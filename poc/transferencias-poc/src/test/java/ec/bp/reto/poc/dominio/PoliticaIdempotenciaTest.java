package ec.bp.reto.poc.dominio;

import ec.bp.reto.poc.dominio.idempotencia.ClaveIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.DecisionIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.PoliticaIdempotencia;
import ec.bp.reto.poc.dominio.idempotencia.RegistroIdempotencia;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoliticaIdempotenciaTest {
    private final UUID id = UUID.randomUUID();
    private final RegistroIdempotencia existente =
            new RegistroIdempotencia("clave-0001", "transferencias/propia", "huellaA", id);

    @Test
    void claveNuevaSeProcesa() {
        assertThat(PoliticaIdempotencia.evaluar(Optional.empty(), "transferencias/propia", "huellaA"))
                .isInstanceOf(DecisionIdempotencia.Procesar.class);
    }

    @Test
    void mismaClaveMismoCuerpoSeRepiteConElResultadoOriginal() {
        assertThat(PoliticaIdempotencia.evaluar(Optional.of(existente), "transferencias/propia", "huellaA"))
                .isEqualTo(new DecisionIdempotencia.Repetir(id));
    }

    @Test
    void mismaClaveCuerpoDistintoEsReutilizacionIndebida() {
        assertThat(PoliticaIdempotencia.evaluar(Optional.of(existente), "transferencias/propia", "huellaB"))
                .isInstanceOf(DecisionIdempotencia.CuerpoDistinto.class);
    }

    @Test
    void reservaPerdidaSinRegistroVisibleEstaEnCurso() {
        assertThat(PoliticaIdempotencia.evaluarTrasReservaPerdida(Optional.empty(), "transferencias/propia", "huellaA"))
                .isInstanceOf(DecisionIdempotencia.EnCurso.class);
    }

    @Test
    void reservaPerdidaConRegistroVisibleAplicaLaReglaNormal() {
        assertThat(PoliticaIdempotencia.evaluarTrasReservaPerdida(Optional.of(existente), "transferencias/propia", "huellaA"))
                .isEqualTo(new DecisionIdempotencia.Repetir(id));
    }

    @Test
    void mismaClaveEnOtroRecursoSeRechazaAunqueElCuerpoCoincida() {
        assertThat(PoliticaIdempotencia.evaluar(Optional.of(existente), "transferencias/interbancaria", "huellaA"))
                .isEqualTo(new DecisionIdempotencia.ClaveDeOtroRecurso("transferencias/propia"));
    }

    @Test
    void claveConFormatoInvalidoSeRechaza() {
        assertThatThrownBy(() -> new ClaveIdempotencia("corta")).isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> new ClaveIdempotencia(null)).isInstanceOf(ReglaNegocioException.class);
    }
}
