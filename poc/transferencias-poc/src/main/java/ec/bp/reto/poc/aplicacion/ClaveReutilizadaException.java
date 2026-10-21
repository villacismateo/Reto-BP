package ec.bp.reto.poc.aplicacion;

/**
 * Reutilizacion indebida de la Idempotency-Key (HTTP 422, ADR-009): la clave ya se uso
 * con un cuerpo de solicitud distinto o en otro recurso u operacion.
 */
public class ClaveReutilizadaException extends RuntimeException {
    private ClaveReutilizadaException(String mensaje) {
        super(mensaje);
    }

    public static ClaveReutilizadaException conCuerpoDistinto() {
        return new ClaveReutilizadaException(
                "La Idempotency-Key ya fue usada con un cuerpo de solicitud distinto; genere una clave nueva");
    }

    public static ClaveReutilizadaException enOtroRecurso(String recursoOriginal) {
        return new ClaveReutilizadaException(
                "La Idempotency-Key ya fue usada en otro recurso: " + recursoOriginal + "; genere una clave nueva");
    }
}
