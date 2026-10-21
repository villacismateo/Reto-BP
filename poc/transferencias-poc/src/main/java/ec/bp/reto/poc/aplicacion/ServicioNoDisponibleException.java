package ec.bp.reto.poc.aplicacion;

/** Ni el Core ni el cache de respaldo pueden responder (HTTP 503). */
public class ServicioNoDisponibleException extends RuntimeException {
    public ServicioNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
