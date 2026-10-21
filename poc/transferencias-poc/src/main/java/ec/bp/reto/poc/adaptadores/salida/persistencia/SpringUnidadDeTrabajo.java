package ec.bp.reto.poc.adaptadores.salida.persistencia;

import ec.bp.reto.poc.aplicacion.puerto.salida.UnidadDeTrabajo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class SpringUnidadDeTrabajo implements UnidadDeTrabajo {
    private final TransactionTemplate tx;

    public SpringUnidadDeTrabajo(TransactionTemplate tx) {
        this.tx = tx;
    }

    @Override
    public <T> T enTransaccion(Supplier<T> trabajo) {
        return tx.execute(estado -> trabajo.get());
    }
}
