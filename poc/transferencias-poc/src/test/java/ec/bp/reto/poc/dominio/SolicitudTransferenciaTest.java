package ec.bp.reto.poc.dominio;

import ec.bp.reto.poc.dominio.transferencia.Monto;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import ec.bp.reto.poc.dominio.transferencia.SolicitudTransferencia;
import ec.bp.reto.poc.dominio.transferencia.TipoTransferencia;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolicitudTransferenciaTest {
    private static final NumeroCuenta A = new NumeroCuenta("1000000001");
    private static final NumeroCuenta B = new NumeroCuenta("1000000002");

    private static SolicitudTransferencia propia(String monto, String ref) {
        return new SolicitudTransferencia(TipoTransferencia.PROPIA, A, B, null, new Monto(new BigDecimal(monto), "USD"), ref);
    }

    @Test
    void laHuellaIgnoraDiferenciasDeFormatoDelMonto() {
        assertThat(propia("25.5", "pago").huella()).isEqualTo(propia("25.50", " pago ").huella());
    }

    @Test
    void laHuellaCambiaSiCambiaElMonto() {
        assertThat(propia("25.50", "pago").huella()).isNotEqualTo(propia("25.51", "pago").huella());
    }

    @Test
    void validaInvariantes() {
        assertThatThrownBy(() -> new SolicitudTransferencia(TipoTransferencia.PROPIA, A, A, null,
                new Monto(BigDecimal.TEN, "USD"), "")).hasMessageContaining("misma");
        assertThatThrownBy(() -> new SolicitudTransferencia(TipoTransferencia.INTERBANCARIA, A, B, " ",
                new Monto(BigDecimal.TEN, "USD"), "")).hasMessageContaining("banco destino");
        assertThatThrownBy(() -> new Monto(new BigDecimal("0"), "USD")).hasMessageContaining("mayor a cero");
        assertThatThrownBy(() -> new Monto(new BigDecimal("1.001"), "USD")).hasMessageContaining("2 decimales");
        assertThatThrownBy(() -> new Monto(new BigDecimal("10000.01"), "USD")).hasMessageContaining("limite");
        assertThatThrownBy(() -> new NumeroCuenta("12AB")).isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void enmascaraLaCuenta() {
        assertThat(A.enmascarado()).isEqualTo("******0001");
    }
}
