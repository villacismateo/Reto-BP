package ec.bp.reto.poc.adaptadores.salida.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.bp.reto.poc.aplicacion.puerto.salida.CacheMovimientos;
import ec.bp.reto.poc.dominio.Hashing;
import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache en Valkey (protocolo Redis; ElastiCache en AWS). La clave usa el hash de la cuenta,
 * no el numero en claro. Cualquier fallo del cache degrada a "miss" en lugar de romper la consulta.
 */
@Component
public class ValkeyCacheMovimientos implements CacheMovimientos {
    private static final Logger log = LoggerFactory.getLogger(ValkeyCacheMovimientos.class);
    private static final TypeReference<List<Movimiento>> TIPO = new TypeReference<>() {
    };
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttlVigente;
    private final Duration ttlRespaldo;

    public ValkeyCacheMovimientos(StringRedisTemplate redis, ObjectMapper json,
                                  @Value("${poc.cache.ttl-vigente:30s}") Duration ttlVigente,
                                  @Value("${poc.cache.ttl-respaldo:24h}") Duration ttlRespaldo) {
        this.redis = redis;
        this.json = json;
        this.ttlVigente = ttlVigente;
        this.ttlRespaldo = ttlRespaldo;
    }

    @Override
    public Optional<List<Movimiento>> vigente(NumeroCuenta cuenta) {
        return leer(clave("vigente", cuenta));
    }

    @Override
    public Optional<List<Movimiento>> respaldo(NumeroCuenta cuenta) {
        return leer(clave("respaldo", cuenta));
    }

    @Override
    public void guardar(NumeroCuenta cuenta, List<Movimiento> movimientos) {
        try {
            String valor = json.writeValueAsString(movimientos);
            redis.opsForValue().set(clave("vigente", cuenta), valor, ttlVigente);
            redis.opsForValue().set(clave("respaldo", cuenta), valor, ttlRespaldo);
        } catch (Exception e) {
            log.warn("No se pudo escribir en cache: {}", e.getMessage());
        }
    }

    private Optional<List<Movimiento>> leer(String clave) {
        try {
            String valor = redis.opsForValue().get(clave);
            return valor == null ? Optional.empty() : Optional.of(json.readValue(valor, TIPO));
        } catch (Exception e) {
            log.warn("Cache no disponible, se trata como miss: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static String clave(String nivel, NumeroCuenta cuenta) {
        return "movimientos:" + nivel + ":" + Hashing.sha256(cuenta.valor()).substring(0, 32);
    }
}
