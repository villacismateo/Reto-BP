package ec.bp.reto.poc.dominio.idempotencia;

import ec.bp.reto.poc.dominio.ReglaNegocioException;

/** Objeto de valor del header Idempotency-Key (recomendado: UUID v4 generado por el cliente). */
public record ClaveIdempotencia(String valor) {
    public ClaveIdempotencia {
        if (valor == null || !valor.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ReglaNegocioException("Idempotency-Key obligatorio: 8-64 caracteres [A-Za-z0-9_-]");
        }
    }
}
