package ec.bp.reto.poc.dominio.idempotencia;

import java.util.Optional;

/**
 * Regla pura (sin I/O) alineada con ADR-009 y el borrador IETF "The Idempotency-Key HTTP Header Field":
 * <ul>
 *   <li>misma clave + mismo recurso + misma huella = repeticion (se devuelve el resultado original);</li>
 *   <li>misma clave con otra huella (cuerpo distinto) o en otro recurso = 422 (reutilizacion indebida);</li>
 *   <li>misma clave con una solicitud aun en curso (sin resultado final visible) = 409.</li>
 * </ul>
 */
public final class PoliticaIdempotencia {
    private PoliticaIdempotencia() {
    }

    public static DecisionIdempotencia evaluar(Optional<RegistroIdempotencia> existente, String recurso, String huella) {
        if (existente.isEmpty()) {
            return new DecisionIdempotencia.Procesar();
        }
        RegistroIdempotencia r = existente.get();
        if (!r.recurso().equals(recurso)) {
            return new DecisionIdempotencia.ClaveDeOtroRecurso(r.recurso());
        }
        if (!r.huellaSolicitud().equals(huella)) {
            return new DecisionIdempotencia.CuerpoDistinto();
        }
        return new DecisionIdempotencia.Repetir(r.transferenciaId());
    }

    /**
     * Evaluacion despues de perder la reserva atomica de la clave frente a una solicitud concurrente.
     * Si el registro ganador todavia no es visible, esa solicitud sigue en curso: 409 en lugar de procesar dos veces.
     */
    public static DecisionIdempotencia evaluarTrasReservaPerdida(Optional<RegistroIdempotencia> existente,
                                                                 String recurso, String huella) {
        if (existente.isEmpty()) {
            return new DecisionIdempotencia.EnCurso();
        }
        return evaluar(existente, recurso, huella);
    }
}
