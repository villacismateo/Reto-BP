package ec.bp.reto.poc.aplicacion.puerto.salida;

import ec.bp.reto.poc.dominio.idempotencia.RegistroIdempotencia;

import java.util.Optional;

public interface IdempotenciaRepositorio {
    Optional<RegistroIdempotencia> buscar(String clave);

    /**
     * Reserva atomica de la clave (INSERT ... ON CONFLICT DO NOTHING).
     * @return false si otra solicitud concurrente ya la reservo.
     */
    boolean reservar(RegistroIdempotencia registro);
}
