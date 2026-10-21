# ADR-002 — Plataforma AWS: región principal/DR y plataforma de cómputo

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RNF-02, RNF-04, RNF-08, RNF-09, RNF-11, RNF-12, RNF-15, S-11, S-12, S-15, S-16, S-17, S-21 | 1, 8, 9, 14 | Contenedores (límite de nube, tecnología de cada servicio), Infraestructura/despliegue AWS, vista HA/DR |

## 1. Contexto

El enunciado exige nube AWS/Azure con "garantía de baja latencia" (RNF-09), HA, DR y auto-healing (RNF-02/04/08). La nube **AWS** es una restricción del ejercicio (R-01). **No existe región AWS en Ecuador**; la salida internacional del país se enruta mayoritariamente por cables submarinos hacia EE. UU. (S-11). El Core y el sistema complementario permanecen on-premise y se conectan por Direct Connect (S-12), por lo que la región elegida también fija la latencia **nube ↔ Core**, que está en la ruta crítica de transferencias.

Carga de diseño: 1.000 req/s en el gateway y 50 TPS monetarias (S-06, S-07), con picos de quincena ×4. 14 servicios desplegables en contenedor (catálogo de ADR-001 más el worker de la saga y el proyector CQRS), escritos en Java 21/Spring Boot (decisión de la PoC) por equipos de dominio.

Preguntas: (a) ¿qué región principal y cuál de DR? (b) ¿EKS, ECS Fargate o Lambda para ejecutar los servicios?

## 2. Opciones evaluadas

### 2.1 Región (principal + DR)

RTT desde Ecuador **estimado** (S-11), pendiente de medición real desde Quito/Guayaquil antes de producción.

| Criterio (peso) | A. us-east-1 + DR us-east-2 | B. sa-east-1 + DR us-east-1 | C. mx-central-1 + DR us-east-1 |
|---|---|---|---|
| Latencia estimada desde Ecuador y hacia el Direct Connect (30) | 4 (~60–90 ms) | 2 (~120–160 ms) | 4 (a medir; ruta probable vía EE. UU.) |
| Catálogo y madurez de servicios (20) | 5 | 4 | 3 (región reciente) |
| Costo relativo (15) | 5 | 2 (precios notablemente mayores) | 4 |
| Par de DR cercano en la misma jurisdicción (15) | 5 (Ohio, ~10–15 ms entre regiones) | 3 | 3 (sin segunda región en México) |
| Afinidad regulatoria / residencia regional (20) | 3 | 4 | 4 |
| **Puntaje ponderado** | **4,30** | **2,95** | **3,65** |

- **A. us-east-1/us-east-2**: mayor catálogo (todo servicio nuevo llega primero), precios de referencia más bajos, par de regiones cercano y en la misma jurisdicción, mejor ruta de red estimada desde Ecuador. Contra: concentración de clientes en us-east-1 (riesgo de eventos regionales de gran escala), datos fuera de LATAM.
- **B. sa-east-1**: residencia en Sudamérica, pero la ruta de red desde Ecuador suele pasar por EE. UU., la latencia estimada es mayor y el costo también.
- **C. mx-central-1**: región LATAM más cercana geográficamente, pero joven (catálogo menor) y sin región par en el mismo país; atractiva como evolución si mide mejor latencia.

### 2.2 Cómputo

| Criterio (peso) | A. Amazon EKS | B. Amazon ECS sobre Fargate | C. AWS Lambda |
|---|---|---|---|
| Esfuerzo operativo (plano de control, nodos, parches) (25) | 2 | 5 | 5 |
| Control y portabilidad (Kubernetes estándar) (15) | 5 | 3 | 2 |
| Costo a carga sostenida (1.000 req/s) (20) | 4 | 4 | 2 |
| Latencia estable (sin arranque en frío para Java) (15) | 5 | 5 | 3 |
| Escalado y auto-healing nativos (15) | 4 | 4 | 5 |
| Ajuste a equipos de dominio sin especialistas K8s (10) | 3 | 4 | 3 |
| **Puntaje ponderado** | **3,70** | **4,25** | **3,45** |

- **EKS**: máximo control y ecosistema (Istio, Argo, Karpenter), pero exige un equipo de plataforma Kubernetes, gestión de versiones del clúster y de *add-ons*.
- **ECS Fargate**: sin servidores que parchear, tareas aisladas por microVM, integración nativa con ALB, Service Connect, Secrets Manager, CloudWatch; *deployment circuit breaker* con *rollback* automático. Contra: menos portabilidad que Kubernetes y menos control fino de red.
- **Lambda**: ideal para cargas por evento, pero a carga sostenida resulta más caro que contenedores, los servicios Java requieren SnapStart para mitigar arranque en frío y el modelo de 15 min/invocación no encaja con *pools* de conexiones al Core.

## 3. Decisión

1. **Región principal `us-east-1` (N. Virginia)** con **DR en `us-east-2` (Ohio)**, desplegando en **3 zonas de disponibilidad**. Condicionado a: (i) medir RTT real desde Quito/Guayaquil a `us-east-1`, `mx-central-1` y `sa-east-1` (pruebas a *endpoints* regionales) — si `mx-central-1` mejora >20 % y tiene los servicios requeridos, se reabre la decisión; (ii) verificación normativa de procesamiento fuera del país (S-21), resuelta en `02-seguridad-y-normativa.md` §9.2: permitido por la LOPDP (arts. 55–60) con instrumento jurídico vinculante (art. 57) o autorización de la Autoridad (art. 59), declaración de proveedores ante la SB y plan B híbrido para evidencias biométricas y bóveda de tokens.
2. **Amazon ECS sobre AWS Fargate** (arquitectura **ARM64/Graviton**) para BFF, servicios de dominio y ACL; **AWS Lambda** solo para *glue* orientado a eventos de baja frecuencia (p. ej. sellado diario de auditoría, ADR-012; tareas programadas). El borde usa servicios gestionados globales (CloudFront, WAF, Route 53).
3. Cuentas separadas por entorno con **AWS Organizations** (dev, qa, staging, prod, DR, *log-archive*, seguridad) según S-17.
4. Conectividad híbrida: **Direct Connect Gateway** asociado a ambas regiones para que el DR alcance el Core sin rediseñar enlaces (detalle en ADR-004).

Costo orientativo (a refinar en la estimación de costos, precios públicos de referencia `us-east-1`): Fargate ARM ≈ 20 % más barato que x86; ~12 servicios × 2–8 tareas de 0,5–1 vCPU en producción ⇒ orden de magnitud **USD 2.000–4.000/mes** en cómputo de producción, más DR *warm standby* reducido (ADR-013).

## 4. Justificación teórica

1. **AWS Well-Architected, pilar de Eficiencia del Rendimiento (PERF)** — "seleccionar la ubicación de los recursos según la latencia hacia los usuarios y dependencias" y **pilar de Optimización de Costos (COST)** — preferir servicios gestionados que transfieren el trabajo indiferenciado al proveedor; Fargate elimina gestión de nodos y la elección de región minimiza latencia al usuario y al Core.
2. **AWS Well-Architected, pilar de Fiabilidad (REL10/REL13)** — usar múltiples zonas de disponibilidad para cargas críticas y elegir la estrategia de DR por objetivos RTO/RPO; el par us-east-1/us-east-2 permite replicación con RPO de segundos (Aurora Global Database, DynamoDB Global Tables).
3. **12-Factor App** (Wiggins, 2011), factores VI (procesos sin estado), VIII (concurrencia por escalado horizontal) y IX (desechabilidad): los servicios en contenedores sin estado son reemplazables por el orquestador (auto-healing) y escalan horizontalmente.
4. **Ley de Conway / Team Topologies** (Skelton & Pais, 2019): reducir la carga cognitiva de los equipos de dominio con una plataforma "como producto"; ECS Fargate exige menos especialización que operar Kubernetes.

## 5. Consecuencias

**Positivas:** sin parches de SO ni gestión de nodos; auto-healing nativo (tareas que fallan *health checks* se reemplazan); escalado por métricas (CPU, *request count* por *target*); acceso a todo el catálogo AWS.

**Negativas / trade-offs:** dependencia de APIs de ECS (mitigable: imágenes OCI estándar, sin lógica acoplada al orquestador); datos alojados fuera de Ecuador (sujeto al documento de seguridad); us-east-1 tiene historial de incidentes regionales de alto impacto (mitigado con DR en us-east-2, ADR-013).

| Riesgo | Mitigación |
|---|---|
| Latencia real peor que la estimada | Medición previa; CloudFront en el borde; caché (ADR-011); reevaluar `mx-central-1` |
| Restricción normativa de residencia (S-21, RK-04) | Diseño con datos sensibles desacoplados; alternativa híbrida (datos en on-prem, cómputo en nube) documentada |
| Límite de cuotas de Fargate en picos | Aumentos de cuota solicitados por adelantado; *capacity provider* mixto Fargate/Fargate Spot solo en entornos no productivos |
| Bloqueo a proveedor | Contenedores OCI, OpenTelemetry (ADR-014), IaC con Terraform |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Deployment Stamp / multi-AZ | Servicios ECS distribuidos en 3 AZ |
| Health Endpoint Monitoring | `/actuator/health/liveness` y `/readiness` evaluados por ECS y ALB |
| Immutable Infrastructure | Imágenes de contenedor versionadas; despliegues *blue/green* (CodeDeploy) o *rolling* con *circuit breaker* |
| Landing Zone multi-cuenta | AWS Organizations + SCP |

## 7. Equivalente Azure

Región East US 2 con par Central US (o Brazil South / Mexico Central a evaluar); Azure Container Apps (o AKS si se requiere Kubernetes) y Azure Functions para *glue*.

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RNF-09, S-11 | Infraestructura AWS (regiones, AZ) | §11 Nube e infraestructura |
| RNF-02, RNF-08, RNF-15 | Contenedores (tecnología ECS Fargate) · Infraestructura | §11 · §12 HA/DR |
| RNF-04 | Vista HA/DR | §12 |
| RNF-11 | — | §15 Costos |
