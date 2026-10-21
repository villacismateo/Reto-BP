package ec.bp.reto.poc.dominio.transferencia;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Evento de dominio: el eventoId permite a los consumidores deduplicar (entrega at-least-once del relay). */
public record TransferenciaRegistrada(UUID eventoId,
                                      UUID transferenciaId,
                                      TipoTransferencia tipo,
                                      String cuentaOrigen,
                                      String cuentaDestino,
                                      String bancoDestino,
                                      BigDecimal monto,
                                      String moneda,
                                      Instant ocurridoEn) {
    public static final String TIPO = "transferencia.registrada.v1";
}
