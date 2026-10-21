# ADR-004 — Integración con Core y sistema complementario (ACL + conectividad híbrida) y modelo de lectura de movimientos

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-01, RF-05..RF-07, RF-19, RF-20, RNF-03, RNF-09, RNF-10, RNF-12, S-08, S-12, S-13, S-18, S-19, S-22 | 1, 3, 4, 5, 7, 14 | Contexto (Core, complementario, red interbancaria), Contenedores (`acl-core`, `acl-complementario`, `acl-interbancario`, Kinesis, DMS, Direct Connect), Componentes de `svc-cuentas` (proyector), Infraestructura (enlaces híbridos) |

## 1. Contexto

Los datos del cliente "se tomarán de 2 sistemas": el **Core** (datos básicos, movimientos, productos) y un **sistema independiente** que complementa "cuando los datos se requieren en detalle" (RF-05..07). Las transferencias "realizan llamados a servicios externos dependiendo del tipo" (RF-20): Core para débitos/créditos y la **red interbancaria** para otras entidades (S-22).

Restricciones y fuerzas:
- Core on-premise, **no se modifica** (R-03), capacidad estimada **~300 TPS de consultas** y ventanas *batch* nocturnas (S-18). El canal digital, a 1.000 req/s de diseño (S-06), lo saturaría si cada consulta llegara al Core.
- El volumen de movimientos es grande: ~120 M/mes, 13 meses en línea (S-08) ⇒ ~1.560 M registros consultables.
- Objetivo p95: primera página de movimientos ≤ 800 ms (S-13).
- El modelo de datos del Core (códigos, estructuras heredadas) no debe filtrarse a los dominios nuevos (RNF-10).
- Sistema complementario con disponibilidad menor (~99,5 %, S-19).

Preguntas: (a) ¿cómo aislar el modelo y proteger la capacidad de los sistemas externos? (b) ¿qué conectividad nube ↔ on-premise? (c) ¿se consultan los movimientos al Core o se separan las lecturas?

## 2. Opciones evaluadas

### 2.1 Ubicación de la capa anticorrupción (ACL)

| Criterio (peso) | A. ACL como librería en cada servicio | B. Servicios ACL dedicados por sistema externo | C. Reutilizar ESB on-premise existente |
|---|---|---|---|
| Protección de capacidad del Core (presupuesto global de TPS) (25) | 2 | 5 | 4 |
| Aislamiento del modelo externo (20) | 3 | 5 | 3 |
| Aislamiento de fallas (*bulkhead* por sistema) (20) | 3 | 4 | 2 |
| Latencia (saltos adicionales) (15) | 5 | 4 | 3 |
| Operación (servicios a mantener) (20) | 4 | 3 | 3 |
| **Puntaje ponderado** | **3,25** | **4,25** | **3,05** |

### 2.2 Conectividad híbrida

| Opción | Pros | Contras |
|---|---|---|
| Solo VPN Site-to-Site sobre Internet | Barata, rápida de montar | Latencia y *jitter* variables, ancho de banda limitado por túnel (~1,25 Gbps); inadecuada para la ruta crítica |
| 1 Direct Connect + VPN de respaldo | Latencia estable | El DX único es punto único de falla físico |
| **2 Direct Connect en ubicaciones distintas + VPN de respaldo** (S-12) | Modelo de alta resiliencia recomendado por AWS; latencia estable; *failover* BGP automático | Costo de puertos y *cross-connects*; plazo de aprovisionamiento con el *carrier* |

### 2.3 Modelo de lectura de movimientos

| Criterio (peso) | C1. Consulta directa al Core (+ caché) | C2. CQRS: réplica de lectura alimentada por CDC | C3. Carga *batch* nocturna + día en curso al Core |
|---|---|---|---|
| Carga sobre el Core (25) | 1 | 5 | 4 |
| Frescura del dato (20) | 5 | 4 (segundos) | 2 |
| Latencia p95 y escalabilidad (20) | 2 | 5 | 4 |
| Complejidad (15) | 5 | 2 | 3 |
| Dependencia de capacidades del Core sin modificarlo (20) | 5 | 3 (lectura de *logs*) | 4 |
| **Puntaje ponderado** | **3,40** | **3,95** | **3,45** |

- **C1**: simple y siempre fresco, pero cada consulta de histórico (las más pesadas, con rangos de fechas) consume la capacidad finita del Core y hereda su latencia y sus ventanas *batch*.
- **C2**: lecturas desde un almacén optimizado para el patrón de acceso (cuenta + rango de fechas, paginado), aisladas del Core; frescura de segundos. Contra: consistencia eventual, *pipeline* CDC que operar y necesidad de leer el *log* transaccional del Core.
- **C3**: reduce carga, pero los movimientos del día quedan con baja frescura o vuelven al Core.

## 3. Decisión

1. **ACL dedicados** (opción B), uno por sistema externo, desplegados como servicios ECS separados (*bulkhead* físico): `acl-core`, `acl-complementario`, `acl-interbancario`. Cada uno:
   - Traduce el modelo y protocolo externo (REST/SOAP/mensajería del Core; formato del sistema de pagos: ISO 20022 `pacs.008`/`pacs.002` para la RPI del BCE y formato del SPI, con una estrategia por riel — ver ADR-009 §3.4) a un contrato interno canónico.
   - Aplica **presupuesto de capacidad**: `acl-core` limita el canal digital a una fracción acordada de la capacidad del Core (p. ej. ≤ 60 % de 300 TPS) con *rate limiter* y **pools separados** para consultas y contabilizaciones (las consultas nunca agotan las conexiones de débito/crédito).
   - Implementa *timeout*, reintento con *backoff* + *jitter* solo en operaciones idempotentes, *circuit breaker* (ADR-013) y traza cada llamada.
2. **Conectividad**: 2 AWS Direct Connect en ubicaciones distintas + VPN Site-to-Site de respaldo, terminando en un **Direct Connect Gateway** asociado a un Transit Gateway en `us-east-1` y en `us-east-2` (el DR llega al Core sin nuevos enlaces). Cifrado: **mTLS de aplicación** en todas las llamadas (DX no cifra por defecto); **MACsec** (si las conexiones son dedicadas de 10 Gbps) o **VPN IPsec sobre DX** como capa adicional obligatoria (`02-seguridad-y-normativa.md` §2.6).
3. **Movimientos: CQRS con réplica alimentada por CDC** (opción C2):
   - **AWS DMS** en modo CDC lee el *log* transaccional de una réplica/standby de la BD del Core (sin cambios en el Core; supuesto nuevo **S-27**) y publica a **Kinesis Data Streams** (clave de partición = número de cuenta, orden por cuenta).
   - El **proyector** de `svc-cuentas` (tarea *worker*) traduce (ACL) y escribe en **DynamoDB `movimientos`** (PK = `cuentaId`, SK = `fechaContable#secuencia`, TTL de 13 meses, S-08) y publica `MovimientoRegistrado` al bus (dispara invalidación de caché y notificaciones de créditos recibidos).
   - **Carga inicial** de 13 meses con DMS *full load*.
   - **Salvaguardas de frescura**: si el *lag* del CDC supera 60 s o la réplica no responde, `svc-cuentas` conmuta (vía *circuit breaker*) a consulta directa al Core por `acl-core` para la primera página, con límite estricto; el BFF fusiona las **operaciones en curso** de `svc-transferencias` para garantizar *read-your-writes* al cliente.
   - **Saldos disponibles**: siempre del Core vía `acl-core`, con caché de vida corta (≤ 30 s) e invalidación por evento (ADR-011).
   - Si BP no autoriza la lectura de *logs* (S-27), alternativa declarada: eventos publicados por el Core en su mensajería existente o C3.
4. **Sistema complementario**: solo bajo demanda (vista de detalle, RF-06) vía `acl-complementario`, con *cache-aside* de 24 h y degradación elegante (se muestra la vista básica si el sistema no responde).

Costo orientativo del *pipeline* (estimación de costos): DynamoDB *on-demand* ~120 M escrituras/mes ⇒ decenas de USD/mes en escrituras más ~0,5 TB de almacenamiento ⇒ ~USD 120–150/mes; DMS (instancia Multi-AZ) y Kinesis *on-demand* del orden de cientos de USD/mes; Direct Connect (2 puertos + transferencia) del orden de USD 1.000–2.000/mes según *carrier*.

## 4. Justificación teórica

1. **Anti-Corruption Layer** — Evans, *Domain-Driven Design* (2003), cap. 14: una capa de traducción protege el modelo del dominio nuevo de los conceptos de un sistema heredado; reforzado por Newman, *Monolith to Microservices* (2019) para integrar sin modificar el sistema de registro.
2. **CQRS y vistas materializadas** — Greg Young / Martin Fowler, "CQRS" (2011) y Kleppmann, *Designing Data-Intensive Applications* (2017), cap. 11 (*Change Data Capture* y flujos derivados): separar el modelo de lectura y alimentarlo desde el *log* del sistema de registro permite escalar lecturas sin cargar la fuente y con un esquema optimizado para la consulta.
3. **Bulkhead y protección de capacidad** — Nygard, *Release It!* 2.ª ed. (2018): *Bulkheads*, *Timeouts*, *Circuit Breaker*; un sistema heredado de capacidad finita debe protegerse contra el tráfico de los canales nuevos (patrón *Throttling* del Azure Architecture Center).
4. **AWS Well-Architected, pilar de Fiabilidad (REL02: planificar la topología de red)** y "AWS Direct Connect Resiliency Recommendations": conexiones redundantes en ubicaciones distintas para cargas críticas.
5. **PACELC** (Abadi, 2012): en el histórico se acepta consistencia eventual (segundos) a cambio de latencia baja y disponibilidad; en saldos y débitos se exige el dato del Core.

## 5. Consecuencias

**Positivas:** el histórico (la consulta más pesada) deja de cargar al Core; p95 de movimientos sostenible en picos; el dominio usa un modelo limpio; cambiar el Core en el futuro afecta solo a `acl-core` y al proyector (habilita *Strangler Fig*).

**Negativas / trade-offs:** consistencia eventual del histórico (mitigada con *read-your-writes*); más componentes (DMS, Kinesis, proyector); dependencia de la autorización de lectura de *logs* del Core.

| Riesgo | Mitigación |
|---|---|
| Lectura de *logs* del Core no autorizada o motor no soportado (S-27) | Alternativas declaradas (eventos del Core o *batch*); decisión revisable |
| *Lag* del CDC en cierre *batch* nocturno | Métrica `IteratorAge`/*lag* con alarma; conmutación a consulta directa; escalado del proyector |
| Divergencia réplica vs. Core | Conciliación diaria por conteo/suma por cuenta; re-proyección desde Kinesis (retención 7 días) o recarga DMS |
| Caída de ambos DX | VPN de respaldo; degradación a "solo consultas desde réplica/caché" con aviso al cliente |
| `acl-*` como cuello de botella | Autoscaling, *pools* separados, *bulkhead* por sistema externo |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Anti-Corruption Layer, Adapter, Facade | `acl-core`, `acl-complementario`, `acl-interbancario` |
| CQRS, Materialized View | `svc-cuentas` (DynamoDB `movimientos`) |
| Change Data Capture / Event-Carried State Transfer | DMS → Kinesis → proyector |
| Bulkhead, Rate Limiting, Circuit Breaker, Retry + Jitter, Timeout | `acl-*` |
| Graceful Degradation | Detalle complementario y conmutación a Core |
| Strangler Fig (habilitado) | Sustitución futura del Core detrás de `acl-core` |

## 7. Equivalente Azure

ExpressRoute (2 *peering locations*) + VPN Gateway; Azure Database Migration Service / Debezium en Event Hubs para CDC; Azure Cosmos DB para el modelo de lectura.

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RF-05..07, RF-20 | Contexto (sistemas externos) · Contenedores (`acl-*`) | §4 · §5 · §10 Integración |
| RF-01, RF-19, RNF-12 | Componentes `svc-cuentas` (proyector, repositorio) · secuencia "consulta con caché" | §6 · §7 · §9 Datos |
| S-12, RNF-09 | Infraestructura (DX, DXGW, TGW, VPN) | §11 Nube |
| RNF-03 | Componentes `acl-core` (clientes resilientes) | §12 Resiliencia |
