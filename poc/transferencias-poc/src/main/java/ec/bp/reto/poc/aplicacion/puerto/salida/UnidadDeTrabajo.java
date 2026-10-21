package ec.bp.reto.poc.aplicacion.puerto.salida;

import java.util.function.Supplier;

/** Limite transaccional expresado como puerto para no acoplar la aplicacion a Spring. */
public interface UnidadDeTrabajo {
    <T> T enTransaccion(Supplier<T> trabajo);
}
