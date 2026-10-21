# PoC: transferencias resilientes y auditables

Esta prueba de concepto se puede ejecutar y sirve para demostrar **4 decisiones** del diseño de la banca por internet. No es un sistema productivo: es lo mínimo necesario para comprobar que los patrones elegidos funcionan juntos, con pruebas automáticas y un `docker compose up`.

| # | Decisión del diseño | Qué demuestra la PoC |
|---|---|---|
| 1 | API Gateway / BFF delante de los microservicios | `gateway/nginx.conf`: punto único de entrada, rate limiting por IP (429), `X-Request-Id` de correlación, límite de tamaño de cuerpo, logs JSON y `/actuator` oculto |
| 2 | Consulta de movimientos con **Cache-Aside** + **Circuit Breaker** con fallback | Cache vigente (TTL corto) → Core (timeout + Resilience4j) → copia de respaldo **degradada** si el Core cae → 503 si no hay copia |
| 3 | Transferencia **idempotente** + **Transactional Outbox** | `Idempotency-Key` según ADR-009: 201 / 200 repetido / 422 (otro cuerpo u otro recurso) / 409 (clave en curso). Transferencia, clave, evento y auditoría se guardan en **una** transacción de PostgreSQL; un relay publica en un stream de Valkey |
| 4 | Auditoría **a prueba de manipulación** (detecta alteraciones) | Cada registro guarda `hash_anterior` y `hash = SHA-256(hash_anterior | contenido)`. `GET /api/v1/auditoria/verificacion` detecta alteraciones, borrados y reordenamientos |

## Arquitectura (hexagonal)

```
             +-------------------+        +-----------------------------------------------+
 cliente --> | gateway (nginx)   | -----> | transferencias-poc (Spring Boot 3.5, Java 21) |
             | rate limit, X-Req |        |                                               |
             +-------------------+        |  adaptadores/entrada  REST, scheduler relay   |
                                          |        |                                      |
                                          |  aplicacion  casos de uso + puertos           |
                                          |        |                                      |
                                          |  dominio  Transferencia, PoliticaIdempotencia,|
                                          |           CadenaAuditoria (sin frameworks)    |
                                          |        |                                      |
                                          |  adaptadores/salida  JDBC, Valkey, Core HTTP  |
                                          +----+-----------+-------------+----------------+
                                               |           |             |
                                         PostgreSQL     Valkey      core-mock (WireMock)
                                   (transf., idempot.,  (cache +    latencia y fallas
                                    outbox, auditoria)   stream)     inyectables
```

```
transferencias-poc/src/main/java/ec/bp/reto/poc/
  dominio/            Reglas puras: Monto, NumeroCuenta, SolicitudTransferencia (huella SHA-256),
                      Transferencia, PoliticaIdempotencia, CadenaAuditoria. Sin Spring.
  aplicacion/         puerto/entrada (casos de uso), puerto/salida (repositorios, Core, cache,
                      publicador, UnidadDeTrabajo) y servicio/ (implementaciones). Sin Spring.
  adaptadores/
    entrada/rest      Controllers + ManejadorErrores (RFC 9457 Problem Details)
    entrada/programado OutboxRelayProgramado (@Scheduled)
    salida/           persistencia (JdbcClient), cache (Valkey), core (RestClient+Resilience4j),
                      mensajeria (Valkey Streams)
  configuracion/      Composition root: único lugar que une aplicación y adaptadores
```

## Mapa decisión → código

| Decisión | Clases / archivos |
|---|---|
| Gateway/BFF | `gateway/nginx.conf`, `docker-compose.yml` (solo el gateway publica el puerto 8080) |
| Cache-Aside | `aplicacion/servicio/ConsultarMovimientosService`, `adaptadores/salida/cache/ValkeyCacheMovimientos` (claves con hash de la cuenta, TTL vigente 30 s y respaldo 24 h; si el cache falla, la lectura se trata como miss) |
| Circuit breaker + timeout | `adaptadores/salida/core/CoreBancarioHttp` (timeouts de 500 ms / 1,5 s), `application.yml` → `resilience4j.circuitbreaker.instances.core` (ventana de 10, mínimo 4 llamadas, 50 % de fallas o llamadas lentas, 15 s en OPEN) |
| Idempotencia | `dominio/idempotencia/PoliticaIdempotencia` (regla pura), `aplicacion/servicio/RegistrarTransferenciaService`, `adaptadores/salida/persistencia/JdbcIdempotenciaRepositorio` (`INSERT ... ON CONFLICT DO NOTHING` para manejar la carrera entre solicitudes concurrentes) |
| Transactional Outbox | `RegistrarTransferenciaService` (misma transacción), `JdbcOutboxRepositorio` (`FOR UPDATE SKIP LOCKED`), `PublicarOutboxService` + `OutboxRelayProgramado`, `ValkeyStreamPublicador` |
| Auditoría encadenada | `dominio/auditoria/CadenaAuditoria`, `aplicacion/servicio/RegistradorAuditoria` (lock con `pg_advisory_xact_lock` para serializar las escrituras), `VerificarAuditoriaService`, `AuditoriaController` |
| Minimización de datos (LOPDP) | Las cuentas van enmascaradas en las respuestas y en la auditoría (`NumeroCuenta.enmascarado()`), y las claves de cache usan el hash de la cuenta |

## Cómo ejecutar

Requisitos: Docker Desktop. Para compilar fuera de Docker se necesita JDK 21, porque el Maven wrapper descarga Maven 3.9.9.

```bash
cd poc
docker compose up --build -d      # gateway :8080, core-mock admin :8089
bash demo.sh                      # recorre los 10 escenarios de abajo
docker compose down -v            # limpia contenedores y volumen
```

Opcional: `cp env.example .env` para cambiar los puertos y las credenciales locales (`.env` no se versiona).

Pruebas:

```bash
cd poc/transferencias-poc
./mvnw verify          # 22 unitarias (surefire) + 3 de integración con Testcontainers (failsafe)
```

## Ejemplos curl

```bash
# Transferencia (repetir el MISMO comando devuelve 200 + Idempotent-Replayed: true)
curl -i -X POST http://localhost:8080/api/v1/transferencias/propias \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 6f1c2b7e-0001' -H 'X-Usuario: cliente-demo' \
  -d '{"cuentaOrigen":"1000000001","cuentaDestino":"2000000002","monto":125.50,"moneda":"USD","referencia":"Arriendo"}'

# Movimientos (mira los headers X-Origen-Datos y X-Respuesta-Degradada)
curl -i http://localhost:8080/api/v1/cuentas/1000000001/movimientos

# Simular la caída del Core (WireMock) y restaurarlo
curl -X POST http://localhost:8089/__admin/mappings -H 'Content-Type: application/json' \
  -d '{"priority":1,"request":{"method":"GET","urlPathPattern":"/core/cuentas/.*"},"response":{"fault":"CONNECTION_RESET_BY_PEER"}}'
curl -X POST http://localhost:8089/__admin/mappings/reset
# Latencia: usar "response":{"status":200,"fixedDelayMilliseconds":3000,...} -> cuenta como llamada lenta en el breaker
# La cuenta 9999999999 siempre falla (stub fijo en core-mock/mappings)

# Verificar la integridad de la auditoría
curl -i http://localhost:8080/api/v1/auditoria/verificacion
```

## Evidencia (ejecución real del 2026-10-19)

### `./mvnw -B verify` (JDK 21.0.12)

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ec.bp.reto.poc.aplicacion.ConsultarMovimientosServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ec.bp.reto.poc.aplicacion.RegistrarTransferenciaServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ec.bp.reto.poc.dominio.CadenaAuditoriaTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ec.bp.reto.poc.dominio.PoliticaIdempotenciaTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ec.bp.reto.poc.dominio.SolicitudTransferenciaTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 59.91 s -- in ec.bp.reto.poc.integracion.TransferenciasPocIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

La prueba de integración usa PostgreSQL 16 y Valkey 8 reales (Testcontainers) y el Core simulado con WireMock. Cubre 201/200/422 (otro cuerpo)/422 (otro recurso)/400, la publicación del outbox en el stream, CORE → CACHE → CACHE_RESPALDO → 503, la cadena íntegra y la detección de una alteración.

Códigos de idempotencia (alineados con ADR-009 y el borrador IETF *The Idempotency-Key HTTP Header Field*):

| Situación | HTTP | Dónde se evidencia |
|---|---|---|
| Primera solicitud con la clave | 201 | IT, `demo.sh` paso 1 |
| Reintento idéntico ya completado | 200 + `Idempotent-Replayed: true`, mismo `id` | IT, `demo.sh` paso 2 |
| Misma clave con otro cuerpo (huella distinta) | 422 | IT, `demo.sh` paso 3 |
| Misma clave en otro recurso | 422 | IT, `demo.sh` paso 4 |
| Misma clave con una solicitud aún en curso | 409 | Solo pruebas unitarias (`PoliticaIdempotenciaTest`, `RegistrarTransferenciaServiceTest`) |

El 409 se reserva para la carrera concurrente: la reserva atómica de la clave (`INSERT ... ON CONFLICT DO NOTHING`) la ganó otra solicitud cuyo registro todavía no es visible. En la PoC la clave, la transferencia, el outbox y la auditoría se confirman en una sola transacción y PostgreSQL hace esperar a la solicitud concurrente hasta el commit, por lo que la demo no puede provocar este caso de forma determinista; el diseño objetivo (ADR-009) lo expone con un `estado` explícito en la tabla de idempotencia.

### `docker compose up --build` + `demo.sh` (extracto)

```
=== 1. Transferencia propia -> 201 ===
HTTP 201
{"id":"b9aeac16-...","tipo":"PROPIA","estado":"REGISTRADA","cuentaOrigen":"******0001","cuentaDestino":"******0002","monto":125.50,...}

=== 2. Reintento con la MISMA clave y mismo cuerpo -> 200 ===
HTTP/1.1 200
Idempotent-Replayed: true
X-Request-Id: b0b8ca0d634ee11b86f5eb3e40dea331
{"id":"b9aeac16-...", ...}                       <- misma transferencia, no se duplicó

=== 3. Misma clave, cuerpo distinto -> 422 ===
{"title":"Idempotency-Key reutilizada","status":422,"detail":"La Idempotency-Key ya fue usada con un cuerpo de solicitud distinto; genere una clave nueva",...}

=== 4. Misma clave en otro recurso -> 422 ===
{"title":"Idempotency-Key reutilizada","status":422,"detail":"La Idempotency-Key ya fue usada en otro recurso: transferencias/propia; genere una clave nueva",...}

=== 5. Outbox -> relay -> Valkey Stream ===
 transferencia.registrada.v1 | b9aeac16-... | publicado = t        XLEN transferencias.eventos = 1

=== 6. Movimientos ===
X-Origen-Datos: CORE      (1.a consulta, cache miss)
X-Origen-Datos: CACHE     (2.a consulta, cache hit)

=== 7. Core caído + cache vigente expirado ===
HTTP/1.1 200
X-Origen-Datos: CACHE_RESPALDO
X-Respuesta-Degradada: true

=== 8. Cuenta sin respaldo con el Core caído ===
intento 1..5 -> HTTP 503
"failureRate":"75.0%"  "state":"OPEN"            <- circuit breaker abierto

=== 9. Verificación de la auditoría ===
{"integra":true,"registrosVerificados":5,"primerRegistroAlterado":null,"motivo":null}   HTTP 200
  1 | TRANSFERENCIA_REGISTRADA            | 000000000000 | 31da4de67965
  2 | TRANSFERENCIA_REINTENTO_IDEMPOTENTE | 31da4de67965 | 211e7b094e83
  3 | CONSULTA_MOVIMIENTOS                | 211e7b094e83 | fe3515a233bd

=== 10. UPDATE directo en BD del registro 1 ===
{"integra":false,"registrosVerificados":0,"primerRegistroAlterado":1,"motivo":"contenido alterado: el hash recalculado no coincide"}   HTTP 409
```

Log de acceso del gateway (JSON, con correlación):

```
{"ts":"2026-10-19T19:54:34+00:00","req_id":"b0b8ca0d634ee11b86f5eb3e40dea331","metodo":"POST","uri":"/api/v1/transferencias/propias","estado":200,"ms":0.066}
```

## Recortes de alcance y limitaciones (lo que esta PoC NO cubre)

- **Seguridad de identidad:** no hay OAuth 2.0/OIDC, PKCE, DPoP/mTLS ni validación de JWT. `X-Usuario` simula el `sub` del token que en el diseño objetivo valida el API Gateway. Los endpoints no tienen autorización: el de verificación de auditoría sería interno (rol auditor).
- **Gateway:** nginx sustituye a Amazon API Gateway + WAF + BFF. No hay agregación de BFF por canal (web/móvil).
- **Transferencia:** queda en estado `REGISTRADA`. La ejecución contra el Core / red interbancaria (saga con compensación), los saldos, los límites por cliente y la detección de fraude se activarían a partir del evento del outbox, pero están fuera de alcance. El reintento idempotente devuelve el estado **actual** de la transferencia, no una copia literal de la respuesta original.
- **Mensajería:** Valkey Streams sustituye a EventBridge/MSK. No hay consumidor de ejemplo. La entrega es *at-least-once* (el consumidor debe deduplicar por `eventoId`).
- **Auditoría:** la cadena es única y global (un lock serializa las escrituras), suficiente para la PoC. En producción se particiona (por día o por servicio), se exporta a almacenamiento WORM (S3 Object Lock), se ancla el último hash periódicamente y se usa un rol de BD con solo `INSERT/SELECT`. Las consultas que terminan en 503 no se auditan en la PoC.
- **Datos:** sin cifrado a nivel de columna ni tokenización, y sin purga de claves de idempotencia (el ADR-009 fija una retención de 7 días).
- **Operación:** sin multi-AZ ni multi-región, sin métricas exportadas a CloudWatch/OTel (solo `/actuator`) y sin pruebas de carga.
