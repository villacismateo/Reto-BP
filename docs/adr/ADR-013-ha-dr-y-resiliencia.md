# ADR-013 — Alta disponibilidad, recuperación ante desastres y resiliencia (auto-healing)

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RNF-02, RNF-03, RNF-04, RNF-08, RNF-13, RNF-15, S-12, S-14, S-15, S-16, S-18..S-20 | 1, 8, 9, 14 | Infraestructura (multi-AZ, región DR), vista HA/DR, Componentes (clientes resilientes en BFF, `svc-*` y `acl-*`) |

## 1. Contexto

El enunciado exige "alta disponibilidad (HA), tolerancia a fallos, recuperación ante desastres (DR)… auto-healing" (RNF-02/03/04/08). Objetivos declarados:

| Tier (S-15) | Servicios | SLO (S-14) | RTO | RPO |
|---|---|---|---|---|
| 1 | Autenticación (BFF/gateway; IdP de BP), `svc-transferencias`, `acl-core`, `acl-interbancario`, `svc-dispositivos`, auditoría | 99,95 % (login y transferencia propia: **99,9 %**, decisión de SLO de §10 del documento 03) | ≤ 30 min | ≤ 1 min (auditoría ≈ 0 en la región) |
| 2 | `svc-clientes`, `svc-cuentas`, `svc-notificaciones`, `svc-onboarding`, `acl-complementario` | 99,95 % consultas; 99,9 % onboarding/notificaciones | ≤ 1 h | ≤ 5 min |
| 3 | Reportería/analítica | — | ≤ 24 h | ≤ 24 h |

Hechos que condicionan: el **Core es un único sitio on-premise** (S-18) y el IdP lo provee BP (S-20) — su HA/DR es una dependencia externa; las dependencias (Core, complementario, red interbancaria, proveedores) fallan o se ponen lentas, y una dependencia lenta es peor que una caída (agota hilos y conexiones). Presupuesto S-16.

Preguntas: (a) ¿qué estrategia de DR por tier? (b) ¿cómo implementar *timeouts*, reintentos, *circuit breaker*, *bulkhead* y *rate limiting*: librería, *service mesh* o ambos?

## 2. Opciones evaluadas

### 2.1 Estrategia de DR (evaluada contra Tier 1)

| Criterio (peso) | Backup & restore | Pilot light | **Warm standby** | Activo-activo multi-región |
|---|---|---|---|---|
| Cumple RTO 30 min / RPO 1 min (35) | 1 | 3 | 5 | 5 |
| Costo (25) | 5 | 4 | 3 | 1 |
| Complejidad (20) | 4 | 3 | 3 | 1 |
| Confianza en el *failover* (ensayable, sin arranque en frío masivo) (20) | 2 | 3 | 4 | 4 |
| **Puntaje ponderado** | **2,80** | **3,25** | **3,90** | **3,00** |

- **Backup & restore**: RPO de horas; no cumple Tier 1.
- **Pilot light**: datos replicados pero cómputo apagado; levantar y calentar todo en un desastre real arriesga el RTO de 30 min.
- **Warm standby**: datos replicados continuamente y todos los servicios corriendo a capacidad mínima; el *failover* es escalar y conmutar tráfico.
- **Activo-activo**: RTO casi cero, pero duplica costo y obliga a resolver escrituras multi-región en transferencias; además el **Core es de un solo sitio**, por lo que el beneficio marginal frente a *warm standby* es bajo.

### 2.2 Implementación de la resiliencia

| Criterio (peso) | A. Librería en el código (Resilience4j / Spring) | B. *Service mesh* (Istio en EKS / VPC Lattice) | C. **Librería + ECS Service Connect** |
|---|---|---|---|
| Control semántico (reintentar solo lo idempotente, *fallbacks* de negocio) (30) | 5 | 2 | 5 |
| Consistencia de políticas entre servicios (20) | 3 | 5 | 4 |
| Esfuerzo operativo (20) | 4 | 2 | 4 |
| Observabilidad de tráfico (15) | 3 | 5 | 4 |
| Compatibilidad con la plataforma elegida (ECS Fargate, ADR-002) (15) | 5 | 1 | 5 |
| **Puntaje ponderado** | **4,10** | **2,90** | **4,45** |

Nota: **AWS App Mesh llegó a fin de soporte el 2026-09-30** (verificado en la documentación de AWS); no se considera. Istio requiere EKS, descartado en ADR-002.

## 3. Decisión

### 3.1 Alta disponibilidad en la región (todas las capas, 3 AZ)

| Capa | Mecanismo de HA y auto-healing |
|---|---|
| Borde | CloudFront, WAF, Route 53 (globales); API Gateway regional multi-AZ gestionado |
| Cómputo | ECS Fargate ≥ 3 tareas por servicio Tier 1 repartidas en 3 AZ; *health checks* de contenedor y de ALB (*liveness/readiness*) ⇒ reemplazo automático; autoscaling por CPU, solicitudes por *target* y profundidad de cola; **deployment circuit breaker** con *rollback* automático |
| Datos | Aurora Multi-AZ (réplicas en otras AZ, *failover* automático); DynamoDB y S3 multi-AZ por diseño; ElastiCache Multi-AZ con *failover* automático; Kinesis/SQS/EventBridge/Step Functions regionales multi-AZ |
| Híbrido | 2 Direct Connect + VPN de respaldo con BGP (ADR-004) |

### 3.2 DR por tier

- **Tier 1 y Tier 2 → Warm standby en `us-east-2`** (una sola plataforma; separar tiers en DR no ahorra lo suficiente para justificar dos procedimientos):
  - Datos replicados continuamente: **Aurora Global Database** (RPO típico ~1 s), **DynamoDB Global Tables**, **S3 CRR** (incluida auditoría con Object Lock), Secrets/KMS multi-región.
  - Cómputo: todos los servicios desplegados con el mismo IaC a **capacidad mínima** (1–2 tareas) y autoscaling preconfigurado.
  - Caché ElastiCache de **datos** **fría** en DR (datos derivables; se recalienta con Refresh-Ahead, ADR-011) para ahorrar costo. El clúster de **seguridad** (sesiones web, `jti`, lista de denegación) se replica con **Global Datastore** (§10 del documento 03) para que las revocaciones sobrevivan al *failover*.
  - **Sagas en curso**: tras conmutar, un *job* de rehidratación relanza en DR las transferencias en `EN_PROCESO` leyendo el estado replicado de Aurora; los pasos son idempotentes por referencia (`deb-`, `rev-`, ADR-009), así que reejecutar no duplica.
  - Conmutación **controlada** (decisión humana con *runbook*, no automática, para evitar *flapping*): Route 53 Application Recovery Controller (*routing controls*) + *failover* gestionado de Aurora Global Database. Objetivo **RTO ≤ 30 min**.
  - Conectividad al Core desde DR mediante Direct Connect Gateway (ADR-004).
- **Tier 3 → Backup & restore** con AWS Backup y copias entre regiones.
- **Dependencias externas**: el plan exige que BP declare RTO/RPO del IdP y del Core compatibles (S-18, S-20); sin ellos, el RTO compuesto queda limitado por el más débil (se declara en la estimación de costos).
- Ensayos: *game days* de DR **semestrales** y experimentos de caos trimestrales con **AWS Fault Injection Service** (caída de AZ, latencia hacia el Core, caída de proveedor de notificaciones).

### 3.3 Resiliencia en llamadas (opción C)

**Resilience4j** en BFF, `svc-*` y `acl-*` para políticas con semántica de negocio; **ECS Service Connect** para descubrimiento, balanceo, TLS interno (AWS Private CA) y métricas de tráfico.

| Dependencia | *Timeout* | Reintentos (*backoff* exponencial + *full jitter*) | *Circuit breaker* | *Bulkhead* | Degradación |
|---|---|---|---|---|---|
| Core — consultas (`acl-core`) | 800 ms | 2, solo lecturas | 50 % fallos o 50 % lentas en ventana de 20 | *Pool* de consultas | Réplica/caché con marca de frescura |
| Core — contabilización | 3 s | **No ciego**: consulta por referencia y luego reintento | Sí | *Pool* de contabilización separado | `EN_VERIFICACION`; el cliente ve "en proceso" |
| Sistema complementario | 1,5 s | 1 | Sí | *Pool* propio | Vista básica sin detalle |
| Red interbancaria | 5 s envío; confirmación asíncrona | Por paso de la saga | Sí | *Pool* propio | Aceptación `202`; compensación |
| IdP (JWKS/introspección) | 1 s | 2 | Sí | — | JWKS en caché; validación local del JWT |
| Proveedores de notificación | 3 s | 3 | Por proveedor | Por Adapter | Proveedor alterno (ADR-010) |

Reglas transversales: **presupuesto de reintentos** (≤ 10 % del tráfico) para no amplificar caídas; *timeouts* decrecientes por capa (gateway > BFF > servicio > ACL); *rate limiting* en WAF/API Gateway y presupuesto de TPS en `acl-core`; **load shedding** en BFF ante saturación (prioridad: transferencias > posición > histórico); DLQ en toda cola.

### 3.4 Degradación del plano de autorización (decisiones de §10 del documento 03)

| Estado del ElastiCache de seguridad | Comportamiento del *Lambda authorizer* |
|---|---|
| Sano | Verifica JWT, DPoP (`jti` anti-*replay*) y lista de denegación en Valkey; refresca su copia en memoria de la lista cada 15–30 s |
| Falla un nodo | *Failover* a réplica de otra AZ; sin efecto |
| Cae todo el clúster (≤ 15 min) | **Modo *fail-static***: aplica la última copia en memoria de la lista (un entorno nuevo sin copia la carga del índice `revocadas-vigentes` de DynamoDB `dispositivos`; si no responde, ese entorno queda *fail-closed*); **monetarias *fail-closed***; lecturas omiten solo `jti` con `iat` ≤ 60 s y tokens ≤ 5 min; evento `auth.degraded_mode` (P1 + SOC) |
| Cae todo el clúster (> 15 min) | *Fail-closed* total salvo extensión manual registrada en la auditoría |

Justificación: Nygard, *Release It!* (2018) — *Fail Fast* y *Steady State*; AWS Well-Architected REL05 (degradación elegante) y el concepto de **estabilidad estática** de AWS (seguir operando con el último estado conocido cuando cae una dependencia); NIST SP 800-207 (decisión de acceso por solicitud, que obliga a acotar en tiempo cualquier excepción).

Costo orientativo del DR *warm standby* (estimación de costos): ~25–35 % del costo de producción (réplicas de BD, tareas mínimas, transferencia entre regiones).

## 4. Justificación teórica

1. **AWS Well-Architected, pilar de Fiabilidad** y el *whitepaper* "Disaster Recovery of Workloads on AWS: Recovery in the Cloud": las cuatro estrategias (backup & restore, pilot light, warm standby, multi-site activo-activo) se eligen por RTO/RPO frente a costo; REL10 (aislamiento de fallas con múltiples AZ) y REL13 (planificar la recuperación ante desastres).
2. **Nygard, *Release It!* 2.ª ed. (2018)**: patrones de estabilidad *Timeouts*, *Circuit Breaker*, *Bulkheads*, *Steady State*, *Fail Fast* y *Shed Load*; "las fallas en cascada empiezan con una dependencia lenta".
3. **Google, *Site Reliability Engineering* (Beyer et al., 2016), cap. 21–22 (*Handling Overload*, *Addressing Cascading Failures*)** y AWS Builders' Library, "Timeouts, retries, and backoff with jitter" (Brooker): *backoff* exponencial con *jitter*, presupuestos de reintento y *load shedding* evitan tormentas de reintentos.
4. **Principios de ingeniería del caos** (Basiri et al., "Chaos Engineering", IEEE Software, 2016): un plan de DR no ensayado no es un plan; justificar *game days* con FIS.

## 5. Consecuencias

**Positivas:** ningún punto único de falla en la región; RTO/RPO de Tier 1 alcanzables con costo acotado; dependencias lentas aisladas por *bulkheads*; recuperación automática de tareas, BD y despliegues fallidos.

**Negativas / trade-offs:** costo del DR permanente; conmutación regional manual (minutos de decisión dentro del RTO); disciplina para mantener DR sincronizado (mitigado: mismo IaC y *pipeline* para ambas regiones).

| Riesgo | Mitigación |
|---|---|
| Core/IdP sin DR equivalente | Exigir RTO/RPO a BP; documentar disponibilidad compuesta; modo degradado de solo consulta |
| *Failover* nunca probado | *Game days* semestrales con evidencia; FIS trimestral |
| *Split-brain* en transferencias | Una sola región escritora (Aurora Global: escritura única); conmutación con *routing controls* de ARC |
| Tormenta de reintentos | Presupuesto de reintentos, *jitter*, *circuit breakers* |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Warm Standby, Multi-AZ, Deployment Stamp | Infraestructura `us-east-1` / `us-east-2` |
| Timeout, Retry + Jitter, Circuit Breaker, Bulkhead, Fallback | Resilience4j en BFF, `svc-*`, `acl-*` |
| Rate Limiting, Load Shedding, Queue-Based Load Leveling | API Gateway/WAF, BFF, SQS |
| Health Endpoint Monitoring, Auto-healing | ECS, ALB, Aurora |
| Compensating Transaction, Saga rehydration | DR de transferencias |

## 7. Equivalente Azure

Zonas de disponibilidad + región emparejada; Azure Site Recovery / réplicas geográficas (PostgreSQL *geo-replica*, Cosmos DB multi-región), Azure Front Door para conmutación, Azure Chaos Studio.

## 8. Trazabilidad

| Requisito | Diagrama C4 / otro | Sección del PDF |
|---|---|---|
| RNF-02, RNF-08 | Infraestructura (3 AZ, autoscaling, *health checks*) | §11 · §12 HA/DR |
| RNF-04, S-15 | Vista HA/DR (warm standby, replicación) | §12 |
| RNF-03 | Componentes (clientes resilientes, tabla 3.3) | §6 · §12 Resiliencia |
| RNF-13 | Rehidratación de sagas | §7 · §12 |
