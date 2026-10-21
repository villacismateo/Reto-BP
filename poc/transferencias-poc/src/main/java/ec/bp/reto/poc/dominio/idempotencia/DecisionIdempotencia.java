package ec.bp.reto.poc.dominio.idempotencia;

import java.util.UUID;

/**
 * Resultado de evaluar una clave contra lo ya registrado (ADR-009, borrador IETF
 * "The Idempotency-Key HTTP Header Field").
 */
public sealed interface DecisionIdempotencia {
    /** Primera vez que se ve la clave: procesar. */
    record Procesar() implements DecisionIdempotencia {
    }

    /** Reintento identico ya completado: devolver el resultado original sin re-ejecutar (HTTP 200). */
    record Repetir(UUID transferenciaId) implements DecisionIdempotencia {
    }

    /** Misma clave y mismo recurso pero cuerpo distinto: reutilizacion indebida de la clave (HTTP 422). */
    record CuerpoDistinto() implements DecisionIdempotencia {
    }

    /** Misma clave usada en otro recurso u operacion: reutilizacion indebida de la clave (HTTP 422). */
    record ClaveDeOtroRecurso(String recursoOriginal) implements DecisionIdempotencia {
    }

    /** Otra solicitud con la misma clave aun no tiene resultado final (HTTP 409). */
    record EnCurso() implements DecisionIdempotencia {
    }
}
