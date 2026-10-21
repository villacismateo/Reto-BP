package ec.bp.reto.poc.dominio.transferencia;

/** Tipo de transferencia; tambien identifica el "recurso" protegido por la clave de idempotencia. */
public enum TipoTransferencia {
    PROPIA,
    INTERBANCARIA
}
