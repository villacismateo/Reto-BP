package ec.bp.reto.poc.aplicacion.servicio;

import ec.bp.reto.poc.aplicacion.ServicioNoDisponibleException;
import ec.bp.reto.poc.aplicacion.puerto.entrada.ConsultarMovimientosUseCase;
import ec.bp.reto.poc.aplicacion.puerto.salida.CacheMovimientos;
import ec.bp.reto.poc.aplicacion.puerto.salida.CoreBancario;
import ec.bp.reto.poc.aplicacion.puerto.salida.UnidadDeTrabajo;
import ec.bp.reto.poc.dominio.auditoria.EventoAuditoria;
import ec.bp.reto.poc.dominio.movimientos.ConsultaMovimientos;
import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.movimientos.OrigenDatos;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Cache-Aside: 1) cache vigente; 2) si falla, Core (protegido por circuit breaker) y se puebla el cache;
 * 3) si el Core no esta disponible, fallback a la ultima copia conocida marcada como degradada; 4) si no hay, 503.
 */
public class ConsultarMovimientosService implements ConsultarMovimientosUseCase {
    private final CacheMovimientos cache;
    private final CoreBancario core;
    private final UnidadDeTrabajo unidadDeTrabajo;
    private final RegistradorAuditoria auditoria;
    private final Clock reloj;

    public ConsultarMovimientosService(CacheMovimientos cache, CoreBancario core, UnidadDeTrabajo unidadDeTrabajo,
                                       RegistradorAuditoria auditoria, Clock reloj) {
        this.cache = cache;
        this.core = core;
        this.unidadDeTrabajo = unidadDeTrabajo;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Override
    public ConsultaMovimientos consultar(NumeroCuenta cuenta, String actor) {
        ConsultaMovimientos resultado = resolver(cuenta);
        unidadDeTrabajo.enTransaccion(() -> {
            auditoria.registrar(new EventoAuditoria(reloj.instant(), actor, "CONSULTA_MOVIMIENTOS",
                    "cuentas/" + cuenta.enmascarado() + "/movimientos",
                    "origen=" + resultado.origen() + ";registros=" + resultado.movimientos().size()));
            return null;
        });
        return resultado;
    }

    private ConsultaMovimientos resolver(NumeroCuenta cuenta) {
        Optional<List<Movimiento>> vigente = cache.vigente(cuenta);
        if (vigente.isPresent()) {
            return new ConsultaMovimientos(cuenta.valor(), vigente.get(), OrigenDatos.CACHE, false);
        }
        try {
            List<Movimiento> movimientos = core.movimientos(cuenta);
            cache.guardar(cuenta, movimientos);
            return new ConsultaMovimientos(cuenta.valor(), movimientos, OrigenDatos.CORE, false);
        } catch (CoreBancario.CoreNoDisponibleException e) {
            return cache.respaldo(cuenta)
                    .map(m -> new ConsultaMovimientos(cuenta.valor(), m, OrigenDatos.CACHE_RESPALDO, true))
                    .orElseThrow(() -> new ServicioNoDisponibleException(
                            "Core no disponible y sin copia de respaldo para la cuenta " + cuenta.enmascarado()));
        }
    }
}
