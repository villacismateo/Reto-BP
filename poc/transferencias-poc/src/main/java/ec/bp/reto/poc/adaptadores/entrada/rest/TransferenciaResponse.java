package ec.bp.reto.poc.adaptadores.entrada.rest;

import ec.bp.reto.poc.dominio.transferencia.Transferencia;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Representacion HTTP: las cuentas viajan enmascaradas (minimizacion de datos). */
public record TransferenciaResponse(UUID id, String tipo, String estado, String cuentaOrigen, String cuentaDestino,
                                    String bancoDestino, BigDecimal monto, String moneda, String referencia,
                                    Instant creadaEn) {
    static TransferenciaResponse de(Transferencia t) {
        var s = t.solicitud();
        return new TransferenciaResponse(t.id(), s.tipo().name(), t.estado().name(),
                s.cuentaOrigen().enmascarado(), s.cuentaDestino().enmascarado(), s.bancoDestino(),
                s.monto().valor(), s.monto().moneda(), s.referencia(), t.creadaEn());
    }
}
