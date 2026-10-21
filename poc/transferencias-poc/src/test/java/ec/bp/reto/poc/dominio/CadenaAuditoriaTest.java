package ec.bp.reto.poc.dominio;

import ec.bp.reto.poc.dominio.auditoria.CadenaAuditoria;
import ec.bp.reto.poc.dominio.auditoria.EventoAuditoria;
import ec.bp.reto.poc.dominio.auditoria.RegistroAuditoria;
import ec.bp.reto.poc.dominio.auditoria.ResultadoVerificacion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CadenaAuditoriaTest {

    private static List<RegistroAuditoria> cadena(int n) {
        List<RegistroAuditoria> lista = new ArrayList<>();
        String ultimo = CadenaAuditoria.GENESIS;
        for (int i = 1; i <= n; i++) {
            var e = new EventoAuditoria(Instant.parse("2026-10-19T10:00:00.123456789Z").plusSeconds(i),
                    "usuario" + i, "ACCION", "recurso/" + i, "detalle " + i);
            var r = CadenaAuditoria.encadenar(ultimo, e);
            lista.add(new RegistroAuditoria((long) i, r.evento(), r.hashAnterior(), r.hash()));
            ultimo = r.hash();
        }
        return lista;
    }

    @Test
    void primerEslabonParteDelGenesisYElHashEsDeterminista() {
        var e = new EventoAuditoria(Instant.parse("2026-10-19T10:00:00Z"), "u", "A", "r", "d");
        var r1 = CadenaAuditoria.encadenar(null, e);
        var r2 = CadenaAuditoria.encadenar(null, e);
        assertThat(r1.hashAnterior()).isEqualTo(CadenaAuditoria.GENESIS);
        assertThat(r1.hash()).hasSize(64).isEqualTo(r2.hash());
    }

    @Test
    void cadenaIntactaSeVerifica() {
        ResultadoVerificacion r = CadenaAuditoria.verificar(cadena(5));
        assertThat(r.integra()).isTrue();
        assertThat(r.registrosVerificados()).isEqualTo(5);
    }

    @Test
    void cadenaVaciaEsIntegra() {
        assertThat(CadenaAuditoria.verificar(List.of()).integra()).isTrue();
    }

    @Test
    void detectaContenidoAlterado() {
        var lista = cadena(5);
        var original = lista.get(2);
        var e = original.evento();
        lista.set(2, new RegistroAuditoria(original.id(),
                new EventoAuditoria(e.ocurridoEn(), e.actor(), e.accion(), e.recurso(), "monto=999999"),
                original.hashAnterior(), original.hash()));
        ResultadoVerificacion r = CadenaAuditoria.verificar(lista);
        assertThat(r.integra()).isFalse();
        assertThat(r.primerRegistroAlterado()).isEqualTo(3L);
        assertThat(r.motivo()).contains("contenido alterado");
    }

    @Test
    void detectaRegistroEliminado() {
        var lista = cadena(5);
        lista.remove(1);
        ResultadoVerificacion r = CadenaAuditoria.verificar(lista);
        assertThat(r.integra()).isFalse();
        assertThat(r.primerRegistroAlterado()).isEqualTo(3L);
        assertThat(r.motivo()).contains("hash_anterior");
    }

    @Test
    void elTruncadoAMicrosegundosHaceReproducibleElHash() {
        var conNanos = new EventoAuditoria(Instant.parse("2026-10-19T10:00:00.123456789Z"), "u", "A", "r", "d");
        var conMicros = new EventoAuditoria(Instant.parse("2026-10-19T10:00:00.123456Z"), "u", "A", "r", "d");
        assertThat(CadenaAuditoria.calcularHash(CadenaAuditoria.GENESIS, conNanos))
                .isEqualTo(CadenaAuditoria.calcularHash(CadenaAuditoria.GENESIS, conMicros));
    }
}
