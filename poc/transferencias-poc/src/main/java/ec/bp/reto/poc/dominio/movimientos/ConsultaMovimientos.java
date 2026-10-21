package ec.bp.reto.poc.dominio.movimientos;

import java.util.List;

/** Respuesta de la consulta de movimientos con su procedencia y si es una respuesta degradada. */
public record ConsultaMovimientos(String cuenta, List<Movimiento> movimientos, OrigenDatos origen, boolean degradado) {
}
