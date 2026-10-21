package ec.bp.reto.poc.adaptadores.salida.persistencia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.bp.reto.poc.aplicacion.puerto.salida.MensajeOutbox;
import ec.bp.reto.poc.aplicacion.puerto.salida.OutboxRepositorio;
import ec.bp.reto.poc.dominio.transferencia.TransferenciaRegistrada;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxRepositorio implements OutboxRepositorio {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcOutboxRepositorio(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void agregar(TransferenciaRegistrada evento) {
        try {
            jdbc.sql("INSERT INTO outbox (id, tipo, agregado_id, payload) VALUES (:id, :tipo, :agregado, CAST(:payload AS jsonb))")
                    .param("id", evento.eventoId()).param("tipo", TransferenciaRegistrada.TIPO)
                    .param("agregado", evento.transferenciaId()).param("payload", json.writeValueAsString(evento))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento", e);
        }
    }

    @Override
    public List<MensajeOutbox> tomarPendientes(int limite) {
        return jdbc.sql("""
                        SELECT id, tipo, agregado_id, payload::text AS payload FROM outbox
                        WHERE publicado_en IS NULL ORDER BY creado_en
                        LIMIT :limite FOR UPDATE SKIP LOCKED
                        """)
                .param("limite", limite)
                .query((rs, n) -> new MensajeOutbox(rs.getObject("id", UUID.class), rs.getString("tipo"),
                        rs.getObject("agregado_id", UUID.class), rs.getString("payload")))
                .list();
    }

    @Override
    public void marcarPublicado(UUID id) {
        jdbc.sql("UPDATE outbox SET publicado_en = now() WHERE id = :id").param("id", id).update();
    }
}
