package ec.bp.reto.poc.dominio.idempotencia;

import java.util.UUID;

/** Lo que se recuerda de una clave de idempotencia ya usada. */
public record RegistroIdempotencia(String clave, String recurso, String huellaSolicitud, UUID transferenciaId) {
}
