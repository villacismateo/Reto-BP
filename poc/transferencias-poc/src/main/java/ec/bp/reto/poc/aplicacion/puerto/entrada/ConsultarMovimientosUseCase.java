package ec.bp.reto.poc.aplicacion.puerto.entrada;

import ec.bp.reto.poc.dominio.movimientos.ConsultaMovimientos;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;

/** Puerto de entrada: historico de movimientos con Cache-Aside y degradacion controlada. */
public interface ConsultarMovimientosUseCase {
    ConsultaMovimientos consultar(NumeroCuenta cuenta, String actor);
}
