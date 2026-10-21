# ADR-012 — Auditoría completa, inmutable y consultable

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-15, RNF-01, RNF-05, RNF-14, RNF-16, S-09, S-10, S-17 | 1, 4, 7, 12, 13 | Contexto (auditor/operador), Contenedores (`svc-auditoria`, Kinesis, Firehose, S3 Object Lock, Athena, KMS, cuenta *log-archive*), Componentes de `svc-auditoria` y librería de auditoría en cada servicio |

## 1. Contexto

"El sistema utiliza una base de datos de auditoría que registra **todas las acciones del cliente**" (RF-15). Requisito derivado RNF-14: registro *append-only*, protegido contra borrado/alteración, con retención acorde a la norma y consultable por cliente/rango de fechas.

Fuerzas:
- Volumen: ~160 M eventos/mes (~1 KB c/u, ~160 GB/mes sin comprimir, S-09) — incluye consultas, no solo transacciones.
- Retención: 12 meses consultables en línea + archivo inmutable hasta 10 años (S-10; **plazo legal exacto a verificar**).
- Completitud: una acción que ocurrió no puede faltar (p. ej. transferencia sin su evento); tampoco debe bloquear la operación si la auditoría está lenta.
- Seguridad (ADR-007/008): se auditan los eventos `auth.*` y `onboarding.*` definidos allí (incluidos registro y revocación de instancia de cliente `auth.client_instance.registered|revoked`, bloqueo y reanudación de sesión `auth.session.locked|resumed`, y alta o baja de dispositivo), **sin tokens, claves, OTP, imágenes ni plantillas biométricas**; minimización de datos personales (LOPDP).
- **Estado de servicios AWS verificado (2026-10-18):** **Amazon QLDB** llegó a fin de soporte (2025-07-31) y **AWS CloudTrail Lake** no admite nuevos clientes desde 2026-05-31 ⇒ ambos se descartan.

## 2. Opciones evaluadas

| Criterio (peso) | A. Tabla *append-only* en Aurora PostgreSQL | B. **Log inmutable: Kinesis → `svc-auditoria` (cadena de *hashes*) → S3 Object Lock (Parquet) + Athena** | C. Amazon OpenSearch Service como almacén principal |
|---|---|---|---|
| Inmutabilidad verificable (25) | 3 (permisos; un DBA privilegiado puede alterar) | 5 (WORM en modo *Compliance* + cadena de *hashes* firmada) | 2 |
| Completitud / no pérdida (20) | 5 | 4 | 3 |
| Retención de 10 años a costo razonable (20) | 2 | 5 (niveles de almacenamiento de S3) | 2 |
| Consultabilidad para auditor/regulador (15) | 4 | 3 (SQL con latencia de segundos) | 5 |
| Escalabilidad a 160 M eventos/mes (10) | 2 | 5 | 4 |
| Esfuerzo operativo (10) | 4 | 4 | 3 |
| **Puntaje ponderado** | **3,35** | **4,40** | **2,95** |

- **A**: transaccional y sencillo, pero el volumen y 10 años de retención inflan costo y tamaño; la inmutabilidad depende de permisos.
- **B**: separa ingesta (*stream*), sellado (cadena de *hashes* y firmas) y almacenamiento WORM barato; consulta SQL sin servidores. Contra: consultas de segundos y más piezas.
- **C**: búsqueda excelente, pero no es WORM, el costo de retener 10 años es alto y su modelo de índices mutables no prueba integridad.
- **Descartadas sin puntuar:** QLDB (fin de soporte) y CloudTrail Lake (cerrado a nuevos clientes).

## 3. Decisión

**Opción B**, con *Event Sourcing* aplicado al **registro de auditoría** (no al estado de negocio):

```
Servicios (librería de auditoría) ─┬─ outbox (acciones transaccionales) ─→ relay ─┐
                                    └─ envío asíncrono con búfer (consultas/lecturas) ─→ Kinesis Data Streams `auditoria`
IdP / BFF (auth.*), onboarding.*, notificacion.* ────────────────────────────────────→ (partición = cliente)
Kinesis ─→ svc-auditoria: valida esquema, minimiza/enmascara, encadena SHA-256 por partición ─→ Firehose ─→
S3 (cuenta log-archive) Object Lock COMPLIANCE, Parquet particionado por fecha ─→ Glue Catalog + Athena (consulta)
Lambda horaria: raíz Merkle del período firmada con KMS (llave asimétrica) ─→ bucket de sellos (Object Lock)
```

1. **Esquema canónico** (versionado): `eventId`, `timestamp` UTC (Amazon Time Sync), `actor` (`sub` seudonimizado + tipo: cliente/operador/sistema), `accion` (`auth.*`, `onboarding.*`, `consulta.*`, `transferencia.*`, `pago.*`, `notificacion.*`, `perfil.*`, `admin.*`), `recurso`, `resultado`, `canal`, IP, *hash* del *device id*, `correlationId`/`traceId`, `datos` minimizados, `hashPrevio`, `hash`.
2. **Completitud**: acciones transaccionales (transferencias, pagos, onboarding) se auditan vía **outbox en la misma transacción** (no se pierden); lecturas y consultas vía librería asíncrona con búfer y reintentos (no bloquean al cliente). **Conciliación diaria** contra fuentes independientes (*access logs* de API Gateway y eventos del IdP) por `correlationId`; brechas ⇒ alarma.
3. **Inmutabilidad**: S3 **Object Lock en modo Compliance** con retención de 10 años (nadie, ni la cuenta *root*, puede borrar antes de vencer), en una **cuenta *log-archive*** separada (AWS Organizations, SCP que niega desactivar el bloqueo), cifrado SSE-KMS con llave dedicada, réplica a `us-east-2` (S3 CRR con Object Lock).
4. **Integridad verificable**: cadena de *hashes* por partición (la **cabeza de la cadena** —último `hash` de cada partición— se persiste junto con el *checkpoint* de KCL en su tabla de *leases* de DynamoDB, en la misma escritura condicional que avanza el *checkpoint*, para que un reinicio o una reasignación de *shard* continúe la cadena sin huecos ni bifurcaciones) + **sello horario** (raíz Merkle firmada con KMS); un *job* diario recalcula y verifica; el auditor puede comprobar cualquier evento con su prueba.
5. **Retención por niveles**: 0–12 meses en S3 Standard / Intelligent-Tiering (consultable con Athena); 12 meses–10 años en **S3 Glacier Deep Archive** (restauración bajo pedido, horas). Costo de almacenamiento resultante del orden de decenas de USD/mes; Kinesis + Firehose + Athena del orden de cientos de USD/mes (estimación de costos).
6. **Acceso**: auditor/operador mediante grupo de trabajo de Athena con **Lake Formation** (columnas PII enmascaradas salvo rol autorizado); las propias consultas de auditoría quedan registradas (CloudTrail *data events*). Búsqueda interactiva con OpenSearch queda como evolución opcional sobre una copia, nunca como fuente.

**PoC (A-8)**: tabla `auditoria` *append-only* en PostgreSQL con cadena de *hashes*, *trigger* que rechaza `UPDATE`/`DELETE` y evento recibido desde el outbox de la transferencia.

## 4. Justificación teórica

1. **Event Sourcing / log inmutable** — Fowler, "Event Sourcing" (2005) y Kleppmann, *Designing Data-Intensive Applications* (2017), cap. 11: un *log* de solo-anexión es la representación natural de "lo que ocurrió"; las vistas de consulta se derivan de él.
2. **Integridad criptográfica por encadenamiento y árboles de Merkle** — Haber & Stornetta, "How to Time-Stamp a Digital Document" (1991) y Merkle (1987): cada registro compromete al anterior y los sellos firmados hacen detectable cualquier alteración — el mismo principio que usaba QLDB, ahora sobre almacenamiento WORM.
3. **NIST SP 800-92 (gestión de logs de seguridad)** y **NIST SP 800-53 familia AU** (AU-9 protección de la información de auditoría, AU-10 no repudio, AU-11 retención): protección contra modificación, separación de funciones y retención definida; **PCI DSS v4.0 req. 10** si hay datos de tarjeta.
4. **AWS Well-Architected, pilar de Seguridad (SEC04: detección — configurar registro de servicios y aplicaciones, almacenar logs de forma centralizada e inmutable)** y la guía de *landing zone* con cuenta *log-archive*; S3 Object Lock evaluado para retención regulatoria WORM (SEC 17a-4, CFTC, FINRA — evaluación de Cohasset Associates publicada por AWS).

## 5. Consecuencias

**Positivas:** prueba verificable de integridad; retención de 10 años a costo muy bajo; escala sin gestión; ningún operador (ni administrador) puede alterar o borrar; desacoplado de la ruta crítica.

**Negativas / trade-offs:** consultas en segundos (no milisegundos); consistencia eventual de la consulta (minutos por el *buffer* de Firehose); Object Lock Compliance es irreversible (un error de configuración de retención no se puede acortar).

| Riesgo | Mitigación |
|---|---|
| Pérdida de eventos de lectura ante caída de Kinesis | Búfer local + reintentos; respaldo a SQS; conciliación diaria con *access logs* |
| PII excesiva en auditoría | Validación de esquema y lista de campos permitidos en `svc-auditoria`; enmascaramiento; revisión de privacidad (documento de seguridad) |
| Retención mal configurada (irreversible) | Probar en cuenta no productiva; *governance mode* en entornos de prueba; revisión dual del IaC |
| Obligación de supresión de datos personales vs. WORM | Seudonimización (`sub`) y llave de reidentificación separada; base legal de conservación (obligación regulatoria) a confirmar |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Event Sourcing (registro de auditoría), Append-Only Log | Kinesis → S3 |
| Transactional Outbox | Acciones transaccionales |
| Hash Chain / Merkle Tree, Signed Digest | `svc-auditoria` + Lambda de sellado |
| WORM Storage, Cold Archive | S3 Object Lock + Glacier Deep Archive |
| Claim Check | Referencias a evidencias de onboarding (sin imágenes en el *log*) |
| Reconciliation | Conciliación diaria con fuentes independientes |

## 7. Equivalente Azure

Event Hubs → Azure Functions/Container Apps (encadenado) → Blob Storage con políticas de inmutabilidad (*time-based retention*, *locked*) + Azure Data Explorer o Synapse serverless para consulta; Key Vault para firmas.

## 8. Trazabilidad

| Requisito | Diagrama C4 / otro | Sección del PDF |
|---|---|---|
| RF-15 | Contexto (auditor) · Contenedores (Kinesis, `svc-auditoria`, S3, Athena) | §4 · §5 · §9.x Auditoría |
| RNF-14 | Componentes `svc-auditoria` (validador, encadenador, sellador) · Infraestructura (cuenta *log-archive*) | §6 · §9.x · §11 |
| RNF-01, RNF-16 | Reglas de minimización y retención | §14 Normativa |
