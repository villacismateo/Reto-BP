package ec.bp.reto.poc.dominio.transferencia;

/** En la PoC la transferencia queda REGISTRADA; la ejecucion contra el Core / red interbancaria consume el evento del outbox. */
public enum EstadoTransferencia {
    REGISTRADA,
    EJECUTADA,
    RECHAZADA
}
