package ec.bp.reto.poc.aplicacion.servicio;

import ec.bp.reto.poc.aplicacion.puerto.entrada.PublicarOutboxUseCase;
import ec.bp.reto.poc.aplicacion.puerto.salida.MensajeOutbox;
import ec.bp.reto.poc.aplicacion.puerto.salida.OutboxRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.PublicadorEventos;
import ec.bp.reto.poc.aplicacion.puerto.salida.UnidadDeTrabajo;

import java.util.List;

/**
 * Relay del outbox (polling publisher). Entrega at-least-once: si el broker acepta pero la marca falla,
 * el mensaje se re-publica y el consumidor deduplica por eventoId.
 */
public class PublicarOutboxService implements PublicarOutboxUseCase {
    private final UnidadDeTrabajo unidadDeTrabajo;
    private final OutboxRepositorio outbox;
    private final PublicadorEventos publicador;

    public PublicarOutboxService(UnidadDeTrabajo unidadDeTrabajo, OutboxRepositorio outbox, PublicadorEventos publicador) {
        this.unidadDeTrabajo = unidadDeTrabajo;
        this.outbox = outbox;
        this.publicador = publicador;
    }

    @Override
    public int publicarPendientes(int tamanoLote) {
        return unidadDeTrabajo.enTransaccion(() -> {
            List<MensajeOutbox> pendientes = outbox.tomarPendientes(tamanoLote);
            for (MensajeOutbox m : pendientes) {
                publicador.publicar(m);
                outbox.marcarPublicado(m.id());
            }
            return pendientes.size();
        });
    }
}
