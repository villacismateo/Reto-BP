package ec.bp.reto.poc.aplicacion.puerto.entrada;

import ec.bp.reto.poc.dominio.idempotencia.ClaveIdempotencia;
import ec.bp.reto.poc.dominio.transferencia.SolicitudTransferencia;
import ec.bp.reto.poc.dominio.transferencia.Transferencia;

/** Puerto de entrada: registrar una transferencia de forma idempotente. */
public interface RegistrarTransferenciaUseCase {

    ResultadoRegistro registrar(ClaveIdempotencia clave, SolicitudTransferencia solicitud, String actor);

    /** repetida=true cuando la respuesta proviene de un reintento con la misma Idempotency-Key. */
    record ResultadoRegistro(Transferencia transferencia, boolean repetida) {
    }
}
