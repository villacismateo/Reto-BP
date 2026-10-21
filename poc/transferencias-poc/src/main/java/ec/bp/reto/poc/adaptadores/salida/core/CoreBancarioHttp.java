package ec.bp.reto.poc.adaptadores.salida.core;

import ec.bp.reto.poc.aplicacion.puerto.salida.CoreBancario;
import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Adaptador anti-corrupcion hacia el Core: timeout corto + circuit breaker (Resilience4j).
 * Cualquier fallo (timeout, 5xx, circuito abierto) se traduce a CoreNoDisponibleException del puerto.
 */
@Component
public class CoreBancarioHttp implements CoreBancario {
    private final RestClient http;
    private final CircuitBreaker circuito;

    public CoreBancarioHttp(@Value("${poc.core.url}") String urlBase,
                            @Value("${poc.core.timeout-conexion:500ms}") Duration timeoutConexion,
                            @Value("${poc.core.timeout-lectura:1500ms}") Duration timeoutLectura,
                            CircuitBreakerRegistry registro) {
        var fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(timeoutConexion);
        fabrica.setReadTimeout(timeoutLectura);
        this.http = RestClient.builder().baseUrl(urlBase).requestFactory(fabrica).build();
        this.circuito = registro.circuitBreaker("core");
    }

    @Override
    public List<Movimiento> movimientos(NumeroCuenta cuenta) {
        try {
            RespuestaCore r = circuito.executeSupplier(() -> http.get()
                    .uri("/core/cuentas/{cuenta}/movimientos", cuenta.valor())
                    .retrieve()
                    .body(RespuestaCore.class));
            return r == null || r.movimientos() == null ? List.of() : r.movimientos();
        } catch (CallNotPermittedException e) {
            throw new CoreNoDisponibleException("Circuito abierto hacia el Core", e);
        } catch (RuntimeException e) {
            throw new CoreNoDisponibleException("Fallo al consultar el Core: " + e.getMessage(), e);
        }
    }

    /** Contrato del Core (ajeno a nuestro dominio). */
    record RespuestaCore(String cuenta, List<Movimiento> movimientos) {
    }
}
