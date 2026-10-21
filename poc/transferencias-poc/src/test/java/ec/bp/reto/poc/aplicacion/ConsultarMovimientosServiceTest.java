package ec.bp.reto.poc.aplicacion;

import ec.bp.reto.poc.aplicacion.servicio.ConsultarMovimientosService;
import ec.bp.reto.poc.aplicacion.servicio.RegistradorAuditoria;
import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.movimientos.OrigenDatos;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsultarMovimientosServiceTest {
    private final Fakes.Cache cache = new Fakes.Cache();
    private final Fakes.Core core = new Fakes.Core();
    private final Fakes.Auditoria auditoria = new Fakes.Auditoria();
    private final ConsultarMovimientosService servicio = new ConsultarMovimientosService(cache, core, Fakes.SIN_TX,
            new RegistradorAuditoria(auditoria), Clock.systemUTC());
    private final NumeroCuenta cuenta = new NumeroCuenta("1000000001");

    {
        core.movimientos = List.of(new Movimiento("m1", LocalDate.of(2026, 10, 1), "Deposito", new BigDecimal("100.00"), "CREDITO"));
    }

    @Test
    void cacheMissVaAlCoreYPuebla_luegoCacheHitNoLlamaAlCore() {
        assertThat(servicio.consultar(cuenta, "u").origen()).isEqualTo(OrigenDatos.CORE);
        assertThat(servicio.consultar(cuenta, "u").origen()).isEqualTo(OrigenDatos.CACHE);
        assertThat(core.llamadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(2);
    }

    @Test
    void coreCaidoConRespaldoDevuelveRespuestaDegradada() {
        servicio.consultar(cuenta, "u");
        cache.vigente.clear(); // expira el TTL corto
        core.caido = true;
        var r = servicio.consultar(cuenta, "u");
        assertThat(r.origen()).isEqualTo(OrigenDatos.CACHE_RESPALDO);
        assertThat(r.degradado()).isTrue();
        assertThat(r.movimientos()).hasSize(1);
    }

    @Test
    void coreCaidoSinRespaldoEsServicioNoDisponible() {
        core.caido = true;
        assertThatThrownBy(() -> servicio.consultar(cuenta, "u")).isInstanceOf(ServicioNoDisponibleException.class);
    }
}
