package ec.bp.reto.poc.aplicacion.puerto.salida;

import ec.bp.reto.poc.dominio.transferencia.Transferencia;

import java.util.Optional;
import java.util.UUID;

public interface TransferenciaRepositorio {
    void guardar(Transferencia transferencia);

    Optional<Transferencia> buscarPorId(UUID id);
}
