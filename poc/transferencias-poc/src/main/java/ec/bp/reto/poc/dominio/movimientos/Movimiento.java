package ec.bp.reto.poc.dominio.movimientos;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Movimiento de cuenta tal como lo expone el Core (lectura). */
public record Movimiento(String id, LocalDate fecha, String descripcion, BigDecimal monto, String tipo) {
}
