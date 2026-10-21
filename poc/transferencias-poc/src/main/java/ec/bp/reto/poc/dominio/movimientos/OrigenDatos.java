package ec.bp.reto.poc.dominio.movimientos;

/** De donde salieron los datos devueltos al cliente. */
public enum OrigenDatos {
    /** Core consultado en linea (cache miss). */
    CORE,
    /** Cache vigente (cache hit, TTL corto). */
    CACHE,
    /** Core no disponible o circuito abierto: ultima copia conocida (respuesta degradada). */
    CACHE_RESPALDO
}
