package ec.bp.reto.poc.aplicacion;

/** Otra solicitud con la misma Idempotency-Key sigue en curso (HTTP 409, ADR-009). */
public class ClaveEnCursoException extends RuntimeException {
    public ClaveEnCursoException() {
        super("Hay una solicitud en curso con la misma Idempotency-Key; reintente en unos segundos");
    }
}
