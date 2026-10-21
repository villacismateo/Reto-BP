# ADR-001 — Estilo arquitectónico y comunicación entre servicios

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RNF-10, RF-17..RF-21, RF-08, RF-15, RNF-13, RNF-15, S-26 | 1, 3, 4, 5 | Contenedores (todos los servicios, bus de eventos), Componentes (publicador outbox, consumidores) |

## 1. Contexto

El enunciado pide una arquitectura "desacoplada con elementos reutilizables y cohesionados para otros componentes que puedan adicionarse en el futuro" (RNF-10), con 3 servicios iniciales (datos básicos, movimientos, transferencias) y libertad para agregar más (RF-21). Además hay trabajo que **no requiere respuesta inmediata** al cliente: notificar movimientos por ≥2 canales (RF-08), registrar auditoría de todas las acciones (RF-15), actualizar cachés y vistas de lectura.

Fuerzas y restricciones:
- Ritmos de cambio y criticidad muy distintos: transferencias (Tier 1, consistencia fuerte, 50 TPS de diseño, S-07) frente a consultas (1.000 req/s de diseño, S-06) y notificaciones (diferibles).
- Equipos por dominio (S-26): cuentas/consultas, transferencias, identidad/onboarding, canales, plataforma.
- El Core es la fuente de verdad y no se modifica (R-03); su capacidad es finita (~300 TPS de consultas, S-18).
- Un evento de negocio (p. ej. "transferencia completada") debe llegar **siempre** a notificaciones y auditoría, sin dobles débitos ni eventos "fantasma" si la transacción se revierte (RNF-13).

Preguntas: (a) ¿qué estilo de backend? (b) ¿qué infraestructura de mensajería para eventos de dominio? (c) ¿cómo publicar eventos de forma confiable junto con la escritura en BD?

## 2. Opciones evaluadas

### 2.1 Estilo de backend

Escala 1–5 (5 = mejor). Peso en %.

| Criterio (peso) | A. Monolito modular | B. Microservicios por *bounded context* | C. SOA con ESB centralizado |
|---|---|---|---|
| Desacoplamiento / evolución independiente (25) | 3 | 5 | 2 |
| Escalado diferenciado consultas vs. transferencias (20) | 2 | 5 | 3 |
| Aislamiento de fallas (*blast radius*) (20) | 2 | 4 | 2 |
| Complejidad operativa (15) | 5 | 2 | 2 |
| Alineación con equipos por dominio (Conway) (10) | 3 | 5 | 2 |
| Costo de infraestructura (10) | 5 | 3 | 2 |
| **Puntaje ponderado** | **3,10** | **4,15** | **2,20** |

- **A. Monolito modular** — Pros: una sola unidad de despliegue, transacciones locales, menor costo y latencia interna. Contras: escala todo junto (las consultas de pico arrastran a transferencias), un *memory leak* en un módulo tumba el canal completo, despliegues acoplados entre equipos.
- **B. Microservicios por bounded context** — Pros: escalado y despliegue independiente, aislamiento de fallas, BD por servicio, encaja con equipos por dominio. Contras: consistencia eventual entre contextos, más piezas que operar y observar, necesidad de contratos versionados.
- **C. SOA con ESB** — Pros: conocido en banca, mediación centralizada. Contras: el ESB concentra lógica y se vuelve cuello de botella y punto único de cambio ("smart pipes"), contrario a RNF-10.

### 2.2 Mensajería para eventos de dominio

| Criterio (peso) | EventBridge (bus) + SQS por consumidor | SNS + SQS (fan-out) | Amazon MSK (Kafka) |
|---|---|---|---|
| Enrutamiento por contenido y desacoplamiento productor/consumidor (25) | 5 | 3 | 3 |
| Esfuerzo operativo (25) | 5 | 5 | 2 |
| Costo al volumen de eventos de negocio (~3–5 M/mes) (20) | 5 | 5 | 2 |
| Re-procesamiento / *replay* (15) | 4 (archivo + replay) | 2 | 5 |
| Orden estricto y throughput masivo (15) | 2 | 3 (FIFO) | 5 |
| **Puntaje ponderado** | **4,40** | **3,75** | **3,15** |

- **EventBridge + SQS**: reglas por patrón de evento, *schema registry*, archivo y *replay*, integración nativa con Step Functions y Lambda; cada consumidor tiene su cola SQS con DLQ (*Queue-Based Load Leveling*). Contra: sin orden global, cuota de *throughput* por región (holgada para ~50 TPS monetarias).
- **SNS + SQS**: simple y barato, pero el filtrado es por atributos, sin *replay* ni catálogo de esquemas.
- **MSK**: excelente para *streaming* de alto volumen y orden por partición, pero exige operar *brokers*, *topics*, particiones y conectores; sobredimensionado para eventos de negocio a esta escala.

### 2.3 Publicación confiable de eventos

| Opción | Pros | Contras |
|---|---|---|
| Publicar al bus después del `COMMIT` | Trivial | *Dual write*: si el proceso cae entre `COMMIT` y publicación se pierde el evento (notificación y auditoría faltantes). |
| **Transactional Outbox + relay por *polling*** | Atómico con la transacción de negocio; sin infraestructura extra; demostrable en la PoC | Latencia de *polling* (≈ 200–500 ms); el relay debe ser idempotente y ordenado por agregado. |
| Transactional Outbox + CDC (Debezium/DMS) | Latencia baja, sin *polling* | Requiere conector CDC y su operación; más piezas. |

## 3. Decisión

1. **Microservicios por *bounded context*** (opción B) con este catálogo inicial (nombres canónicos para todos los diagramas):

| Servicio | Bounded context | Responsabilidad | Req. |
|---|---|---|---|
| `bff-web`, `bff-movil` | Canales | Agregación y adaptación por canal; *token handler* web (ADR-003, ADR-007) | RF-17, RNF-17 |
| `svc-clientes` | Clientes | Datos básicos (Core) + detalle bajo demanda (complementario) | RF-05, RF-06, RF-18 |
| `svc-cuentas` | Cuentas y movimientos | Productos, saldos, histórico (modelo de lectura CQRS) | RF-01, RF-07, RF-19 |
| `svc-transferencias` | Pagos y transferencias | Comando de transferencia/pago, idempotencia, saga, beneficiarios frecuentes | RF-02..04, RF-20 |
| `svc-notificaciones` | Notificaciones | Envío multicanal con proveedor alterno | RF-08 |
| `svc-auditoria` | Auditoría | Ingesta, sellado e indexación del registro inmutable | RF-15 |
| `svc-onboarding` | Identidad/Onboarding | Expediente de alta digital: orquesta por puertos/adaptadores al proveedor IDV (liveness + 1:1), Registro Civil, listas KYC/AML y API de administración del IdP (ADR-008) | RF-12, RF-14 |
| `svc-dispositivos` (decisión de §10 del documento 03) | Identidad/Dispositivos | Registro y política de instancias móviles (máx. 3 por cliente, enfriamiento 24 h, revocación por autoservicio, centro de contacto o 180 días de inactividad; `02` §5–6); DynamoDB `dispositivos` (Global Table); escribe la **lista de denegación** (`client_id`/`jkt` revocados, TTL = vida del *access token*) en ElastiCache de seguridad; revoca la instancia en el IdP (RFC 7592/7009). `bff-movil` solo valida atestación y orquesta el DCR, pidiéndole la autorización de alta | RF-11, RF-13, RNF-05 |
| `acl-core`, `acl-complementario`, `acl-interbancario` | Integración (ACL) | Traducción de modelos y protocolos hacia sistemas externos (ADR-004) | RF-20, RNF-03 |

2. Comunicación: **síncrona REST/JSON (HTTP/2 + TLS 1.3 de Service Connect + identidad por token; mTLS en los ACL hacia on-premise, ver `02-seguridad-y-normativa.md` §2.5)** solo cuando el cliente espera la respuesta (consultas, aceptación de transferencia); **asíncrona por eventos de dominio** en **Amazon EventBridge** (bus `bp-dominio`) con **una cola SQS + DLQ por consumidor**. Los flujos de alto volumen y orden por clave (CDC de movimientos, auditoría) usan **Kinesis Data Streams** (ADR-004, ADR-012), no el bus de dominio.
3. Publicación confiable: **Transactional Outbox** en la BD de cada servicio productor, con un **relay por *polling*** (`SELECT … FOR UPDATE SKIP LOCKED`, orden por agregado). CDC queda como evolución si la latencia del relay se vuelve relevante.
4. Contratos: eventos en formato **CloudEvents 1.0** con `id` único (clave de deduplicación), `type` versionado (`bp.transferencias.completada.v1`) y esquema en el *schema registry*; APIs REST versionadas en la ruta (`/v1/...`) y descritas en OpenAPI 3.1. Consumidores **idempotentes** (tabla de eventos procesados).

## 4. Justificación teórica

1. **Bounded contexts y Ley de Conway** — Evans, *Domain-Driven Design* (2003): cada contexto delimitado mantiene un modelo coherente y se integra con otros por traducción explícita; Conway (1968) y Skelton & Pais, *Team Topologies* (2019): alinear límites de servicio con equipos (S-26) reduce coordinación y acelera el cambio independiente.
2. **Microservicios y "smart endpoints, dumb pipes"** — Newman, *Building Microservices* 2.ª ed. (2021) y Lewis & Fowler, "Microservices" (2014): despliegue independiente, aislamiento de fallas y escalado por servicio; se rechaza el ESB porque concentra lógica en la tubería.
3. **Transactional Outbox / Idempotent Consumer** — Richardson, *Microservices Patterns* (2018), cap. 3: evita el *dual write* garantizando que el evento se publique si y solo si la transacción local confirma; combinado con consumidores idempotentes logra efecto *exactly-once* sobre entrega *at-least-once*.
4. **Desacoplamiento temporal y nivelación de carga** — Hohpe & Woolf, *Enterprise Integration Patterns* (2003): *Publish-Subscribe Channel*, *Dead Letter Channel*, *Message Router*; AWS Well-Architected, pilar de Fiabilidad (REL04: "diseñar interacciones en sistemas distribuidos para prevenir fallas" — preferir dependencias asíncronas y débilmente acopladas).
5. **PACELC** (Abadi, 2012): para notificaciones y auditoría se prioriza disponibilidad/latencia (consistencia eventual); para el débito se prioriza consistencia (transacción local + saga, ADR-009).

## 5. Consecuencias

**Positivas:** escalado independiente de consultas (alto volumen) y transferencias (crítico); agregar un canal o consumidor nuevo (p. ej. antifraude, analítica) es suscribir una regla sin tocar productores (RNF-10); fallas de notificaciones no afectan la transferencia.

**Negativas / trade-offs:** consistencia eventual entre contextos (p. ej. la notificación llega segundos después); mayor superficie de observabilidad (mitigado con trazas distribuidas, ADR-014); costo base mayor que un monolito.

| Riesgo | Mitigación |
|---|---|
| Eventos perdidos o duplicados | Outbox + consumidor idempotente + DLQ con alarma y *redrive* |
| Proliferación de microservicios ("nano-servicios") | Un servicio por *bounded context*, no por entidad; revisión de nuevos servicios por ADR |
| Cambios de contrato rompen consumidores | Versionado semántico de eventos, *consumer-driven contract tests* (Pact) en CI |
| Cuota de EventBridge | ~50 TPS monetarias × ~3 eventos ≪ cuota regional; monitoreo de `ThrottledRules` |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Microservices / Database per Service | Todos los `svc-*` (ADR-011) |
| Domain Event, Publish-Subscribe | Bus `bp-dominio` (EventBridge) |
| Transactional Outbox + Polling Publisher | `svc-transferencias`, `svc-onboarding` (en `svc-cuentas`, equivalente nativo con DynamoDB Streams + EventBridge Pipes, ADR-011) |
| Idempotent Consumer, Dead Letter Channel | Consumidores SQS (`svc-notificaciones`, `svc-auditoria`) |
| Queue-Based Load Leveling | SQS delante de notificaciones y auditoría |
| Anti-Corruption Layer | `acl-*` (ADR-004) |

## 7. Equivalente Azure

Microservicios en Azure Container Apps; Azure Event Grid (o Service Bus Topics) + colas de Service Bus con *dead-letter*; Event Hubs para *streaming*.

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RNF-10, RF-17..21 | Contenedores (catálogo de servicios y bus) | §3 Visión y principios · §5 C4 Contenedores |
| RF-08, RF-15 | Componentes de `svc-transferencias` (outbox/relay) | §6 C4 Componentes · §10 Integración |
| RNF-13 | Secuencia de transferencia | §7 Flujos clave |
