# ADR-014 — Observabilidad, monitoreo y SLO por journey

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RNF-06, RNF-07, RNF-08, RNF-11, RNF-12, S-13, S-14, A-6 | 1, 8, 9, 14, 15 | Contenedores (ADOT *collector*, CloudWatch, X-Ray, Managed Grafana, Synthetics, RUM), Componentes (instrumentación OTel en cada servicio), Infraestructura (cuenta de observabilidad) |

## 1. Contexto

"Seguridad y Monitoreo", "Excelencia operativa", "auto-healing" y el criterio de rúbrica "Implementación de Monitoreo" (RNF-06/07/08). A-6 dejó la herramienta para este ADR (propuesta inicial: AWS nativo + OpenTelemetry).

Fuerzas:
- Una transacción cruza SPA/app → CloudFront → API Gateway → BFF → servicio → ACL → Core/red interbancaria, más eventos asíncronos (outbox, EventBridge, Step Functions, SQS): sin **trazas distribuidas con *correlation id*** no hay diagnóstico.
- Los objetivos están expresados por **journey de cliente** (S-13, S-14), no por servidor: login, consulta, transferencia, onboarding, notificación.
- Volumen alto de telemetría (1.000 req/s de diseño) ⇒ el costo de *logs*/trazas es una variable relevante (RNF-11).
- Seguridad (ADR-007/008) pide alarmas por **caída del IdP** y por **tasas de liveness/rechazo según versión del SDK**.
- Equipos de dominio dueños de sus servicios ("you build it, you run it").

## 2. Opciones evaluadas

| Criterio (peso) | A. AWS nativo + OpenTelemetry (ADOT) | B. APM comercial (Dynatrace / Datadog) | C. *Stack* OSS autogestionado (Prometheus, Loki, Tempo, Grafana) |
|---|---|---|---|
| Cobertura: métricas, *logs*, trazas, RUM, sintéticos, SLO (20) | 4 | 5 | 4 |
| Costo a la escala de diseño (20) | 4 | 2 | 3 |
| Portabilidad / instrumentación estándar (15) | 5 | 3 | 5 |
| Esfuerzo operativo (15) | 4 | 5 | 1 |
| Análisis automático de causa raíz / AIOps (15) | 3 | 5 | 2 |
| Integración con servicios gestionados de AWS (15) | 5 | 4 | 3 |
| **Puntaje ponderado** | **4,15** | **3,95** | **3,05** |

- **A**: CloudWatch (métricas, *logs*, **Application Signals** con SLO nativos), X-Ray como *backend* de trazas alimentado por OpenTelemetry, CloudWatch Synthetics y RUM, Amazon Managed Grafana; sin agentes propietarios. Contra: análisis de causa raíz menos automático; tableros más manuales.
- **B**: la mejor experiencia de análisis y correlación automática; Dynatrace y Datadog ingieren OTLP. Contra: licencias por *host*/GB que crecen con el tráfico y agentes propietarios.
- **C**: control y portabilidad totales, pero operar el *stack* de observabilidad de un banco es un producto en sí mismo (HA, retención, escalado).

**Diferencia estrecha A vs. B**: si BP ya tiene licenciado un APM corporativo, B pasa a ser preferible; por eso la instrumentación es **OpenTelemetry**, que permite cambiar de *backend* sin tocar el código.

## 3. Decisión

**Opción A: AWS nativo con instrumentación OpenTelemetry**, en una **cuenta de observabilidad central** (CloudWatch *cross-account observability*).

| Señal | Implementación |
|---|---|
| Trazas | SDK/agente OpenTelemetry en Java (auto-instrumentación) → ADOT *collector* (*sidecar* en ECS) → X-Ray / Application Signals; contexto **W3C `traceparent`** propagado desde el borde, en mensajes (atributos de SQS/EventBridge/Kinesis) y en Step Functions; muestreo de cabecera 10 % + **100 % de errores y latencias altas** |
| Métricas | *Golden signals* por servicio (latencia, tráfico, errores, saturación) vía OTel/EMF; métricas de negocio: transferencias/min, tasa de rechazo, compensaciones, *lag* de CDC (`IteratorAge`), antigüedad del outbox, *hit ratio* de caché, profundidad de DLQ, entregas por canal |
| *Logs* | JSON estructurado con `traceId`, `correlationId`, `sub` seudonimizado; **enmascaramiento de PII** en la librería de *logging* + *data protection policies* de CloudWatch Logs; retención 30–90 días y exportación a S3 |
| Experiencia real | CloudWatch RUM (SPA) y SDK de *crash/performance* en la app; **CloudWatch Synthetics**: *canaries* por journey cada minuto desde `us-east-1` y `us-east-2` con usuarios sintéticos |
| Tableros | Amazon Managed Grafana: un tablero por journey (vista de negocio) y por servicio (vista técnica) |
| Seguridad | GuardDuty, Security Hub, CloudTrail, *logs* de WAF (detalle en documento de seguridad) |

**SLO por journey** (S-13/S-14) con **alertas por tasa de consumo del presupuesto de error** (*multiwindow, multi-burn-rate*: 14,4× en 1 h/5 min ⇒ página; 6× en 6 h/30 min ⇒ página; 1× en 3 días ⇒ ticket):

| Journey | SLI | SLO |
|---|---|---|
| Login | Logins exitosos / intentos válidos; p95 | **99,9 %** (SLO de §10; 99,95 % si BP entrega IdP ≥ 99,99 %); ≤ 1,5 s |
| Posición consolidada | Respuestas 2xx/3xx; p95 | 99,95 %; ≤ 500 ms (con caché) |
| Movimientos | Respuestas válidas; p95 primera página | 99,95 %; ≤ 800 ms |
| Transferencia propia | Completadas sin error técnico; p95 | **99,9 %** (SLO de §10, limitada por el Core); ≤ 2 s |
| Transferencia interbancaria | Aceptación p95; % con estado final dentro del plazo de la modalidad | ≤ 2 s; 99 % |
| Onboarding | Expedientes sin error técnico; conversión (métrica de negocio) | 99,9 % |
| Notificación de movimiento | Entregadas por ≥ 2 canales en ≤ 60 s | 99,9 % |

Nota (SLO de §10): el cálculo de disponibilidad compuesta (`03` §2.2) muestra que el IdP y el Core de BP (99,95 % supuestos) limitan login y transferencia propia a ≈ 99,90 %; por eso su SLO es 99,9 % y se solicita a BP un IdP ≥ 99,99 % para subir el de login. Se alerta además por `auth.degraded_mode` (P1 + SOC, ADR-013 §3.4).

**Alertas accionables** (cada una con *runbook* enlazado y dueño): consumo de presupuesto de error por journey; **IdP no disponible o con latencia anómala** (*canary* + tasa de errores en el *authorizer*); **tasa de aprobación/rechazo de liveness y de coincidencia 1:1 por versión del SDK IDV** (detección de anomalías: caída brusca ⇒ posible regresión o ataque); DLQ > 0 en flujos monetarios; outbox con antigüedad > 60 s; *lag* de CDC > 60 s; compensaciones fallidas (P1); *circuit breaker* abierto hacia el Core; caída anómala del volumen de transferencias frente a la línea base (CloudWatch *anomaly detection*).

**Operación**: alarmas → SNS → herramienta de guardia y canal de chat del equipo; **auto-remediación** con SSM Automation para acciones seguras (escalar, reciclar tareas, abrir *circuit breaker* manual); revisión post-incidente sin culpables; los SLO alimentan la política de *error budget* (si se agota, se priorizan fiabilidad sobre funcionalidades).

Costo orientativo (estimación de costos): la ingesta de *logs* es el principal impulsor; con muestreo de trazas y niveles de *log* controlados se estima del orden de **USD 1.500–3.000/mes**.

## 4. Justificación teórica

1. **Google SRE Book (Beyer et al., 2016), cap. 4 (SLO) y cap. 6 (*four golden signals*)** y **The Site Reliability Workbook (2018), cap. 5 (alertas por *burn rate* multiventana)**: medir lo que siente el cliente (journeys), alertar sobre síntomas y no sobre causas, y gobernar con presupuesto de error.
2. **OpenTelemetry (CNCF) y W3C Trace Context**: estándar abierto de instrumentación y propagación de contexto que desacopla el código del *backend* de observabilidad (evita *lock-in*; coherente con 12-Factor, factor XI "logs como flujos de eventos").
3. **AWS Well-Architected, pilar de Excelencia Operativa (OPS04/OPS08: implementar observabilidad, utilizar la observabilidad de la carga de trabajo)** y pilar de Fiabilidad (REL06: monitorear todos los componentes; REL07: escalar y remediar automáticamente).
4. **Majors, Fong-Jones & Miranda, *Observability Engineering* (O'Reilly, 2022)**: eventos estructurados de alta cardinalidad y trazas distribuidas para depurar sistemas distribuidos con "incógnitas desconocidas".

## 5. Consecuencias

**Positivas:** trazabilidad extremo a extremo incluso a través de flujos asíncronos; alertas ligadas a impacto en cliente; sin licencias por *host*; *backend* reemplazable gracias a OTel.

**Negativas / trade-offs:** más trabajo de construcción de tableros y correlación que un APM comercial; costo de *logs* a vigilar; varias consolas (CloudWatch, Grafana).

| Riesgo | Mitigación |
|---|---|
| Fatiga de alertas | Solo alertas por síntoma con *runbook*; revisión mensual de alertas ruidosas |
| PII en *logs*/trazas | Enmascaramiento en origen, políticas de protección de datos, revisión en CI |
| Explosión de costo de telemetría | Muestreo, niveles de *log* por entorno, retención por niveles, presupuesto con alarma de AWS Budgets |
| Pérdida de contexto en asíncronos | Propagación obligatoria de `traceparent` en atributos de mensajes (verificada en pruebas de contrato) |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Distributed Tracing, Correlation Identifier | OTel + W3C Trace Context |
| Health Endpoint Monitoring, Synthetic Transactions | *Canaries* por journey |
| Log Aggregation, Application Metrics | CloudWatch, cuenta central |
| SLO / Error Budget, Burn-Rate Alerting | Tabla de SLO |
| Auto-remediation (Runbook Automation) | SSM Automation |

## 7. Equivalente Azure

Azure Monitor + Application Insights (con OpenTelemetry), Log Analytics, Azure Managed Grafana y pruebas de disponibilidad estándar.

## 8. Trazabilidad

| Requisito | Diagrama C4 / otro | Sección del PDF |
|---|---|---|
| RNF-06 | Contenedores (observabilidad) · Infraestructura (cuenta central) | §5 · §13 Monitoreo |
| RNF-07, RNF-08 | Auto-remediación, *runbooks* | §13 Excelencia operativa |
| S-13, S-14 | Tabla de SLO por journey | §13 |
| RNF-11 | — | §15 Costos |
