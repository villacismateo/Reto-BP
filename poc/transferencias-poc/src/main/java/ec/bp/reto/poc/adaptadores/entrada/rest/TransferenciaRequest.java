package ec.bp.reto.poc.adaptadores.entrada.rest;

import java.math.BigDecimal;

/** Cuerpo HTTP de entrada (las invariantes las valida el dominio). */
public record TransferenciaRequest(String cuentaOrigen, String cuentaDestino, String bancoDestino,
                                   BigDecimal monto, String moneda, String referencia) {
}
