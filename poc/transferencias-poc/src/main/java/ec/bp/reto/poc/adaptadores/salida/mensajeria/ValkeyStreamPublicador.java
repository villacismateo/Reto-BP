package ec.bp.reto.poc.adaptadores.salida.mensajeria;

import ec.bp.reto.poc.aplicacion.puerto.salida.MensajeOutbox;
import ec.bp.reto.poc.aplicacion.puerto.salida.PublicadorEventos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Publica en un Valkey Stream (sustituto local de EventBridge/MSK del diseno objetivo). */
@Component
public class ValkeyStreamPublicador implements PublicadorEventos {
    private final StringRedisTemplate redis;
    private final String stream;

    public ValkeyStreamPublicador(StringRedisTemplate redis, @Value("${poc.eventos.stream:transferencias.eventos}") String stream) {
        this.redis = redis;
        this.stream = stream;
    }

    @Override
    public void publicar(MensajeOutbox m) {
        redis.opsForStream().add(StreamRecords.string(Map.of(
                "eventoId", m.id().toString(),
                "tipo", m.tipo(),
                "agregadoId", m.agregadoId().toString(),
                "payload", m.payload())).withStreamKey(stream));
    }
}
