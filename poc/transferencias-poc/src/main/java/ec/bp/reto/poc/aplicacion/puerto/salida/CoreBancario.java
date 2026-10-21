package ec.bp.reto.poc.aplicacion.puerto.salida;

import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;

import java.util.List;

/** Core bancario (sistema externo). El adaptador aplica timeout + circuit breaker. */
public interface CoreBancario {
    /** @throws CoreNoDisponibleException si el Core falla, excede el timeout o el circuito esta abierto. */
    List<Movimiento> movimientos(NumeroCuenta cuenta);

    class CoreNoDisponibleException extends RuntimeException {
        public CoreNoDisponibleException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
