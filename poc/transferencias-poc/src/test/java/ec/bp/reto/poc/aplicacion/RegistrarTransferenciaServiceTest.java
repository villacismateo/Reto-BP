package ec.bp.reto.poc.aplicacion;

import ec.bp.reto.poc.aplicacion.servicio.RegistradorAuditoria;
import ec.bp.reto.poc.aplicacion.servicio.RegistrarTransferenciaService;
import ec.bp.reto.poc.dominio.auditoria.CadenaAuditoria;
import ec.bp.reto.poc.dominio.idempotencia.ClaveIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.RegistroIdempotencia;
import ec.bp.reto.poc.dominio.transferencia.Monto;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import ec.bp.reto.poc.dominio.transferencia.SolicitudTransferencia;
import ec.bp.reto.poc.dominio.transferencia.TipoTransferencia;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrarTransferenciaServiceTest {
    private final Fakes.Idempotencia idem = new Fakes.Idempotencia();
    private final Fakes.Transferencias repo = new Fakes.Transferencias();
    private final Fakes.Outbox outbox = new Fakes.Outbox();
    private final Fakes.Auditoria auditoria = new Fakes.Auditoria();
    private final RegistrarTransferenciaService servicio = new RegistrarTransferenciaService(Fakes.SIN_TX, idem, repo,
            outbox, new RegistradorAuditoria(auditoria), Clock.systemUTC());
    private final ClaveIdempotencia clave = new ClaveIdempotencia("8c1f6a0e-clave-01");

    private static SolicitudTransferencia solicitud(TipoTransferencia tipo, String monto) {
        return new SolicitudTransferencia(tipo, new NumeroCuenta("1000000001"), new NumeroCuenta("2000000002"),
                tipo == TipoTransferencia.INTERBANCARIA ? "BANCO-X" : null, new Monto(new BigDecimal(monto), "USD"), "ref");
    }

    @Test
    void primeraSolicitudRegistraTransferenciaEventoYAuditoria() {
        var r = servicio.registrar(clave, solicitud(TipoTransferencia.PROPIA, "50.00"), "cliente1");
        assertThat(r.repetida()).isFalse();
        assertThat(repo.datos).hasSize(1);
        assertThat(outbox.eventos).hasSize(1);
        assertThat(outbox.eventos.getFirst().transferenciaId()).isEqualTo(r.transferencia().id());
        assertThat(auditoria.registros).hasSize(1);
        assertThat(auditoria.registros.getFirst().evento().detalle()).contains("******0001").doesNotContain("1000000001");
    }

    @Test
    void reintentoConMismaClaveNoDuplicaYDevuelveLaMismaTransferencia() {
        var primera = servicio.registrar(clave, solicitud(TipoTransferencia.PROPIA, "50.00"), "cliente1");
        var segunda = servicio.registrar(clave, solicitud(TipoTransferencia.PROPIA, "50"), "cliente1");
        assertThat(segunda.repetida()).isTrue();
        assertThat(segunda.transferencia().id()).isEqualTo(primera.transferencia().id());
        assertThat(repo.datos).hasSize(1);
        assertThat(outbox.eventos).hasSize(1);
        assertThat(auditoria.registros).hasSize(2);
        assertThat(CadenaAuditoria.verificar(auditoria.registros).integra()).isTrue();
    }

    @Test
    void mismaClaveConOtroMontoEsReutilizacionIndebida() {
        servicio.registrar(clave, solicitud(TipoTransferencia.PROPIA, "50.00"), "cliente1");
        assertThatThrownBy(() -> servicio.registrar(clave, solicitud(TipoTransferencia.PROPIA, "51.00"), "cliente1"))
                .isInstanceOf(ClaveReutilizadaException.class)
                .hasMessageContaining("cuerpo de solicitud distinto");
        assertThat(repo.datos).hasSize(1);
    }

    @Test
    void mismaClaveEnOtroTipoDeTransferenciaEsClaveReutilizada() {
        servicio.registrar(clave, solicitud(TipoTransferencia.PROPIA, "50.00"), "cliente1");
        assertThatThrownBy(() -> servicio.registrar(clave, solicitud(TipoTransferencia.INTERBANCARIA, "50.00"), "cliente1"))
                .isInstanceOf(ClaveReutilizadaException.class)
                .hasMessageContaining("otro recurso");
    }

    @Test
    void claveReservadaPorSolicitudConcurrenteAunEnCursoEsConflicto() {
        // Simula la carrera: otra solicitud ya reservo la clave, pero su registro aun no es visible (sin commit).
        var idemEnCurso = new Fakes.Idempotencia() {
            @Override
            public boolean reservar(RegistroIdempotencia r) {
                return false;
            }
        };
        var conCarrera = new RegistrarTransferenciaService(Fakes.SIN_TX, idemEnCurso, repo, outbox,
                new RegistradorAuditoria(auditoria), Clock.systemUTC());
        assertThatThrownBy(() -> conCarrera.registrar(clave, solicitud(TipoTransferencia.PROPIA, "50.00"), "cliente1"))
                .isInstanceOf(ClaveEnCursoException.class);
        assertThat(repo.datos).isEmpty();
        assertThat(outbox.eventos).isEmpty();
    }
}
