package ec.bp.reto.poc.dominio.transferencia;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Agregado raiz Transferencia. */
public record Transferencia(UUID id,
                            SolicitudTransferencia solicitud,
                            EstadoTransferencia estado,
                            String solicitante,
                            Instant creadaEn) {

    public static Transferencia registrar(SolicitudTransferencia solicitud, String solicitante, Instant ahora) {
        return new Transferencia(UUID.randomUUID(), solicitud, EstadoTransferencia.REGISTRADA, solicitante,
                ahora.truncatedTo(ChronoUnit.MICROS));
    }

    /** Evento de dominio que se persiste en el outbox en la misma transaccion que la transferencia. */
    public TransferenciaRegistrada eventoRegistrada() {
        return new TransferenciaRegistrada(UUID.randomUUID(), id, solicitud.tipo(),
                solicitud.cuentaOrigen().valor(), solicitud.cuentaDestino().valor(), solicitud.bancoDestino(),
                solicitud.monto().valor(), solicitud.monto().moneda(), creadaEn);
    }
}
