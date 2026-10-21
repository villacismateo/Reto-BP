# ADR-009 — Orquestación de transferencias y pagos (saga, idempotencia y compensación)

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-02, RF-03, RF-04, RF-08, RF-15, RF-20, RNF-03, RNF-13, S-07, S-13, S-18, S-22, A-1 | 1, 4, 5, 7, 14 | Contenedores (`svc-transferencias`, AWS Step Functions, SQS de tareas, `acl-core`, `acl-interbancario`), Componentes de `svc-transferencias`, secuencia de transferencia interbancaria |

## 1. Contexto

Transferencias y pagos "entre cuentas propias e interbancarias" (RF-02..04) que "realizan llamados a servicios externos dependiendo del tipo" (RF-20). Por A-1 (resolución adoptada), "pagos" = pago a productos propios (tarjeta, préstamo) y a terceros en BP u otras entidades; pagos de servicios básicos fuera de alcance pero extensible.

Fuerzas:
- **Integridad monetaria** (RNF-13): sin dobles débitos ni saldos inconsistentes; reintentos del cliente o de la red no deben duplicar.
- **Transacción distribuida sin 2PC**: el débito ocurre en el Core (on-prem) y el abono en otra entidad vía red interbancaria; no hay transacción ACID común.
- La confirmación interbancaria es **asíncrona** y puede tardar de segundos a horas según la modalidad (S-22, a verificar); API Gateway corta a 29 s.
- Objetivos: transferencia propia ≤ 2 s p95; aceptación de interbancaria ≤ 2 s con confirmación final asíncrona (S-13). Diseño 50 TPS (S-07), ~40 % interbancarias.
- Seguridad (ADR-007): la solicitud lleva `Idempotency-Key`; el token trae `authorization_details` (**RFC 9396, RAR**) con monto, moneda y destino aprobados por el cliente; `acr` mínimo por tipo de operación (*step-up* para terceros e interbancarias).

## 2. Opciones evaluadas

| Criterio (peso) | A. Saga coreografiada (eventos entre servicios) | B. Saga orquestada con AWS Step Functions (Standard) | C. Orquestador embebido en el servicio (máquina de estados propia o Temporal) |
|---|---|---|---|
| Visibilidad y auditabilidad del flujo extremo a extremo (20) | 2 | 5 | 4 |
| Compensación y esperas largas con *timeouts* (20) | 2 | 5 | 4 |
| Garantías de ejecución e idempotencia (20) | 3 | 5 | 4 |
| Esfuerzo operativo (15) | 4 | 5 | 2 |
| Costo (10) | 5 | 3 | 4 |
| Portabilidad / acoplamiento al proveedor (15) | 4 | 2 | 5 |
| **Puntaje ponderado** | **3,10** | **4,35** | **3,85** |

- **A. Coreografía**: cada servicio reacciona a eventos; sin coordinador. Bueno para pocos pasos, pero el flujo queda implícito y repartido, las compensaciones y *timeouts* son difíciles de razonar y auditar, y aparecen dependencias cíclicas de eventos.
- **B. Step Functions Standard**: flujo explícito y versionado, historial de ejecución de hasta 1 año, ejecución *exactly-once* por flujo, **nombre de ejecución único** (arranque idempotente), esperas por *callback* (`.waitForTaskToken`) con *timeout* y *heartbeat*, reintentos y `Catch` declarativos. Contra: costo por transición y acoplamiento a AWS (definición en ASL).
- **C. Orquestador embebido**: portable y barato; Temporal es muy potente, pero autogestionarlo es otro sistema crítico (o un SaaS más a homologar); una máquina de estados propia exige construir *timers*, reintentos y *tooling* de operación.

Variante descartada: **Step Functions Express** (más barata, pero *at-least-once* y máximo 5 min: no cubre esperas interbancarias largas).

## 3. Decisión

**Saga orquestada con AWS Step Functions (Standard) solo donde hay transacción distribuida; camino síncrono simple donde el Core resuelve atómicamente.**

### 3.1 Recepción del comando (todos los tipos) — `svc-transferencias`

1. `POST /v1/transferencias` con `Idempotency-Key` (UUID generado por el cliente). Validaciones: `acr` mínimo por tipo (propias: sesión vigente; terceros BP / interbancarias / pagos a terceros: `aal2-tx` con *step-up*) y **coincidencia exacta del cuerpo con `authorization_details`** del token (monto, moneda, cuenta destino); límites diarios y beneficiario.
2. **Idempotencia**: tabla `idempotencia(sub, clave, hash_solicitud, estado, respuesta, expira)` con clave única `(sub, clave)`. Misma clave + mismo *hash* ⇒ devuelve la respuesta guardada; misma clave + otro *hash* ⇒ `422`; en curso ⇒ `409` con estado. Retención 7 días.
3. En **una transacción local** (Aurora PostgreSQL, ADR-011): `INSERT` de la transferencia (`SOLICITADA`) + registro de idempotencia + evento `TransferenciaSolicitada` en el **outbox** (ADR-001).
4. Ruteo por tipo (**Strategy**): `PropiaStrategy`, `TerceroBpStrategy`, `PagoProductoStrategy`, `InterbancariaStrategy` (extensible: `PagoServicioStrategy` para A-1 (b)).

### 3.2 Propias, terceros en BP y pagos a productos BP (síncrono, ≤ 2 s)

`svc-transferencias` → `acl-core` "contabilizar" (débito y crédito en **un solo asiento del Core**, atómico allí) con **referencia única = id de transferencia**. Si hay *timeout* ⇒ estado `EN_VERIFICACION` y **consulta por referencia antes de reintentar** (nunca reintento ciego de un débito). Respuesta `201` con comprobante. Supuesto nuevo **S-28**: el Core acepta una referencia única por asiento (deduplica) o permite consultarlo por referencia.

### 3.3 Interbancarias y pagos a otras entidades (saga orquestada, `202 Accepted`)

El *relay* del outbox inicia la ejecución con `StartExecution(name = idTransferencia)` (si ya existe, Step Functions la rechaza ⇒ arranque idempotente). Los pasos usan el patrón **`.waitForTaskToken` sobre SQS**: Step Functions encola la tarea y los *workers* de `svc-transferencias` llaman al ACL y devuelven el resultado (la lógica de dominio permanece en el servicio; Step Functions solo coordina estado).

| Paso | Acción | Fallo ⇒ |
|---|---|---|
| 1. Débito / retención en Core | `acl-core` con referencia `deb-{id}` | Rechazo de fondos ⇒ `RECHAZADA` (sin compensación) |
| 2. Envío a la red interbancaria | `acl-interbancario` con referencia `{id}` por el **riel** elegido (ver 3.4); espera *callback* de confirmación con `TimeoutSeconds` **parametrizado por riel** y *heartbeat* | Rechazo o *timeout* ⇒ consulta activa de estado y, si no hay acreditación, paso C |
| 3. Confirmación | Acreditada ⇒ consolidar débito, estado `COMPLETADA`, evento `TransferenciaCompletada` | — |
| C. **Compensación** | Reverso en Core con referencia `rev-{id}` (idempotente), estado `REVERSADA`, evento `TransferenciaReversada` | Reintentos con *backoff*; agotados ⇒ cola de **intervención manual** con alarma P1 (nunca se descarta) |

Estados visibles al cliente: `SOLICITADA → EN_PROCESO → COMPLETADA | RECHAZADA | REVERSADA`, consultables en `GET /v1/transferencias/{id}` y notificados (ADR-010). Cada transición publica evento (outbox) ⇒ notificaciones, auditoría (ADR-012) e invalidación de caché de saldos (ADR-011).

### 3.4 Rieles del BCE en `acl-interbancario` (actualización posterior, fuentes en `02-seguridad-y-normativa.md` §9.3)

El Sistema Central de Pagos del BCE ofrece dos rieles relevantes; `acl-interbancario` los implementa como **Strategy** (`RielRpiStrategy`, `RielSpiStrategy`) detrás del mismo contrato canónico interno, y la **traducción a ISO 20022** ocurre solo dentro del ACL (el dominio de `svc-transferencias` no conoce mensajes `pacs`):

| Riel | Uso | Mensajería | Confirmación | `TimeoutSeconds` de la saga (parámetro) | Selección |
|---|---|---|---|---|---|
| **RPI — Red de Pagos Instantáneos** | Tiempo real, montos **≤ USD 15.000** (Res. BCE-GG-008-2025 [V]) | ISO 20022 **`pacs.008`** (orden) / **`pacs.002`** (estado) (Res. BCE-GG-007-2026 [V]; implementación intra-redes hasta 2026-11-30) | Segundos; tiempo máximo lo fija el BCE | Corto (p. ej. 30–60 s) y luego consulta activa de estado | Por defecto si el monto ≤ tope y la entidad destino está adherida |
| **SPI — Sistema de Pagos Interbancarios** | Montos mayores, entidades no adheridas a RPI o contingencia de RPI | Formato vigente del SPI (a confirmar con BP; ciclos/lotes intradía [NV]) | Diferida (horas, por ciclo) | Largo (hasta el cierre del ciclo + margen), con *heartbeat* | Monto > tope, destino no adherido o *circuit breaker* abierto en RPI (degradación) |

Reglas: el riel elegido se persiste en la transferencia y viaja en el evento (`riel = RPI|SPI`), de modo que la compensación, la conciliación diaria y el mensaje al cliente ("acreditada en segundos" vs. "se acreditará en el próximo ciclo") dependen del riel; la conmutación RPI → SPI solo ocurre **antes** de enviar (nunca se reenvía por otro riel una orden ya enviada sin un `pacs.002` de rechazo, para evitar doble abono).

Punto de extensión: paso de **evaluación de fraude** antes del débito (motor de riesgo), no incluido en el alcance.

Costo orientativo (estimación de costos): ~32.000 interbancarias/día × ~12 transiciones ≈ 11,5 M transiciones/mes × USD 0,025 por 1.000 ⇒ **≈ USD 300/mes**.

**Demostración en la PoC (A-8)**: `POST /transferencias` con `Idempotency-Key` (N reintentos ⇒ un solo movimiento) + outbox + *relay* hacia el evento consumido por auditoría; la saga interbancaria se documenta (diagrama de secuencia) y no se implementa.

## 4. Justificación teórica

1. **Saga** — Garcia-Molina & Salem, "Sagas" (ACM SIGMOD, 1987) y Richardson, *Microservices Patterns* (2018), cap. 4: una transacción de larga duración se divide en transacciones locales con acciones compensatorias; la **orquestación** se recomienda cuando hay varios participantes, compensaciones y necesidad de visibilidad del flujo — el caso de un pago interbancario.
2. **Idempotency Key** — Stripe, "Designing robust and predictable APIs with idempotency" (Leach, 2017) e IETF *draft* "The Idempotency-Key HTTP Header Field" (httpapi WG): reintentos seguros sobre redes no confiables; combinado con *Idempotent Receiver* (Hohpe & Woolf, EIP) en cada participante (referencias `deb-`, `rev-`).
3. **Imposibilidad del consenso con fallas / "Two Generals"** y crítica a 2PC en sistemas distribuidos — Kleppmann, *Designing Data-Intensive Applications* (2017), cap. 9: sin coordinador transaccional común entre Core y red interbancaria, la consistencia se logra con compensación y conciliación, no con bloqueo distribuido.
4. **AWS Well-Architected, pilar de Fiabilidad (REL05: diseñar interacciones para mitigar o resistir fallas; hacer las operaciones idempotentes)** y Nygard, *Release It!* (2018): *timeouts* explícitos y no reintentar operaciones no idempotentes sin verificación.

## 5. Consecuencias

**Positivas:** imposible duplicar débitos por reintentos; flujo interbancario visible y auditable paso a paso; transferencias internas rápidas sin pagar orquestación; nuevos tipos de pago se agregan como nueva *Strategy* y, si son distribuidos, como nueva máquina de estados.

**Negativas / trade-offs:** consistencia eventual en interbancarias (el cliente ve `EN_PROCESO`); acoplamiento a Step Functions (aislado: la lógica está en los *workers*, la definición ASL es corta y sustituible); costo por transición.

| Riesgo | Mitigación |
|---|---|
| Core sin deduplicación por referencia (S-28) | `acl-core` mantiene un registro de asientos enviados y consulta-antes-de-reintentar |
| Compensación que también falla | Reintentos con *backoff*, cola de intervención manual, conciliación diaria Core ↔ red ↔ `svc-transferencias` |
| *Callback* de la red que nunca llega | `TimeoutSeconds` por riel + consulta activa de estado a la red antes de compensar (evita reversar una transferencia acreditada) |
| Manipulación del monto entre aprobación y ejecución | Verificación de `authorization_details` contra el cuerpo; firma de transacción del dispositivo (ADR-007) |
| Pico de quincena | Cola SQS de tareas nivela la carga hacia el Core; *rate limiter* del ACL (ADR-004) |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Saga orquestada, Compensating Transaction | Step Functions (interbancarias/pagos externos) |
| Idempotency Key / Idempotent Receiver | API de `svc-transferencias`; referencias en `acl-core` y `acl-interbancario` |
| Transactional Outbox | `svc-transferencias` → EventBridge |
| Strategy | Ruteo por tipo de transferencia/pago; riel RPI/SPI en `acl-interbancario` |
| Asynchronous Request-Reply (202 + estado) | Interbancarias |
| Queue-Based Load Leveling, Competing Consumers | SQS de tareas con `.waitForTaskToken` |

## 7. Equivalente Azure

Azure Durable Functions (o Logic Apps Standard) como orquestador, Service Bus para tareas y Azure Database for PostgreSQL para estado + outbox.

## 8. Trazabilidad

| Requisito | Diagrama C4 / otro | Sección del PDF |
|---|---|---|
| RF-02..04, RF-20 | Componentes `svc-transferencias` (Strategy, saga, idempotencia, outbox) | §6 · §10 Integración |
| RNF-13 | Secuencia de transferencia interbancaria (camino feliz y compensación) | §7 Flujos clave |
| RF-08, RF-15 | Contenedores (eventos hacia notificaciones y auditoría) | §5 · §9.x Auditoría |
| RNF-03 | Componentes (cliente resiliente hacia `acl-*`) | §12 Resiliencia |
