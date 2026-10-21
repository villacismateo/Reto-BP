package ec.bp.reto.poc.integracion;

import com.github.tomakehurst.wiremock.WireMockServer;
import ec.bp.reto.poc.adaptadores.entrada.programado.OutboxRelayProgramado;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integracion de punta a punta con PostgreSQL y Valkey reales (Testcontainers) y el Core simulado con WireMock.
 * Requiere Docker; si no hay Docker, Testcontainers aborta la clase (disabledWithoutDocker).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"poc.outbox.intervalo-ms=200", "poc.cache.ttl-vigente=1s"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferenciasPocIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8.0-alpine").withExposedPorts(6379);

    static WireMockServer core = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        core.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("poc.core.url", core::baseUrl);
    }

    @AfterAll
    static void detener() {
        core.stop();
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    OutboxRelayProgramado relay;

    private static final String CUERPO = """
            {"cuentaOrigen":"1000000001","cuentaDestino":"2000000002","monto":%s,"moneda":"USD","referencia":"Arriendo"}""";

    private ResponseEntity<Map> post(String ruta, String clave, String cuerpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", clave);
        h.set("X-Usuario", "cliente-it");
        return http.exchange(ruta, HttpMethod.POST, new HttpEntity<>(cuerpo, h), Map.class);
    }

    @Test
    @Order(1)
    void transferenciaIdempotenteConOutboxPublicado() throws Exception {
        String clave = "it-clave-0000000001";
        var r1 = post("/api/v1/transferencias/propias", clave, CUERPO.formatted("125.50"));
        assertThat(r1.getStatusCode().value()).isEqualTo(201);

        var r2 = post("/api/v1/transferencias/propias", clave, CUERPO.formatted("125.5"));
        assertThat(r2.getStatusCode().value()).isEqualTo(200);
        assertThat(r2.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(r2.getBody().get("id")).isEqualTo(r1.getBody().get("id"));

        // ADR-009: misma clave con otro cuerpo -> 422 (reutilizacion indebida), no 409
        var cuerpoDistinto = post("/api/v1/transferencias/propias", clave, CUERPO.formatted("999.00"));
        assertThat(cuerpoDistinto.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(cuerpoDistinto.getBody().get("detail"))).contains("cuerpo de solicitud distinto");
        String interbancaria = """
                {"cuentaOrigen":"1000000001","cuentaDestino":"2000000002","bancoDestino":"BANCO-X","monto":125.50,"referencia":"Arriendo"}""";
        var otroRecurso = post("/api/v1/transferencias/interbancarias", clave, interbancaria);
        assertThat(otroRecurso.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(otroRecurso.getBody().get("detail"))).contains("otro recurso");
        assertThat(post("/api/v1/transferencias/propias", "", CUERPO.formatted("1")).getStatusCode().value())
                .isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transferencia", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);

        // El relay publica en el stream (polling cada 200 ms)
        long limite = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < limite
                && jdbc.queryForObject("SELECT count(*) FROM outbox WHERE publicado_en IS NULL", Integer.class) > 0) {
            Thread.sleep(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE publicado_en IS NULL", Integer.class)).isZero();
        assertThat(redis.opsForStream().size("transferencias.eventos")).isEqualTo(1L);
    }

    @Test
    @Order(2)
    void movimientosCacheAsideYFallbackCuandoElCoreCae() throws Exception {
        core.stubFor(get(urlPathMatching("/core/cuentas/.*/movimientos")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"cuenta":"1000000001","movimientos":[
                          {"id":"m1","fecha":"2026-10-01","descripcion":"Deposito","monto":100.00,"tipo":"CREDITO"}]}""")));
        String ruta = "/api/v1/cuentas/1000000001/movimientos";

        var primera = http.getForEntity(ruta, Map.class);
        assertThat(primera.getHeaders().getFirst("X-Origen-Datos")).isEqualTo("CORE");
        assertThat(http.getForEntity(ruta, Map.class).getHeaders().getFirst("X-Origen-Datos")).isEqualTo("CACHE");

        // Core cae y expira el TTL vigente (1 s en esta prueba): se sirve la copia de respaldo degradada.
        core.resetAll();
        core.stubFor(get(urlPathMatching("/core/cuentas/.*/movimientos")).willReturn(aResponse().withStatus(503)));
        Thread.sleep(1_200);
        var degradada = http.getForEntity(ruta, Map.class);
        assertThat(degradada.getStatusCode().value()).isEqualTo(200);
        assertThat(degradada.getHeaders().getFirst("X-Origen-Datos")).isEqualTo("CACHE_RESPALDO");
        assertThat(degradada.getBody().get("degradado")).isEqualTo(true);

        // Cuenta sin copia de respaldo y Core caido: 503
        assertThat(http.getForEntity("/api/v1/cuentas/3000000003/movimientos", Map.class).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    @Order(3)
    void cadenaDeAuditoriaIntegraYDetectaAlteracion() {
        var ok = http.getForEntity("/api/v1/auditoria/verificacion", Map.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("integra")).isEqualTo(true);
        assertThat(((Number) ok.getBody().get("registrosVerificados")).longValue()).isGreaterThanOrEqualTo(4);

        jdbc.update("UPDATE auditoria SET detalle = detalle || ';monto=1.00' WHERE id = 1");
        var rota = http.getForEntity("/api/v1/auditoria/verificacion", Map.class);
        assertThat(rota.getStatusCode().value()).isEqualTo(409);
        assertThat(rota.getBody().get("integra")).isEqualTo(false);
        assertThat(((Number) rota.getBody().get("primerRegistroAlterado")).longValue()).isEqualTo(1L);
    }
}
