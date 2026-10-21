# ADR-011 — Persistencia por servicio y mecanismo para clientes frecuentes

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-01, RF-05..RF-07, RF-16, RNF-02, RNF-10, RNF-12, RNF-15, RNF-16, S-03, S-05, S-08, S-13, S-18, A-2 | 1, 3, 4, 7, 14 | Contenedores (Aurora PostgreSQL, DynamoDB, ElastiCache), Componentes de `svc-clientes` y `svc-cuentas` (repositorios, caché, detector de frecuencia, invalidador), secuencia "consulta con caché" |

## 1. Contexto

Dos preguntas relacionadas:

1. **Database per Service** (RNF-10): qué motor usa cada servicio según su patrón de acceso y criticidad.
2. **Clientes frecuentes** (RF-16): "cuenta con un mecanismo de persistencia de información para clientes frecuentes, para este caso proponga una alternativa basada en patrones de diseño que relacione los componentes que deben interactuar". Por **A-2 (resolución adoptada)**: persistir/cachear **perfil, productos y últimos movimientos de los clientes que ingresan con frecuencia**, para responder rápido y proteger el Core; **beneficiarios frecuentes** como extensión.

Fuerzas: Core con ~300 TPS de consultas (S-18) frente a 1.000 req/s de diseño (S-06); 250.000 usuarios activos diarios (S-03) con una fracción recurrente que concentra la mayoría de sesiones; objetivo p95 de posición ≤ 500 ms con caché (S-13); el Core sigue siendo la fuente de verdad y el saldo debe ser fresco.

## 2. Opciones evaluadas

### 2.1 Motores por servicio

| Opción | Pros | Contras | Encaja en |
|---|---|---|---|
| **Aurora PostgreSQL (Multi-AZ, Global Database)** | ACID, transacción local con outbox e idempotencia, *failover* en la región < 1 min aprox., replicación entre regiones con RPO de segundos | Costo de instancias; escalado de escritura vertical | Estado transaccional (`svc-transferencias`, `svc-onboarding`) |
| RDS PostgreSQL Multi-AZ | Más barato | *Failover* más lento, réplica entre regiones menos integrada | Entornos no productivos |
| **DynamoDB (on-demand, Global Tables)** | Escala horizontal sin gestión, latencia de milisegundos para acceso por clave, TTL nativo, *Streams* | Modelado por patrón de acceso; consultas *ad hoc* limitadas | Modelo de lectura de movimientos, notificaciones, preferencias |
| **ElastiCache (motor Valkey, compatible con Redis OSS)** | Submilisegundos, estructuras de datos, Multi-AZ con *failover* automático; Valkey con precio menor que Redis OSS | Datos volátiles (aceptable si son derivables) | Caché de clientes frecuentes, sesiones de `bff-web` |

### 2.2 Patrón para clientes frecuentes

| Criterio (peso) | B1. Cache-Aside con TTL | B2. Read-Through / Write-Through | B3. Vista materializada persistente por cliente (actualizada por eventos) | B4. **Cache-Aside + Refresh-Ahead selectivo para frecuentes + invalidación por eventos** |
|---|---|---|---|---|
| Protección del Core (25) | 3 | 3 | 5 | 5 |
| Latencia p95 (20) | 4 | 4 | 5 | 5 |
| Frescura / consistencia (20) | 3 | 3 | 4 | 4 |
| Complejidad (15) | 5 | 3 | 2 | 3 |
| Costo (10) | 5 | 4 | 3 | 4 |
| Comportamiento ante fallo de la caché (10) | 4 | 3 | 5 | 4 |
| **Puntaje ponderado** | **3,80** | **3,30** | **4,15** | **4,30** |

- **B1**: simple, pero el primer acceso de cada sesión siempre va al Core (*cold miss*) y la frescura depende solo del TTL.
- **B2**: acopla la caché a la fuente (el proveedor de caché debe saber cargar desde el Core vía ACL); escritura directa no aplica porque el Core es la fuente de verdad.
- **B3**: excelente latencia y protección, pero mantener una vista por **cada** cliente (2 M) actualizada por eventos es costoso cuando la mayoría ingresa poco.
- **B4**: aplica el costo de precarga solo a quienes lo amortizan (los frecuentes), mantiene frescura por eventos y conserva la simplicidad de Cache-Aside para el resto.

## 3. Decisión

### 3.1 Database per Service

| Servicio | Almacén | Contenido | Notas |
|---|---|---|---|
| `svc-transferencias` | **Aurora PostgreSQL** (clúster dedicado Tier 1, Multi-AZ, Global Database a `us-east-2`) | Transferencias, idempotencia, outbox, límites, **beneficiarios frecuentes** | Credenciales IAM/Secrets Manager propias; ningún otro servicio accede |
| `svc-onboarding` | **Aurora PostgreSQL** (clúster de plataforma, base lógica propia) | Expediente, estados de la saga de onboarding, outbox; evidencias KYC en S3 + KMS dedicada (*claim check*, ADR-008) | Esquema y usuario exclusivos |
| `svc-cuentas` | **DynamoDB** `movimientos` (Global Table) | Modelo de lectura CQRS (ADR-004); TTL 13 meses | Eventos `MovimientoRegistrado` vía **DynamoDB Streams + EventBridge Pipes** (outbox nativo) |
| `svc-clientes` | **DynamoDB** `perfil-digital` + caché | Alias, contacto digital, marca de frecuencia; datos básicos **no** se copian (fuente: Core) | — |
| `svc-notificaciones` | **DynamoDB** `notificaciones`, `preferencias` | Estado de entregas (TTL 13 meses), canales elegidos | ADR-010 |
| `svc-auditoria` | **Kinesis + S3 Object Lock + Athena** | Registro inmutable | ADR-012 |
| `bff-web` | **ElastiCache** (clúster de sesiones, separado del de datos) | Sesión y tokens cifrados | ADR-007 |
| Clientes frecuentes | **ElastiCache for Valkey** (clúster de datos, *cluster mode*, Multi-AZ) | Ver 3.2 | — |

Regla: *Database per Service* lógico siempre; consolidación física solo en el clúster de plataforma (no Tier 1) y con esquema + credenciales por servicio (variante *schema-per-service*).

### 3.2 Mecanismo de clientes frecuentes (B4)

Componentes que interactúan (diagrama de componentes DC-03c, `04-modelo-c4.md` §3.3). **Ubicación (fijada 2026-10-19):** `DetectorFrecuencia` vive en **`svc-clientes`** (dueño de `perfil-digital`); `PrecargadorPerfil`, `RepositorioCacheado` e `InvalidadorPorEventos` en `svc-cuentas` (con su equivalente en `svc-clientes` para perfil y productos); `ClienteResiliente` en ambos.

| Componente | Responsabilidad |
|---|---|
| `DetectorFrecuencia` | Consume `auth.login.succeeded`; cuenta sesiones por cliente en ventana móvil de 30 días (estructura en Valkey); marca **frecuente** si ≥ 8 sesiones/30 días (parámetro de negocio) y persiste la marca en `perfil-digital` |
| `PrecargadorPerfil` (*Refresh-Ahead*) | Al iniciar sesión un cliente frecuente, precarga en paralelo **perfil básico, productos y primera página de movimientos** antes de la primera pantalla |
| `RepositorioCacheado` (*Cache-Aside*) | En lectura: busca en caché → si falla, lee de la fuente (`acl-core` o DynamoDB `movimientos`) y guarda con TTL; protección contra estampida (*single-flight* por clave + expiración temprana probabilística) |
| `InvalidadorPorEventos` | Consume `TransferenciaCompletada/Reversada`, `MovimientoRegistrado`, `ClienteActualizado` y borra las claves afectadas (*event-driven invalidation*) |
| `ClienteResiliente` | Si la caché no responde: *circuit breaker* abierto ⇒ lectura directa a la fuente con el *rate limiter* del ACL; la petición no falla por la caché |

Política de datos en caché:

| Clave (sub seudonimizado) | Contenido | TTL frecuente / no frecuente | Invalidación |
|---|---|---|---|
| `cli:{id}:perfil` | Datos básicos para la UI (sin documentos ni datos sensibles completos) | 24 h / 1 h | `ClienteActualizado` |
| `cli:{id}:productos` | Productos y metadatos | 1 h / 15 min | Cambios de producto |
| `cli:{id}:saldos` | Saldos disponibles | 30 s / 30 s | Toda transferencia o movimiento |
| `cta:{n}:mov:p1` | Primera página de movimientos | 5 min / 2 min | `MovimientoRegistrado` |
| `cli:{id}:benef` (extensión A-2 b) | Top-N **beneficiarios frecuentes** (persistidos en Aurora de `svc-transferencias`, ordenados por uso y recencia) | 24 h | Nueva transferencia a beneficiario |

Cifrado en tránsito (TLS) y en reposo (KMS) en ElastiCache; AUTH/RBAC por servicio; claves sin datos personales en claro.

Dimensionamiento orientativo (estimación de costos): ~300.000 clientes frecuentes × ~20 KB ≈ 6 GB + margen ⇒ 2 *shards* con réplica en otra AZ (nodos de memoria optimizada medianos) ⇒ orden de **USD 500–900/mes**. Tasa de aciertos objetivo ≥ 85 % en clientes frecuentes, con reducción esperada de lecturas al Core > 60 % (a validar en pruebas de carga).

**PoC (A-8)**: el servicio de movimientos de la PoC aplica Cache-Aside sobre Redis (compatible con Valkey), TTL e invalidación al registrar una transferencia; métricas de *hit/miss*.

## 4. Justificación teórica

1. **Database per Service** — Richardson, *Microservices Patterns* (2018), cap. 2 y microservices.io: cada servicio es dueño exclusivo de sus datos para evitar acoplamiento en tiempo de diseño y ejecución; Newman, *Building Microservices* (2021): la BD compartida es el acoplamiento más difícil de deshacer. **Persistencia políglota** (Fowler & Sadalage, *NoSQL Distilled*, 2012): elegir el motor por patrón de acceso.
2. **Cache-Aside y Refresh-Ahead** — Microsoft Azure Architecture Center, "Cache-Aside pattern"; documentación de AWS "Caching Best Practices" (*lazy loading* vs. *write-through*, TTL); Oracle Coherence "Refresh-Ahead": precargar antes de la expiración lo que tiene alta probabilidad de usarse reduce la latencia del primer acceso.
3. **Principio de localidad / Ley de Pareto en cargas de usuarios** y Kleppmann, *Designing Data-Intensive Applications* (2017), cap. 1 y 11: optimizar para la carga sesgada (pocos clientes generan la mayoría de lecturas) y mantener datos derivados mediante flujos de eventos (invalidación por evento en lugar de solo TTL).
4. **AWS Well-Architected, pilar de Eficiencia del Rendimiento (PERF03: usar caché para mejorar el rendimiento) y de Fiabilidad**: la caché es una optimización, no una dependencia dura — su falla degrada latencia, no disponibilidad (*fail-open* controlado).
5. **PACELC** (Abadi, 2012): en perfil y movimientos se acepta frescura acotada por TTL/eventos para ganar latencia; en saldos el TTL es corto y se invalida en cada movimiento; los débitos siempre se validan en el Core.

## 5. Consecuencias

**Positivas:** posición consolidada de clientes frecuentes servida desde memoria (p95 objetivo ≤ 500 ms); menor carga sobre el Core en picos; costo de precarga acotado a quien lo aprovecha; servicios con su propio almacén, desplegables y escalables por separado.

**Negativas / trade-offs:** datos de caché eventualmente consistentes (segundos); más tipos de almacén que operar (Aurora, DynamoDB, ElastiCache); el detector de frecuencia agrega un consumidor de eventos.

| Riesgo | Mitigación |
|---|---|
| Datos obsoletos mostrados (p. ej. saldo) | TTL corto + invalidación por evento; operación siempre valida en Core |
| Estampida al expirar claves populares | *Single-flight* y expiración temprana probabilística |
| Caída de ElastiCache | Multi-AZ con *failover*; *circuit breaker* a la fuente con *rate limiter* |
| PII en caché | Contenido mínimo, cifrado, claves seudonimizadas, TTL |
| Crecimiento de consultas *ad hoc* sobre DynamoDB | Diseño de índices por patrón; analítica fuera de línea en S3/Athena |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Database per Service, Polyglot Persistence | Tabla 3.1 |
| Cache-Aside, Refresh-Ahead, Event-Driven Cache Invalidation | `svc-clientes`, `svc-cuentas` |
| Materialized View / CQRS | DynamoDB `movimientos` (ADR-004) |
| Transactional Outbox (vía DynamoDB Streams + EventBridge Pipes) | `svc-cuentas` |
| Circuit Breaker, Fail-Open controlado | `ClienteResiliente` |
| Claim Check | Evidencias de onboarding en S3 |

## 7. Equivalente Azure

Azure Database for PostgreSQL Flexible Server (HA con zona redundante) o Azure SQL; Azure Cosmos DB; Azure Cache for Redis / Azure Managed Redis.

## 8. Trazabilidad

| Requisito | Diagrama C4 / otro | Sección del PDF |
|---|---|---|
| RF-16, A-2 | Componentes `svc-clientes`/`svc-cuentas` (detector, precargador, repositorio cacheado, invalidador) · secuencia "consulta con caché" | §6 · §7 · §9 Datos |
| RNF-10 | Contenedores (un almacén por servicio) | §5 · §9 |
| RNF-12 | Secuencia "consulta con caché" | §7 |
| RNF-02 | Infraestructura (Multi-AZ de Aurora, DynamoDB, ElastiCache) | §11 · §12 |
