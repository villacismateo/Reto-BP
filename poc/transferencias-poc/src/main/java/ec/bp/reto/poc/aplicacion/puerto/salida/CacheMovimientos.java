package ec.bp.reto.poc.aplicacion.puerto.salida;

import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;

import java.util.List;
import java.util.Optional;

/**
 * Cache de movimientos con dos niveles de frescura:
 * vigente (TTL corto, Cache-Aside) y respaldo (TTL largo, solo para degradacion si el Core cae).
 * Los fallos del cache NO deben romper la consulta: el adaptador devuelve vacio y registra el error.
 */
public interface CacheMovimientos {
    Optional<List<Movimiento>> vigente(NumeroCuenta cuenta);

    Optional<List<Movimiento>> respaldo(NumeroCuenta cuenta);

    void guardar(NumeroCuenta cuenta, List<Movimiento> movimientos);
}
