package ec.bp.reto.poc.aplicacion.servicio;

import ec.bp.reto.poc.aplicacion.ClaveEnCursoException;
import ec.bp.reto.poc.aplicacion.ClaveReutilizadaException;
import ec.bp.reto.poc.aplicacion.puerto.entrada.RegistrarTransferenciaUseCase;
import ec.bp.reto.poc.aplicacion.puerto.salida.IdempotenciaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.OutboxRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.TransferenciaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.UnidadDeTrabajo;
import ec.bp.reto.poc.dominio.auditoria.EventoAuditoria;
import ec.bp.reto.poc.dominio.idempotencia.ClaveIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.DecisionIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.PoliticaIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.RegistroIdempotencia;
import ec.bp.reto.poc.dominio.transferencia.SolicitudTransferencia;
import ec.bp.reto.poc.dominio.transferencia.Transferencia;

import java.time.Clock;

/**
 * Caso de uso: registrar transferencia.
 * En UNA transaccion: reserva de la clave de idempotencia + transferencia + evento en outbox + auditoria.
 * Si la transaccion falla no queda nada (ni evento huerfano ni clave consumida).
 */
public class RegistrarTransferenciaService implements RegistrarTransferenciaUseCase {
    private final UnidadDeTrabajo unidadDeTrabajo;
    private final IdempotenciaRepositorio idempotencia;
    private final TransferenciaRepositorio transferencias;
    private final OutboxRepositorio outbox;
    private final RegistradorAuditoria auditoria;
    private final Clock reloj;

    public RegistrarTransferenciaService(UnidadDeTrabajo unidadDeTrabajo, IdempotenciaRepositorio idempotencia,
                                         TransferenciaRepositorio transferencias, OutboxRepositorio outbox,
                                         RegistradorAuditoria auditoria, Clock reloj) {
        this.unidadDeTrabajo = unidadDeTrabajo;
        this.idempotencia = idempotencia;
        this.transferencias = transferencias;
        this.outbox = outbox;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Override
    public ResultadoRegistro registrar(ClaveIdempotencia clave, SolicitudTransferencia solicitud, String actor) {
        String recurso = "transferencias/" + solicitud.tipo().name().toLowerCase();
        String huella = solicitud.huella();
        return unidadDeTrabajo.enTransaccion(() -> {
            DecisionIdempotencia decision =
                    PoliticaIdempotencia.evaluar(idempotencia.buscar(clave.valor()), recurso, huella);
            if (decision instanceof DecisionIdempotencia.Procesar) {
                Transferencia nueva = Transferencia.registrar(solicitud, actor, reloj.instant());
                if (idempotencia.reservar(new RegistroIdempotencia(clave.valor(), recurso, huella, nueva.id()))) {
                    return procesar(nueva, actor, recurso);
                }
                // Carrera: otra solicitud concurrente con la misma clave gano la reserva -> reevaluar
                // (si su registro aun no es visible, sigue en curso -> 409).
                decision = PoliticaIdempotencia.evaluarTrasReservaPerdida(idempotencia.buscar(clave.valor()),
                        recurso, huella);
            }
            return resolverExistente(decision, actor, recurso);
        });
    }

    private ResultadoRegistro procesar(Transferencia nueva, String actor, String recurso) {
        transferencias.guardar(nueva);
        outbox.agregar(nueva.eventoRegistrada());
        auditoria.registrar(new EventoAuditoria(reloj.instant(), actor, "TRANSFERENCIA_REGISTRADA", recurso,
                detalle(nueva)));
        return new ResultadoRegistro(nueva, false);
    }

    private ResultadoRegistro resolverExistente(DecisionIdempotencia decision, String actor, String recurso) {
        return switch (decision) {
            case DecisionIdempotencia.Repetir r -> {
                Transferencia original = transferencias.buscarPorId(r.transferenciaId())
                        .orElseThrow(() -> new IllegalStateException("Clave sin transferencia: " + r.transferenciaId()));
                auditoria.registrar(new EventoAuditoria(reloj.instant(), actor, "TRANSFERENCIA_REINTENTO_IDEMPOTENTE",
                        recurso, "transferencia=" + original.id()));
                yield new ResultadoRegistro(original, true);
            }
            case DecisionIdempotencia.CuerpoDistinto c -> throw ClaveReutilizadaException.conCuerpoDistinto();
            case DecisionIdempotencia.ClaveDeOtroRecurso o -> throw ClaveReutilizadaException.enOtroRecurso(o.recursoOriginal());
            case DecisionIdempotencia.EnCurso e -> throw new ClaveEnCursoException();
            case DecisionIdempotencia.Procesar p -> throw new IllegalStateException("Reserva perdida sin decision");
        };
    }

    private static String detalle(Transferencia t) {
        var s = t.solicitud();
        return "transferencia=" + t.id()
                + ";origen=" + s.cuentaOrigen().enmascarado()
                + ";destino=" + s.cuentaDestino().enmascarado()
                + ";monto=" + s.monto().valor().toPlainString() + " " + s.monto().moneda();
    }
}
