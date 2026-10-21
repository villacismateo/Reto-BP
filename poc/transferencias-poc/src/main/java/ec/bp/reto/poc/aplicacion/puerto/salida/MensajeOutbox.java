package ec.bp.reto.poc.aplicacion.puerto.salida;

import java.util.UUID;

/** Mensaje serializado pendiente de publicar. */
public record MensajeOutbox(UUID id, String tipo, UUID agregadoId, String payload) {
}
