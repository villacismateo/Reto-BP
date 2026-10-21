package ec.bp.reto.poc.aplicacion.puerto.entrada;

/** Puerto de entrada (lo dispara un scheduler): publicar los mensajes pendientes del outbox. */
public interface PublicarOutboxUseCase {
    /** @return cantidad de mensajes publicados en esta pasada. */
    int publicarPendientes(int tamanoLote);
}
