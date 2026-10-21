package ec.bp.reto.poc.dominio.transferencia;

import ec.bp.reto.poc.dominio.ReglaNegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Objeto de valor: importe positivo con maximo 2 decimales en una moneda ISO-4217 (USD en Ecuador). */
public record Monto(BigDecimal valor, String moneda) {
    public static final BigDecimal MAXIMO_POR_OPERACION = new BigDecimal("10000.00");

    public Monto {
        Objects.requireNonNull(valor, "valor");
        if (moneda == null || !moneda.matches("[A-Z]{3}")) {
            throw new ReglaNegocioException("Moneda invalida (ISO-4217 de 3 letras)");
        }
        if (valor.signum() <= 0) {
            throw new ReglaNegocioException("El monto debe ser mayor a cero");
        }
        if (valor.stripTrailingZeros().scale() > 2) {
            throw new ReglaNegocioException("El monto admite maximo 2 decimales");
        }
        if (valor.compareTo(MAXIMO_POR_OPERACION) > 0) {
            throw new ReglaNegocioException("El monto supera el limite por operacion de " + MAXIMO_POR_OPERACION);
        }
        valor = valor.setScale(2, RoundingMode.UNNECESSARY);
    }
}
