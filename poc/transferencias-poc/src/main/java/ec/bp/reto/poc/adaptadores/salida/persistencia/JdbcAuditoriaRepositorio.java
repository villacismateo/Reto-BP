package ec.bp.reto.poc.adaptadores.salida.persistencia;

import ec.bp.reto.poc.aplicacion.puerto.salida.AuditoriaRepositorio;
import ec.bp.reto.poc.dominio.auditoria.EventoAuditoria;
import ec.bp.reto.poc.dominio.auditoria.RegistroAuditoria;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAuditoriaRepositorio implements AuditoriaRepositorio {
    /** Clave arbitraria del advisory lock que serializa a los escritores de la cadena. */
    private static final long LOCK_CADENA = 73_110_001L;
    private final JdbcClient jdbc;

    public JdbcAuditoriaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bloquearCadena() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", LOCK_CADENA).query().singleRow();
    }

    @Override
    public Optional<String> ultimoHash() {
        return jdbc.sql("SELECT hash FROM auditoria ORDER BY id DESC LIMIT 1").query(String.class).optional();
    }

    @Override
    public void anexar(RegistroAuditoria r) {
        var e = r.evento();
        jdbc.sql("""
                        INSERT INTO auditoria (ocurrido_en, actor, accion, recurso, detalle, hash_anterior, hash)
                        VALUES (:ocurridoEn, :actor, :accion, :recurso, :detalle, :hashAnterior, :hash)
                        """)
                .param("ocurridoEn", Timestamp.from(e.ocurridoEn())).param("actor", e.actor())
                .param("accion", e.accion()).param("recurso", e.recurso()).param("detalle", e.detalle())
                .param("hashAnterior", r.hashAnterior()).param("hash", r.hash())
                .update();
    }

    @Override
    public List<RegistroAuditoria> listarEnOrden() {
        return jdbc.sql("SELECT * FROM auditoria ORDER BY id")
                .query((rs, n) -> new RegistroAuditoria(rs.getLong("id"),
                        new EventoAuditoria(rs.getTimestamp("ocurrido_en").toInstant(), rs.getString("actor"),
                                rs.getString("accion"), rs.getString("recurso"), rs.getString("detalle")),
                        rs.getString("hash_anterior"), rs.getString("hash")))
                .list();
    }
}
