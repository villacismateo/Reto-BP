package ec.bp.reto.poc.dominio.transferencia;

import ec.bp.reto.poc.dominio.Hashing;
import ec.bp.reto.poc.dominio.ReglaNegocioException;

/**
 * Intencion del cliente de transferir. Valida invariantes y calcula la huella canonica
 * usada por la politica de idempotencia para detectar que el cuerpo cambio con la misma clave.
 */
public record SolicitudTransferencia(TipoTransferencia tipo,
                                     NumeroCuenta cuentaOrigen,
                                     NumeroCuenta cuentaDestino,
                                     String bancoDestino,
                                     Monto monto,
                                     String referencia) {

    public SolicitudTransferencia {
        if (tipo == null || cuentaOrigen == null || cuentaDestino == null || monto == null) {
            throw new ReglaNegocioException("Tipo, cuentas y monto son obligatorios");
        }
        if (cuentaOrigen.equals(cuentaDestino)) {
            throw new ReglaNegocioException("La cuenta origen y destino no pueden ser la misma");
        }
        if (tipo == TipoTransferencia.INTERBANCARIA && (bancoDestino == null || bancoDestino.isBlank())) {
            throw new ReglaNegocioException("Las transferencias interbancarias requieren banco destino");
        }
        if (tipo == TipoTransferencia.PROPIA) {
            bancoDestino = null;
        }
        referencia = referencia == null ? "" : referencia.strip();
        if (referencia.length() > 140) {
            throw new ReglaNegocioException("La referencia admite maximo 140 caracteres");
        }
    }

    /** Huella SHA-256 de la representacion canonica (independiente del formato JSON de entrada). */
    public String huella() {
        return Hashing.sha256(String.join("|",
                tipo.name(), cuentaOrigen.valor(), cuentaDestino.valor(),
                bancoDestino == null ? "" : bancoDestino,
                monto.valor().toPlainString(), monto.moneda(), referencia));
    }
}
