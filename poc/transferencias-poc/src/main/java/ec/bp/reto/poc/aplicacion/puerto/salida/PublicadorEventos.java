package ec.bp.reto.poc.aplicacion.puerto.salida;

/** Broker de eventos (Valkey Streams en la PoC; Amazon EventBridge / MSK en el diseno objetivo). */
public interface PublicadorEventos {
    void publicar(MensajeOutbox mensaje);
}
