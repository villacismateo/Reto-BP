package ec.bp.reto.poc.dominio;

/** Violacion de una regla de negocio del dominio (se traduce a HTTP 400 en el adaptador REST). */
public class ReglaNegocioException extends RuntimeException {
    public ReglaNegocioException(String mensaje) {
        super(mensaje);
    }
}
