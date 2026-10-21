# ADR-010 — Notificaciones multicanal con proveedor alterno

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-08, RF-15, RNF-01, RNF-03, RNF-15, RNF-16, S-23, A-5 | 1, 4, 5, 13, 14 | Contexto (proveedores de notificación), Contenedores (`svc-notificaciones`, SQS, SES, End User Messaging, FCM/APNs, proveedor SMS alterno), Componentes de `svc-notificaciones` |

## 1. Contexto

"La norma exige que los usuarios sean notificados sobre los movimientos realizados, el sistema utilizará sistemas externos o propios de envío de notificaciones, mínimo 2" (RF-08). Se adopta **A-5 (c)**: ≥2 canales (push, correo, SMS) con proveedor alterno.

Fuerzas:
- Cumplimiento: cada movimiento monetario debe notificarse y **poder demostrarse** (evidencia de envío/entrega en auditoría, RF-15).
- Ningún proveedor ni canal debe ser punto único de falla (RNF-03); la notificación no debe bloquear ni revertir la transferencia (es diferible).
- Volumen: ~80.000 operaciones monetarias/día + créditos recibidos + eventos de seguridad (nuevo dispositivo, *step-up*) ⇒ ~3–5 M mensajes/mes por canal; picos de quincena (RNF-15).
- Privacidad (RNF-16): mínimo dato personal en el contenido; el texto de un SMS o *push* es visible en pantalla bloqueada.
- **Estado de servicios AWS verificado (2026-10-18):** Amazon Pinpoint llega a **fin de soporte el 2026-10-30**; sus APIs de SMS, voz y *push* continúan como **AWS End User Messaging**. No se usa Pinpoint.

## 2. Opciones evaluadas

| Criterio (peso) | A. Amazon SNS directo (fan-out SMS/correo/*push*) | B. Servicio propio con Strategy (canal) + Adapter (proveedor) sobre servicios gestionados | C. Plataforma CPaaS única de terceros |
|---|---|---|---|
| "≥2" con redundancia de canal **y** de proveedor (25) | 2 | 5 | 3 |
| Control y evidencia de entrega para auditoría (20) | 2 | 5 | 4 |
| Extensibilidad (nuevos canales/proveedores, plantillas, preferencias) (15) | 2 | 5 | 3 |
| Costo (15) | 5 | 4 | 3 |
| Esfuerzo operativo (15) | 5 | 3 | 4 |
| Privacidad / control del dato (10) | 3 | 4 | 3 |
| **Puntaje ponderado** | **3,00** | **4,45** | **3,35** |

- **A. SNS directo**: simple y barato, pero un solo proveedor por canal, correo de SNS no apto para clientes (sin plantillas ni dominio propio) y poca evidencia de entrega.
- **B. Servicio propio**: política de canales, plantillas versionadas, preferencias, conmutación de proveedor y registro de entregas bajo control de BP; usa servicios gestionados por debajo. Contra: un servicio más que construir y operar.
- **C. CPaaS único**: rápido de integrar, pero concentra todos los canales en un proveedor (punto único de falla comercial y técnico) y externaliza el dato.

## 3. Decisión

**Opción B**: `svc-notificaciones` (ECS Fargate) suscrito por **SQS + DLQ** a eventos del bus `bp-dominio` (`TransferenciaCompletada|Rechazada|Reversada`, `PagoCompletado`, `MovimientoRegistrado` de créditos, `auth.device.enrolled`, `auth.stepup.*`, `onboarding.completed`).

| Canal (Strategy) | Proveedor primario (Adapter) | Proveedor alterno (Adapter) | Uso |
|---|---|---|---|
| **Push** | FCM (Android) / APNs (iOS) | AWS End User Messaging Push | Toda operación monetaria (canal inmediato) |
| **Correo** | Amazon SES (dominio propio con SPF, DKIM, DMARC) | SES en `us-east-2` o proveedor SMTP externo | Toda operación monetaria (canal con constancia) |
| **SMS** | AWS End User Messaging SMS | Agregador SMS local con conexión a operadoras del país | Eventos de seguridad, montos sobre umbral, o si el *push* no se confirma en N minutos |

Reglas:
1. **Política normativa**: cada movimiento monetario se envía **al menos por 2 canales** (push + correo por defecto). El cliente puede elegir canales, no desactivar la notificación obligatoria.
2. **Conmutación**: *circuit breaker* por proveedor; si el primario abre el circuito o devuelve error retryable, el **Adapter alterno** toma el envío. Si un canal entero falla, la política agrega SMS para mantener ≥2 canales.
3. **Idempotencia**: clave `(eventId, canal)`; reintentos con *backoff* + *jitter*; DLQ con alarma y *redrive*.
4. **Evidencia**: estado por mensaje (`ENCOLADO → ENVIADO → ENTREGADO | FALLIDO`) en DynamoDB `notificaciones` (TTL 13 meses) alimentado por recibos de entrega (eventos de SES y de End User Messaging); cada cambio emite evento `notificacion.*` a la auditoría inmutable (ADR-012).
5. **Contenido mínimo**: cuenta enmascarada (`****1234`), monto, fecha, comprobante; sin saldos ni nombres completos en SMS/*push*; plantillas versionadas por idioma y canal; *payload* de *push* sin datos sensibles (la app consulta el detalle autenticada).
6. **Escalado**: `svc-notificaciones` escala por profundidad de cola (*Queue-Based Load Leveling*), respetando los límites de envío de cada proveedor.

Costo orientativo (estimación de costos): SES ≈ USD 0,10 por 1.000 correos ⇒ ~USD 300–500/mes; *push* sin costo por mensaje; **SMS a cotizar** con tarifa por mensaje hacia Ecuador (variable relevante: se acota a eventos de seguridad y umbral).

## 4. Justificación teórica

1. **Strategy y Adapter** — Gamma, Helm, Johnson & Vlissides, *Design Patterns* (1994): Strategy encapsula la política por canal y Adapter unifica proveedores heterogéneos tras una interfaz común, de modo que cambiar o agregar un proveedor no toca la política (principio abierto/cerrado).
2. **Circuit Breaker y redundancia de dependencias** — Nygard, *Release It!* 2.ª ed. (2018); AWS Well-Architected, pilar de Fiabilidad (REL10: usar aislamiento de fallas y redundancia para proteger la carga): un proveedor caído no debe impedir el cumplimiento normativo.
3. **Guaranteed Delivery, Dead Letter Channel, Idempotent Receiver** — Hohpe & Woolf, *Enterprise Integration Patterns* (2003): entrega garantizada con colas persistentes y deduplicación por `(eventId, canal)`.
4. **Minimización y protección de datos** — LOPDP (Ecuador, 2021, principio de minimización — artículo exacto a verificar) e ISO/IEC 27701: el contenido de canales visibles en pantalla bloqueada se limita al mínimo necesario.

## 5. Consecuencias

**Positivas:** cumplimiento de "mínimo 2" con redundancia doble (canal y proveedor); evidencia auditable de cada envío; cambiar de proveedor SMS es un nuevo Adapter.

**Negativas / trade-offs:** servicio y plantillas que mantener; contratos con ≥2 proveedores; la notificación es eventual (segundos), no síncrona con la transferencia.

| Riesgo | Mitigación |
|---|---|
| Correos marcados como *spam* | SPF/DKIM/DMARC, dominio dedicado, reputación monitoreada (métricas de rebote/queja de SES) |
| *Phishing* que imita las notificaciones | Sin enlaces de acción en SMS/correo; educación al cliente; BIMI opcional |
| Tormenta de reintentos ante caída de proveedor | *Circuit breaker* + *backoff* con *jitter*; límite de concurrencia por Adapter (*bulkhead*) |
| Retraso por acumulación en pico | Autoscaling por profundidad de cola; alarma de antigüedad del mensaje más viejo |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Strategy (canal), Adapter (proveedor) | Componentes `PoliticaCanales`, `CanalPush/Correo/Sms`, `Adapter*` |
| Circuit Breaker, Retry + Jitter, Bulkhead, Failover | Por proveedor |
| Publish-Subscribe, Queue-Based Load Leveling, Dead Letter Channel | EventBridge → SQS → `svc-notificaciones` |
| Idempotent Receiver | Clave `(eventId, canal)` |
| Template Method | Renderizado de plantillas por canal |

## 7. Equivalente Azure

Azure Communication Services (correo y SMS) + Azure Notification Hubs (*push*) + Service Bus; proveedor alterno externo por canal.

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RF-08, A-5 | Contexto (proveedores) · Contenedores (`svc-notificaciones` y proveedores) | §4 · §5 · §10 Integración |
| RNF-03 | Componentes `svc-notificaciones` (Strategy/Adapter, *circuit breakers*) | §6 · §12 Resiliencia |
| RF-15 | Eventos `notificacion.*` hacia auditoría | §9.x Auditoría |
| RNF-01, RNF-16 | Reglas de contenido mínimo | §14 Normativa |
