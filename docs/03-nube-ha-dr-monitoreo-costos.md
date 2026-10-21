# 03 · Nube AWS, alta disponibilidad, recuperación ante desastres, monitoreo y costos

> **Proyecto:** Banca por internet BP (reto de arquitectura de solución).
> **Contenido:** nube, HA/DR, monitoreo y costos.
> **Fecha:** 2026-10-18 · **Estado:** versión final; decisiones de diseño adoptadas en §10.
> **Entradas:** [`01-requisitos.md`](01-requisitos.md) (S-03..S-17), [`adr/`](adr/README.md) (ADR-001, 002, 003, 004, 009, 011, 012, 013, 014), [`02-seguridad-y-normativa.md`](02-seguridad-y-normativa.md) (§2 perímetro, §3 llaves, §5 dispositivos, §9 supuestos).
> **Rúbrica cubierta:** #8 Nube · #9 Costos · #14 HA y tolerancia a fallos · #15 Monitoreo (aporta a #1, #3, #13).
> **Revisión de infraestructura (2026-10-18):** revisión favorable con correcciones; aplicadas C1, C2, A1–A5, M1, M3–M5, B1–B4 (B4 como condicional). M2 se incorporó como modo degradado *fail-static* (§10).
> **Detalle de costos:** [`costos/estimacion-costos.csv`](costos/estimacion-costos.csv) (una fila por servicio con supuesto, precio unitario, fuente y elasticidad).

## Convenciones

| Marca | Significado |
|---|---|
| **[V]** | Precio leído el **2026-10-18** en la **AWS Price List API pública** (archivos `offers/v1.0/aws/<servicio>/current/<región>/index.json`; la fecha de publicación de cada archivo va en el CSV). |
| **[S]** | Precio de página pública de AWS citado por fuentes secundarias; no figura en la Price List. |
| **[E]** | Estimado por conocimiento previo de la página pública de precios o por cálculo propio; **validar en AWS Pricing Calculator** antes de comprometer presupuesto. |

Los MCP `aws-pricing` y `aws-documentation` no conectaron en esta sesión; por eso se usó la Price List pública. Ningún precio de este documento se obtuvo con credenciales AWS.

---

## 1. Topología AWS objetivo

### 1.1 Vista en una página

```
                         Internet (clientes en Ecuador y exterior)
                                         │ HTTPS TLS 1.3 (1.2 mín.)
                 Route 53 (DNS, health checks) ── Route 53 ARC (routing controls)
                                         │
             CloudFront (SPA S3/OAC + API) + AWS WAF (Bot Control, ATP, ACFP) + Shield Advanced
                                         │ HTTPS + cabecera secreta de origen
 ┌──────────────────────────── us-east-1 (principal, 3 AZ) ─────────────────────────────┐
 │ API Gateway REST regional (WAF regional, throttling, Lambda authorizer JWT+DPoP)     │
 │      │ VPC Link V2 (directo a ALB, sin NLB)                                           │
 │ VPC prod 10.10.0.0/16: integración (ALB interno) · app (ECS Fargate ARM64)           │
 │      · datos (Aurora, ElastiCache) · endpoints PrivateLink                           │
 │ Servicios regionales: DynamoDB, Kinesis, Firehose, S3, EventBridge, SQS, Step Func.  │
 │      │ Transit Gateway ── VPC de inspección (Network Firewall + NAT) ── Internet      │
 │      │ (egress con lista blanca: IDV, SMS/correo, FCM/APNs, atestación)              │
 └──────┼───────────────────────────────────────────────────────────────────────────────┘
        │ DX Gateway (global) ── 2 × Direct Connect 1 Gbps (private IP VPN IPsec sobre DX) + VPN por Internet
        ↓
   2 routers CGW (uno por circuito/carrier) ── CPD de BP (on-premise, 10.200.0.0/16 de ejemplo): Core · sistema complementario · conector de pagos BCE · IdP (si es on-prem)

 ┌──────────────── us-east-2 (DR warm standby, 2 AZ activas + 1 de reserva) ────────────┐
 │ Mismo IaC: API GW, ALB, ECS a capacidad mínima · Aurora Global (secundaria) ·        │
 │ DynamoDB Global Tables · ElastiCache Global Datastore (seguridad) · S3 CRR (WORM)    │
 │ VPC 10.20.0.0/16 · TGW propio con sus private IP VPN sobre DX y VPN por Internet ·   │
 │ mismo DX Gateway · TGW peering con us-east-1 (no se depende de él en el desastre)    │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Organización multi-cuenta (AWS Organizations)

Alineado con `02-seguridad-y-normativa.md` §2.3 (S-17), con **un ajuste** (§10, cuentas y DR): la región de DR vive en la **misma cuenta `prod`** y no en una cuenta `dr` separada. Motivo: **Aurora Global Database no admite secundarias en otra cuenta** (solo replicación *cross-account* por *snapshots*, RPO de horas, incumple Tier 1). **DynamoDB sí admite Global Tables multi-cuenta**, pero exige configuración unificada y no permite convertir tablas existentes; partir los datos de un mismo *bounded context* entre dos cuentas complica el *failover* sin aportar aislamiento real. Además, **VPC Link V2 exige API, VPC link y balanceador en la misma cuenta**. El aislamiento contra *ransomware* o borrado malicioso lo da la cuenta `backup-vault` (bóveda *logically air-gapped*) + SCP.

| OU | Cuenta | Propósito | Regiones |
|---|---|---|---|
| Root | `management` | Facturación consolidada, SCP, IAM Identity Center; **sin cargas** | — |
| Security | `log-archive` | CloudTrail organizacional, auditoría WORM (ADR-012), *logs* de VPC/WAF; SCP que niega borrar o desactivar Object Lock | us-east-1 + réplica us-east-2 |
| Security | `security-tooling` | Administrador delegado de GuardDuty, Security Hub, Inspector, Macie, Detective, Config agregador | ambas |
| Infrastructure | `network` | Transit Gateway, DX Gateway, VPC de inspección (Network Firewall, NAT), Route 53 Resolver, zonas privadas, ARC | ambas |
| Infrastructure | `shared-services` | CI/CD (GitHub Actions con OIDC), ECR con replicación entre regiones, artefactos, AMI/imágenes base firmadas | ambas |
| Infrastructure | `observability` | CloudWatch *cross-account observability* (monitoreo central), Managed Grafana, X-Ray, Synthetics (ADR-014) | ambas |
| Workloads | `prod` | Producción: us-east-1 activa + us-east-2 *warm standby* | ambas |
| Workloads | `staging`, `qa`, `dev` | Pre-producción y no productivos (fuera del presupuesto S-16, ver §5.6) | us-east-1 |
| Recovery | `backup-vault` | AWS Backup con **bóveda *logically air-gapped*** y copias entre cuentas/regiones de Aurora, DynamoDB y S3 crítico | ambas |

SCP mínimas: las de 02 §2.3 (solo `us-east-1`/`us-east-2`, no desactivar CloudTrail/GuardDuty/Config/Security Hub, no tocar Object Lock, no borrar llaves KMS sin *break-glass*, no S3 público, no salir de la organización).

### 1.3 Red por cuenta y región (CIDR de ejemplo)

| VPC | Región | CIDR | Subredes (×3 AZ en us-east-1, ×3 en us-east-2 con 2 activas) |
|---|---|---|---|
| `prod-use1` | us-east-1 | 10.10.0.0/16 | **integración** 10.10.0.0/24, .1.0/24, .2.0/24 (ALB interno, destino del VPC Link V2) · **app** 10.10.16.0/20, .32.0/20, .48.0/20 (tareas Fargate, Lambda en VPC) · **datos** 10.10.64.0/22, .68.0/22, .72.0/22 (Aurora, ElastiCache; sin ruta a Internet) · **endpoints** 10.10.80.0/24, .81.0/24, .82.0/24 · **TGW** 10.10.96.0/28 ×3 |
| `prod-use2` | us-east-2 | 10.20.0.0/16 | Misma plantilla (10.20.x) |
| `inspeccion-use1` | us-east-1 (cuenta `network`) | 10.9.0.0/22 | **pública** (NAT) 10.9.0.0/26 ×3 · **firewall** 10.9.1.0/26 ×3 · **TGW** 10.9.2.0/28 ×3 · adjunto TGW con **`ApplianceModeSupport=enable`** |
| `inspeccion-use2` | us-east-2 | 10.17.0.0/22 | Misma plantilla (appliance mode activo) |
| CIDR del TGW (direcciones externas de *private IP VPN*) | us-east-1 / us-east-2 | 10.9.255.0/24 / 10.19.255.0/24 | Anunciados como *allowed prefixes* de la asociación DXGW–TGW |
| `staging` / `qa` / `dev` | us-east-1 | 10.24.0.0/16 / 10.25.0.0/16 / 10.26.0.0/16 | Misma plantilla, sin DX directo a producción del Core (usa ambientes de prueba del Core) |
| CPD de BP | on-premise | 10.200.0.0/16 (ejemplo) | Rutas anunciadas por BGP solo hacia las subredes **app** de producción |

Planificación con **VPC IPAM**: superbloques por región para sumarizar hacia on-prem y no chocar con el 10.0.0.0/8 del CPD (los rangos bajos como 10.0.0.0/22 suelen estar ocupados): **10.8.0.0/13** (us-east-1 prod y red), **10.16.0.0/13** (us-east-2), **10.24.0.0/13** (no productivos).

**Tablas de ruta del TGW:**

| Tabla | Asociada a | Rutas |
|---|---|---|
| `prod-rt` | `prod-use1` | 0.0.0.0/0 → inspección; prefijos on-prem de **Core prod** → adjuntos *private IP VPN*; 10.16.0.0/13 → peering (estática: el peering no propaga) |
| `noprod-rt` | `staging`, `qa`, `dev` | 0.0.0.0/0 → inspección; **solo** prefijos de los ambientes de prueba del Core |
| `onprem-rt` | adjuntos VPN / DXGW | Propaga prod y no-prod separados por prefijo |
| `insp-rt` | VPC de inspección | Retorno a todas las VPC |

Network Firewall incluye una regla que **niega destinos RFC 1918** para que no-prod → inspección → prod no haga *hairpin*. BP debe separar en su CPD los prefijos de Core prod y Core de pruebas (el TGW solo filtra por prefijo). Las subredes app se anuncian a on-prem por el **BGP de los túneles**, no por el DXGW.

Reglas de enrutamiento: la VPC de carga **no tiene subredes públicas ni Internet Gateway**; toda salida va por TGW → VPC de inspección (Network Firewall con lista blanca por dominio + DNS Firewall) → NAT. El tráfico hacia on-premise va por TGW → DX Gateway, **nunca** por la VPC de inspección hacia Internet. Las tablas de rutas del TGW separan dominios (prod, no-prod, on-prem) para que `dev` no alcance el Core de producción.

### 1.4 Conectividad híbrida

| Elemento | Diseño | Cifrado | Motivo |
|---|---|---|---|
| Direct Connect | **2 conexiones dedicadas de 1 Gbps** en **dos ubicaciones DX distintas** con dos *carriers* (modelo de resiliencia *High* de AWS), *transit VIF* hacia el DXGW | **IPsec** (*private IP VPN* sobre DX, terminada en TGW): **2 conexiones en el TGW de us-east-1 y otras 2 en el de us-east-2** (el DR no depende del TGW de us-east-1 vía peering) | El consumo real hacia el Core es bajo (≈ 300 TPS × 5 KB ≈ 12 Mbps pico + CDC); 1 Gbps sobra. **MACsec solo existe en dedicadas de 10/100/400 Gbps** en ubicaciones seleccionadas, no en *hosted* ni en 1 Gbps **[V]**: ~USD 2.850/mes más (USD 2,25/h vs 0,30/h por puerto **[V]**). Ver §10 (enlaces DX) |
| MACsec (alternativa) | Si BP ya tiene o requiere 10 Gbps dedicados en una ubicación compatible | MACsec 802.1AE (L2) + mTLS | Decisión adoptada: "MACsec **o** IPsec sobre DX" |
| VPN Site-to-Site por Internet | 2 túneles por *customer gateway* hacia el TGW **de cada región**, BGP con menor preferencia | IPsec | Respaldo si caen ambos DX (degradado: mayor latencia) |
| *Customer gateways* | **2 routers CGW on-prem** (uno por circuito/*carrier*); elimina el punto único de falla del router de borde | — | La disponibilidad del par la limitan los circuitos y los CGW, no AWS (§2.2) |
| MTU / MSS | MTU del túnel 1446 / MSS 1406 (AES-GCM sin NAT-T); **clamping de MSS en el CGW (≈ 1360–1379)** y fragmentar antes de cifrar; no depender de PMTUD (las tareas en VPC usan MTU 9001) | — | Evita cuelgues por fragmentación |
| DX Gateway | Uno, global, asociado a los TGW de **us-east-1 y us-east-2** | — | El DR alcanza el Core sin nuevos circuitos (ADR-004) |
| Trade-offs de IPsec sobre DX (§10, enlaces DX) | MTU 1446 y *clamping* de MSS; conmutación de túnel ≈ 30 s (no < 1 s); ≤ 1,25 Gbps por túnel (suficiente); 2 conexiones *private IP VPN* por TGW, también en us-east-2 | — | Aceptados frente al sobrecosto de MACsec |
| Capa de aplicación | `acl-core`, `acl-complementario`, `acl-interbancario` → on-prem con **mTLS** (certificados de AWS Private CA de propósito general o PKI de BP) | TLS 1.3 mutuo | Doble capa (02 §2.5–2.6) |
| BGP | **Dos capas**: (1) *underlay* (*transit VIF*) con **BFD** (< 1 s) y comunidades de *local preference* 7224:7300 / 7224:7200 para preferir DX-1 sobre DX-2; (2) **BGP de los túneles** (rutas de aplicación) **sin BFD**: detección por DPD / *hold timer* ≈ 30 s. Preferencia DX-1 > DX-2 > VPN entre túneles con AS-PATH *prepend* en el CGW (AWS → on-prem) y **ECMP VPN del TGW desactivado** (o activo/activo aceptando pérdida parcial durante la detección) | — | Conmutación automática de enlace; convergencia medida por capa |

### 1.5 Borde

| Servicio | Configuración | Entornos |
|---|---|---|
| Route 53 | Zonas públicas (`bp.example` de ejemplo) y privadas; registros de **conmutación** gobernados por **Route 53 ARC** (*routing controls* con *safety rules*) para `api.` y `web.`; *health checks* HTTPS a los *canaries* | Todos (ARC solo prod) |
| CloudFront | Una distribución para la SPA (origen S3 con OAC) y otra para la API (origen API Gateway regional, sin caché para rutas autenticadas, *origin failover* no usado para escrituras); TLS 1.2 mínimo / 1.3 preferido, HSTS, *response headers policy* | Todos |
| AWS WAF | Web ACL global (CloudFront) y regional (API Gateway): reglas gestionadas, *rate-based*, **Bot Control *targeted*** en `/login`, `/par`, `/register`, `/onboarding`, **ATP** en el login web, **ACFP** en el inicio de onboarding (02 §2.1) | Todos (Bot/ATP/ACFP solo prod y staging) |
| **Shield Advanced** | Suscripción organizacional; recursos protegidos: **CloudFront y Route 53** (API Gateway no es protegible directamente y no hay balanceadores públicos); SRT 24×7, protección de costos. El WAF regional del API Gateway **no** queda exento por Shield y se cobra aparte | **Solo producción** (decisión adoptada) |

### 1.6 Cómputo: dónde corre cada contenedor (ECS Fargate ARM64)

Un **clúster ECS por región** (`bp-prod-use1`, `bp-prod-use2`), *namespace* de **Service Connect** `bp.internal` (TLS 1.3 con AWS Private CA en modo vida corta), tareas en subredes **app** de las 3 AZ, *capacity provider* **Fargate On-Demand** (Spot solo en no productivos), *deployment circuit breaker* con *rollback*, *health checks* `/actuator/health/liveness|readiness`. Cada tarea lleva un **sidecar ADOT** (0,125 vCPU / 0,25 GB) para OpenTelemetry y el **proxy de Service Connect** que inyecta ECS (AWS recomienda +0,25 vCPU y ≥ 64 MiB por tarea; 0,5 vCPU si > 500 req/s). **Tamaño de tarea = app + ADOT + proxy Service Connect, redondeado a un tamaño válido de Fargate** (CPU 0,25/0,5/1/2/4/8/16 vCPU con memorias discretas): servicios de app 0,5 vCPU ⇒ tarea **1 vCPU / 2 GB**; servicios de app 1 vCPU ⇒ tarea **2 vCPU / 4 GB**. Imágenes en ECR replicado a us-east-2 y firmadas.

Arranque de la JVM: Spring Boot + agente OpenTelemetry tarda 30–60 s; se ajusta el ***health check grace period*** del servicio ECS (≥ 90 s) y se evalúa **CDS/AppCDS** para reducir el arranque (el paso a ≥ 1 vCPU por tarea ya lo mitiga).

| Servicio (ADR-001 + seguridad) | Tier | App vCPU / GB → **tarea** | Mín | Máx | Prom. (para costo) | Escala por | Acceso entrante permitido (SG) |
|---|---|---|---|---|---|---|---|
| `bff-web` | 1 | 1 / 2 → **2 / 4** | 3 | 12 | 4 | Solicitudes por *target* (ALB) | ALB interno |
| `bff-movil` (atestación, registro DCR, sesión 2 min) | 1 | 1 / 2 → **2 / 4** | 3 | 20 | 6 | Solicitudes por *target* | ALB interno |
| **`svc-dispositivos`** (nuevo, §10) | 1 | 0,5 / 1 → **1 / 2** | 3 | 8 | 3 | CPU | `bff-movil`, `bff-web` (gestión "Mis dispositivos"), centro de contacto |
| `svc-clientes` | 2 | 0,5 / 1 → **1 / 2** | 3 | 10 | 3 | CPU | BFF |
| `svc-cuentas` (API de lectura CQRS) | 2 | 1 / 2 → **2 / 4** | 3 | 12 | 4 | CPU / solicitudes | BFF |
| `svc-cuentas-proyector` (consumidor Kinesis CDC) | 2 | 0,5 / 1 → **1 / 2** | 2 | 6 | 2 | `IteratorAge` / *shards* | — (solo saliente) |
| `svc-transferencias` (API) | 1 | 1 / 2 → **2 / 4** | 3 | 12 | 4 | CPU / solicitudes | BFF |
| `svc-transferencias-worker` (relay de outbox, *workers* de la saga) | 1 | 0,5 / 1 → **1 / 2** | 2 | 8 | 2 | Profundidad SQS de tareas | — |
| `svc-notificaciones` | 2 | 0,5 / 1 → **1 / 2** | 2 | 8 | 2 | Profundidad SQS | — |
| `svc-auditoria` (consumidor Kinesis, cadena de *hashes*) | 1 | 0,5 / 1 → **1 / 2** | 3 | 10 | 3 | `IteratorAge` | — |
| `svc-onboarding` | 2 | 1 / 2 → **2 / 4** | 2 | 6 | 2 | CPU | `bff-movil` |
| `acl-core` | 1 | 1 / 2 → **2 / 4** | 3 | 12 | 4 | CPU + concurrencia; **techo por presupuesto de TPS del Core** | `svc-*` declarados |
| `acl-complementario` | 2 | 0,5 / 1 → **1 / 2** | 2 | 6 | 2 | CPU | `svc-clientes` |
| `acl-interbancario` (estrategias RPI/SPI, ISO 20022) | 1 | 0,5 / 1 → **1 / 2** | 3 | 8 | 3 | Profundidad SQS de tareas | `svc-transferencias-worker` |
| **Total promedio** | | | **37** | **138** | **44 tareas ≈ 68 vCPU / 136 GB** (20 × 1 vCPU + 24 × 2 vCPU, tamaños válidos Fargate) | | |

Los máximos pueden bajarse en la prueba de carga (una tarea de 2 vCPU deja ~1,6 vCPU útiles a la app), conservando **mínimo 3 por servicio Tier 1**.

Fuera de ECS: **Lambda** ARM64 para el *authorizer* de API Gateway (en VPC, para leer la caché anti-*replay* de DPoP y la **lista de denegación**; las ENI *Hyperplane* se crean al configurar la función, no por invocación; concurrencia esperada ≈ 1.000 req/s × 20–40 ms ≈ 20–40: **concurrencia reservada 200** (sin costo, evita que otras Lambdas la dejen sin capacidad), cuota regional elevada en ambas regiones, *runtime* **Node.js/Go/Rust** (no Java), cliente Valkey con *timeout* ≤ 30 ms, JWKS y validación del JWT cacheados en memoria por *hash* de token; por solicitud solo se verifica la prueba DPoP (`htm`/`htu`/`iat` ± 60 s/`jti`). Alternativa viable no adoptada: `jti` en DynamoDB, ≈ USD 75/mes), el sellado horario de auditoría (ADR-012) y *jobs* programados (limpieza de instancias huérfanas, conciliación). Mínimos de 3 tareas en Tier 1 garantizan una tarea por AZ; el máximo cubre el pico de diseño de 1.000 req/s (S-06) con margen ≥ 2× (RNF-15).

Decisión de §10 (**`svc-dispositivos`**): el registro y la política de dispositivos (máx. **3** por cliente, enfriamiento de 24 h, revocación por autoservicio/centro de contacto/inactividad de 180 días, 02 §5) son **reglas de negocio** del contexto Identidad; ADR-003 prohíbe reglas de dominio en los BFF. Por eso viven en un servicio propio con tabla DynamoDB `dispositivos` (Global Table) y escriben la **lista de denegación** (`client_id`/`jkt` revocados, TTL = vida del *access token*) en el clúster ElastiCache de **seguridad**, que consulta el *authorizer*. `bff-movil` conserva lo propio del canal: validar la atestación (App Attest / Play Integrity) y orquestar el registro DCR contra el IdP, pidiendo a `svc-dispositivos` la autorización de alta.

### 1.7 Datos y servicios regionales

| Componente | Configuración en us-east-1 | En us-east-2 (DR) | Dueño |
|---|---|---|---|
| **Aurora PostgreSQL** `transferencias` | Clúster Tier 1: escritor + lector `db.r8g.large` en AZ distintas, cifrado KMS, IAM auth, *backtrack* no (PostgreSQL), PITR 35 días | **Aurora Global Database** secundaria (1 `db.r8g.large`), RPO típico ≈ 1 s | `svc-transferencias` |
| **Aurora PostgreSQL** `plataforma` | Escritor + lector **`db.r8g.large` aprovisionados**; bases lógicas por servicio (`onboarding`). (Serverless v2 se descartó: en una Global Database AWS recomienda mínimo 8 ACU en el primario, ≈ USD 1.400/mes) | Global Database con secundaria ***headless*** (sin instancias; se agrega una al conmutar, compatible con RTO Tier 2 de 1 h) | `svc-onboarding` |
| **DynamoDB** | `movimientos` (CQRS, TTL 13 meses), `perfil-digital`, `notificaciones`, `preferencias`, `dispositivos`; *on-demand*, PITR, cifrado KMS | **Global Tables** (réplica activa de lectura; escritura solo desde la región activa por convención) | `svc-cuentas`, `svc-clientes`, `svc-notificaciones`, `svc-dispositivos` |
| **ElastiCache for Valkey — datos** | *Cluster mode*, 2 *shards* × (primario + réplica) `cache.r7g.large`, Multi-AZ, TLS + AUTH/RBAC | **Frío** (se recalienta con *Refresh-Ahead*, ADR-011) | `svc-clientes`, `svc-cuentas` |
| **ElastiCache for Valkey — seguridad** | 1 *shard* × 3 nodos `cache.m7g.large` (uno por AZ): sesiones cifradas de `bff-web`, caché anti-*replay* `jti` de DPoP, **lista de denegación de instancias** | **Global Datastore** (2 nodos) para que la lista de denegación y las sesiones sobrevivan al *failover* | `bff-web`, *authorizer*, `svc-dispositivos` |
| **S3** | `spa-web` (OAC), `kyc-evidencias` (KMS dedicada, Macie), `auditoria` en `log-archive` (**Object Lock Compliance 10 años**, Parquet), `logs` (VPC/WAF) | **CRR** con Object Lock (auditoría y KYC); SPA replicada | Varios |
| **S3 Glacier Deep Archive** | Ciclo de vida: auditoría > 12 meses → Deep Archive hasta 10 años; registros **seudonimizados** (02 §9.1) | Copia replicada | `svc-auditoria` |
| **Kinesis Data Streams** (*on-demand*) | `cdc-movimientos` (partición = cuenta) y `auditoria` (partición = cliente), retención 7 días | Streams creados en espera | `svc-cuentas`, `svc-auditoria` |
| **AWS DMS** | Instancia Multi-AZ CDC desde la réplica del Core (S-27) | **Frío**: se lanza por IaC al conmutar (Tier 2, RTO 1 h) | `svc-cuentas` |
| **Data Firehose** | `auditoria` → S3 Parquet particionado por fecha | Igual, en espera | `svc-auditoria` |
| **Athena + Glue + Lake Formation** | Consulta de auditoría con columnas PII enmascaradas | Sobre la réplica | Auditor/operador |
| **EventBridge** | Bus `bp-dominio` + *schema registry*; **Pipes** desde DynamoDB Streams: `movimientos` (`MovimientoRegistrado`) y `dispositivos` (`auth.device.*`) | Bus y reglas desplegados; **los dos Pipes desplegados y DETENIDOS** (la replicación de Global Tables escribe en el *stream* de cada réplica: con Pipes activos en ambas regiones cada evento se publicaría dos veces) | Todos |
| **SQS** | Una cola + **DLQ** por consumidor; colas de tareas de la saga (`.waitForTaskToken`) | Desplegadas vacías | Consumidores |
| **Step Functions Standard** | Saga interbancaria con `TimeoutSeconds` **por riel (RPI/SPI)** (ADR-009 §3.4) | Máquina desplegada; **rehidratación** de sagas `EN_PROCESO` al conmutar (ADR-013) | `svc-transferencias` |
| **Notificaciones** | AWS End User Messaging (SMS, *push*) + SES (correo) + proveedor alterno externo vía egress controlado (ADR-010) | Configurados | `svc-notificaciones` |
| **KMS / Secrets Manager** | Llaves por dominio (02 §3.2), **llaves multi-región** para datos replicados; secretos con réplica | Réplicas | Por servicio |

Decisiones de seguridad reflejadas: **KMS** para todas las llaves (CloudHSM solo si BP/supervisor lo exige, vía *custom key store*); **TLS 1.3 interno + JWT** (Service Connect) y **mTLS solo hacia on-prem**; **canal fuera de alcance PCI** (sin PAN completo en ningún almacén de esta topología); retención **10 años WORM seudonimizada**; **máx. 3 dispositivos** (en `svc-dispositivos`); **Shield Advanced solo prod**; **MACsec/IPsec sobre DX**.

---

## 2. Alta disponibilidad y tolerancia a fallos

### 2.1 Modos de falla → detección → reacción automática → efecto al cliente

| # | Modo de falla | Detección | Reacción automática (auto-healing) | Efecto visible al cliente | RTO / RPO |
|---|---|---|---|---|---|
| F1 | Tarea Fargate cae o se cuelga | *Health check* de contenedor y de ALB (*liveness*/*readiness*) cada 10–30 s | ECS reemplaza la tarea; ALB/Service Connect la saca del balanceo; autoscaling repone capacidad | Ninguno (otras 2+ tareas en otras AZ); reintento transparente de lecturas | Segundos / 0 |
| F2 | Despliegue defectuoso | *Deployment circuit breaker* + alarmas de *canary* / tasa de error | *Rollback* automático a la revisión anterior | Ninguno o errores en < 1 % durante minutos | < 10 min / 0 |
| F3 | Caída de una **AZ** | Health checks, métricas por AZ, AWS Health | Tareas redistribuidas en 2 AZ; **Aurora failover** al lector (≈ 30 s **[E]**); ElastiCache promueve réplica; NFW/NAT/endpoints de las otras AZ | Errores transitorios < 1 min; latencia algo mayor | ≈ 1 min / 0 (Aurora replicación síncrona de almacenamiento) |
| F4 | Caída o degradación grave de la **región** us-east-1 | *Canaries* desde us-east-2, AWS Health, burn rate de todos los journeys | **Decisión humana** (runbook §3.1, ARC); no automático para evitar *flapping* y *split-brain* | Indisponibilidad hasta la conmutación; luego servicio en us-east-2 | **≤ 30 min Tier 1, ≤ 1 h Tier 2 / ≤ 1 min** (Aurora ≈ 1 s, DynamoDB segundos, S3 CRR minutos para auditoría ya sellada; eventos en vuelo de Kinesis se recuperan desde el outbox) |
| F5 | **Core** lento o caído | *Circuit breaker* de `acl-core` abierto, latencia p99, *synthetic* de saldo | *Bulkhead* (pools consulta/contabilización separados), *fail fast*, *load shedding* (prioridad transferencias > posición > histórico) | Movimientos desde DynamoDB (CQRS) con marca de frescura; **saldo** desde caché ≤ 30 s con aviso; transferencias **rechazadas con mensaje** ("intente más tarde") o en `EN_VERIFICACION` si hubo *timeout* | Depende de BP (S-18) / 0 en el canal |
| F6 | **IdP** caído | *Canary* de login, errores del *authorizer*, alarma específica (ADR-014) | JWKS en caché ⇒ **sesiones vigentes siguen funcionando** (validación local del JWT); *circuit breaker* hacia el IdP; página de estado | **No se puede iniciar sesión** ni renovar tras la expiración; usuarios ya autenticados operan hasta el fin de su sesión | Depende de BP (S-20) |
| F7 | Sistema complementario caído | *Circuit breaker* de `acl-complementario` | Cache-aside 24 h; respuesta parcial | Vista de detalle no disponible; vista básica sí | — / 0 |
| F8 | Red interbancaria (RPI/SPI) caída o lenta | *Circuit breaker* en `acl-interbancario`, *timeouts* de la saga | RPI → **SPI** solo antes de enviar; sagas esperan con *heartbeat*; consulta activa de estado antes de compensar; **reverso** idempotente si no hay acreditación | Interbancaria aceptada `202` con "se acreditará en el próximo ciclo"; nunca doble débito | — / 0 (estado en Aurora) |
| F9 | Proveedor de notificaciones o biometría caído | *Circuit breaker* por *Adapter*, tasa de entregas, tasa de liveness por versión de SDK | Proveedor alterno (Strategy + Adapter, ADR-010); en IDV, expediente queda `PENDIENTE_VERIFICACION` y se reintenta | Notificación por canal alterno (≥ 2 canales); onboarding diferido con aviso | — / 0 (cola SQS + DLQ) |
| F10 | Un enlace **Direct Connect** (o su CGW) cae | BFD en el *underlay*, DPD / *hold timer* en el túnel, `ConnectionState`, `TunnelState` | BGP converge al segundo DX; si caen ambos, a la VPN por Internet | Lecturas reintentan; escrituras idempotentes (referencia única) | **< 1 s capa DX (BFD); ≈ 30 s capa IPsec** (DPD / *hold* BGP del túnel) / 0 |
| F11 | ElastiCache de datos caído | *Circuit breaker* del `ClienteResiliente` | Lectura directa a la fuente con *rate limiter* del ACL | Latencia mayor, sin errores | Minutos / 0 (datos derivables) |
| F12 | ElastiCache de seguridad caído | Errores del *authorizer*; evento `auth.degraded_mode` | *Failover* a réplica (otra AZ). Si cae todo el clúster, el *authorizer* entra en **modo degradado *fail-static*** (§10, authorizer y modo degradado): aplica la **última copia en memoria de la lista de denegación** (refresco cada 15–30 s); Un entorno Lambda **nuevo** (arranque en frío) sin copia carga la lista desde el índice `revocadas-vigentes` de DynamoDB `dispositivos`; si tampoco responde, ese entorno queda *fail-closed*. **operaciones monetarias *fail-closed*** (por método + recurso, p. ej. `POST /transferencias*`); en lecturas solo se omite la verificación de `jti` con `iat` ≤ 60 s y tokens ≤ 5 min; el modo **expira a los 15 min** y pasa a *fail-closed* total salvo extensión manual; emite `auth.degraded_mode` (P1 + SOC) | Lecturas siguen; transferencias rechazadas con mensaje mientras dure; re-login en web solo si cae el clúster entero | Minutos / segundos |
| F13 | Lag de CDC > 60 s | `IteratorAge`, *lag* de DMS | Conmutación a lectura directa al Core (primera página) | Ninguno | — |
| F14 | Mensajes envenenados | DLQ > 0 | Aislamiento en DLQ, alarma, *redrive* manual tras corrección | Ninguno (o notificación tardía) | — / 0 |

### 2.2 Disponibilidad compuesta por journey

Método: componentes en **serie** se multiplican (A = ∏ Aᵢ); componentes **redundantes en paralelo** se combinan como 1 − ∏(1 − Aᵢ). Valores por componente = **disponibilidad de diseño estimada** **[E]** para despliegues multi-AZ gestionados (no los SLA contractuales, que son cotas inferiores y en varios servicios son 99,9–99,99 %). Dependencias de BP según supuestos: **IdP 99,95 %** (S-20/S-14), **Core 99,95 %** (S-14/S-18), IDV 99,9 %, Registro Civil 99,5 % **[E]**.

| Componente | A estimada | Nota |
|---|---|---|
| CloudFront, API Gateway, Lambda *authorizer*, DynamoDB, Step Functions/SQS | 99,995 % c/u | Servicios regionales/globales multi-AZ |
| ALB interno (VPC Link V2; el NLB se eliminó) | 99,995 % | Un salto menos |
| ElastiCache Multi-AZ, Aurora Multi-AZ | 99,99 % c/u | *Failover* de decenas de segundos |
| Servicio ECS con ≥ 3 tareas en 3 AZ | 99,995 % | Por servicio en la cadena |
| Par Direct Connect + VPN | ≈ 99,995 % | **Lo limitan los circuitos Ecuador → ubicación DX y los CGW, no AWS**: cada camino = circuito 99,5 % **[E]** × CGW 99,9 % × puerto DX 99,9 % ≈ 99,3 %; dos caminos en paralelo ≈ 99,995 % (la VPN por Internet comparte CGW) |

| Journey | Cadena (serie; ‖ = paralelo) | A compuesta | Min/mes sin servicio | SLO (S-14) | ¿Cumple? |
|---|---|---|---|---|---|
| Login | CF · APIGW · authorizer · ALB · BFF · ElastiCache seg. · `svc-dispositivos` · DynamoDB · **IdP** | **99,90 %** | ≈ 41 | **99,9 %** (§10, SLO) | **Al límite** con 99,9 %; con 99,95 % no: el IdP solo consume todo el presupuesto de error |
| Login (si IdP ≥ 99,99 %) | Ídem | 99,94 % | ≈ 24 | 99,95 % (meta si BP entrega IdP ≥ 99,99 %) | Al límite |
| Consulta de movimientos | CF · APIGW · authorizer · ALB · BFF · `svc-cuentas` · (**DynamoDB ‖ ACL+DX+Core**) | **99,97 %** | ≈ 13 | 99,95 % | **Sí** — el CQRS saca al Core de la serie |
| Transferencia propia | Borde · BFF · `svc-transferencias` · Aurora · `acl-core` · DX · **Core** | **99,90 %** | ≈ 43 | **99,9 %** (§10, SLO) | **Al límite** con 99,9 %; con 99,95 % no: limitada por el Core |
| Interbancaria — aceptación (`202`) | Borde · BFF · `svc-transferencias` · Aurora | **99,96 %** | ≈ 17 | 99,95 % | **Sí** — el Core sale de la ruta síncrona |
| Interbancaria — estado final en plazo | Aceptación · Step Functions · *worker* · `acl-core` · Core · red BCE (99,9 % **[E]**) | 99,80 % | — | 99 % en plazo (ADR-014) | Sí |
| Onboarding | Borde · `bff-movil` · `svc-onboarding` · Aurora · IDV · Registro Civil · IdP | **99,31 %** | ≈ 298 | 99,9 % | **No** en modo síncrono ⇒ se mide como "expediente aceptado" y la verificación se reintenta asíncronamente (F9) |

Conclusiones para BP (alimentan §17 del PDF):
1. **El Core y el IdP limitan el SLO**: con ambos a 99,95 %, los journeys que los tienen en serie no pueden superar ≈ 99,90 %. El canal en AWS aporta < 0,05 % de indisponibilidad.
2. Opciones (**decisión adoptada (§10, SLO): se aplican (b) y (a) a la vez** — SLO de login y transferencia propia = **99,9 %**, el resto se mantiene en 99,95 %, y se solicita a BP un IdP ≥ 99,99 % para volver a subir el de login): (a) exigir a BP **IdP ≥ 99,99 %** con despliegue activo-activo (o desplegar el IdP en AWS multi-AZ con su propio DR); (b) **renegociar el SLO** de login y transferencia propia a **99,9 %** mientras el Core no mejore; (c) medir el SLO del canal **excluyendo ventanas de mantenimiento programadas** del Core (S-14) y reportar aparte la disponibilidad de dependencias.
3. Lo que el diseño ya hace: CQRS (consulta independiente del Core), aceptación asíncrona de interbancarias, sesiones vivas ante caída del IdP, degradación elegante.

---

## 3. Recuperación ante desastres (DR)

### 3.1 Runbook resumido de conmutación regional (us-east-1 → us-east-2)

Disparadores: impacto regional confirmado por AWS Health **o** ≥ 2 journeys Tier 1 con *burn rate* 14,4× durante 15 min sin causa local remediable **o** decisión del comité de crisis. **Autoridad**: *incident commander* + gerente de TI de turno (doble aprobación, registrado en la auditoría).

| Paso | Acción | Herramienta | Tiempo objetivo |
|---|---|---|---|
| 0 | Declarar incidente P1, abrir puente, congelar despliegues | Herramienta de guardia, ChatOps | T+0 a T+5 min |
| 1 | Verificar salud de us-east-2 (*canaries* propios, réplicas: *lag* de Aurora Global, Global Tables, Global Datastore) | Tablero DR en Grafana | T+5 a T+8 |
| 2 | **Detener escrituras** en us-east-1 si aún responde, con *feature flag* de mantenimiento (AppConfig) o regla WAF/API Gateway que responda `503` a métodos de escritura. **ARC no se toca en este paso** (con la *safety rule* "al menos una región ON", ARC rechazaría `api-use1 = OFF` mientras `api-use2` siga OFF, y además dejaría sin DNS) | AppConfig / WAF | T+8 |
| 3 | **Promover Aurora**: ***managed failover*** de Global Database (`failover-global-cluster --allow-data-loss`) aunque la primaria no responda; *detach and promote* solo si hay versiones de motor incompatibles. Las aplicaciones usan el ***global writer endpoint*** (TTL DNS ≈ 5 s) para reducir *split-brain* (el *write fencing* es *best effort*). En un *switchover* planificado: RPO 0, típicamente < 30 s | RDS (bloque del plan Region switch) | T+8 a T+15 |
| 3b | **Promover el Global Datastore de seguridad** en us-east-2 (`failover-global-replication-group`); sin esto el *authorizer* no puede escribir `jti` y, por la decisión de *authorizer* y su modo degradado (§10), el *authorizer* seguiría en modo degradado *fail-static* (monetarias rechazadas) **[NV detalle con Valkey]** | ElastiCache (paso personalizado) | T+10 a T+15 |
| 4 | Escalar ECS en us-east-2 a la capacidad mínima de producción (por % de la región origen); lanzar **DMS** CDC hacia `cdc-movimientos` en us-east-2 | Bloque de escalado del plan + paso personalizado | T+10 a T+18 |
| 4b | **Arrancar los dos EventBridge Pipes** DynamoDB Streams → bus en us-east-2 — `movimientos` (`svc-cuentas`, `MovimientoRegistrado`) y `dispositivos` (`svc-dispositivos`, `auth.device.*`) —; en reposo están STOPPED para no duplicar eventos; detener los de us-east-1 si responden | Paso personalizado | T+15 a T+18 |
| 5 | Verificar conectividad al Core por **DX Gateway** desde us-east-2 y certificados mTLS | Synthetics de `acl-core` | T+15 a T+20 |
| 6 | **Rehidratar sagas** `EN_PROCESO` desde Aurora (nuevo `StartExecution` idempotente por `idTransferencia`); reanudar *relay* del outbox | *Job* `rehidratar-sagas` | T+18 a T+22 |
| 7 | Conmutar tráfico **en una sola llamada `UpdateRoutingControlStates`** (`api-use1 = OFF`, `api-use2 = ON`, ídem `web-*`) contra uno de los **5 *endpoints* regionales del plano de datos de ARC**, con credenciales preparadas en us-east-2; Route 53 responde con los orígenes de us-east-2 (CloudFront no cambia) | Route 53 ARC (*safety rule* "al menos una región ON" se respeta) | T+22 a T+25 |
| 8 | Validar journeys con *canaries* y transacción real de prueba; comunicar a clientes (página de estado) y al supervisor si aplica | Synthetics, comunicaciones | T+25 a T+30 |
| 9 | Retorno (*failback*) planificado cuando us-east-1 se estabilice: invertir replicación, ventana programada | Mismo runbook en sentido inverso | Días |

**Estabilidad estática:** durante la conmutación **no se usan planos de control alojados en us-east-1** (IAM, Route 53, CloudFront). Roles, certificados ACM de us-east-2, dominio personalizado de API Gateway en us-east-2, secreto de cabecera de origen y cuotas (vCPU de Fargate, concurrencia de Lambda, *throttling* de API Gateway) se dejan **creados y elevados de antemano**; los SDK usan STS regional.

**Automatización:** el runbook se codifica como **plan de ARC Region switch** (bloques nativos para *failover*/*switchover* de Aurora Global, escalado de ECS por % de la región origen, *routing controls* y *health checks* de Route 53; pasos personalizados para Global Datastore, Pipes, DMS y rehidratación de sagas). El plan se **valida cada 30 min** (USD 70/plan-mes **[V página pública]**). SSM Automation queda solo para auto-remediación local. El *go/no-go* sigue siendo humano.

Notas: las sesiones web sobreviven gracias al Global Datastore; las instancias móviles no se ven afectadas (sus llaves están en el dispositivo y los tokens se validan con JWKS del IdP). La caché de datos arranca fría y se calienta en minutos.

### 3.2 Qué está caliente, tibio y frío en us-east-2

| Estado | Componentes |
|---|---|
| **Caliente** (replicando continuamente, listo para servir) | Aurora Global `transferencias` (secundaria con instancia), DynamoDB Global Tables, ElastiCache Global Datastore de seguridad, S3 CRR (auditoría WORM, KYC, SPA), KMS multi-región, Secrets Manager réplicas, ECR replicado, DX Gateway, TGW de us-east-2 con sus **propias *private IP VPN* y VPN por Internet**, Network Firewall, Route 53 ARC |
| **Tibio** (desplegado a capacidad mínima, escala al conmutar) | ECS (Tier 1: 8 servicios × 2 tareas; Tier 2: 6 × 1 = **22 tareas**), API Gateway, ALB, WAF regional, Lambda *authorizer*, EventBridge/SQS/Step Functions (definidos, sin tráfico), **EventBridge Pipes `movimientos` y `dispositivos` desplegados y detenidos**, Kinesis streams, *canaries*, Aurora `plataforma` *headless* (almacenamiento replicado; se agrega instancia al conmutar) |
| **Frío** (se crea o se carga al conmutar) | ElastiCache de datos (vacío, *Refresh-Ahead*), DMS CDC (se lanza por IaC), Athena *workgroups* de análisis, reportería Tier 3 (AWS Backup *restore*, RTO 24 h) |

### 3.3 Pruebas

| Prueba | Alcance | Frecuencia | Evidencia |
|---|---|---|---|
| ***Game day* de DR completo** | Runbook §3.1 en producción en ventana acordada, o en `staging` con datos sintéticos si el regulador/BP lo prefiere | **Semestral** (ADR-013) | RTO/RPO medidos vs objetivo, acta, mejoras |
| **AWS FIS — caída de AZ** | *Network disruption* de una AZ para ECS/Aurora/ElastiCache | **Trimestral** | Journeys dentro de SLO |
| **FIS — latencia hacia el Core** | Inyectar 2–5 s en `acl-core` | Trimestral | *Circuit breaker* y *load shedding* actúan |
| **FIS — caída de proveedor** | Bloqueo del proveedor de notificaciones / IDV | Trimestral | Conmutación a alterno |
| Prueba de enlaces | Corte controlado de DX-1, luego DX-2, luego de un CGW | Semestral | **Convergencia medida por capa**: BFD del *underlay* < 1 s; túnel IPsec (DPD / *hold timer*) ≈ 30 s; VPN por Internet |
| Restauración de respaldos | Restore de Aurora/DynamoDB desde `backup-vault` | Trimestral | Integridad y tiempo |
| Prueba de carga | 1.000 req/s y 50 TPS (S-06/S-07) con picos de quincena | Antes de cada release mayor y mensual en staging | p95 S-13 |

---

## 4. Monitoreo y excelencia operativa (alineado con ADR-014)

### 4.1 SLI / SLO por journey

| Journey | SLI de disponibilidad | SLI de latencia (p95, en gateway) | SLO | Presupuesto de error mensual |
|---|---|---|---|---|
| Login | Logins exitosos / intentos válidos (excluye credenciales erróneas) | ≤ 1,5 s | **99,9 %** (§10, SLO; sube a 99,95 % si BP entrega IdP ≥ 99,99 %) | ≈ 43 min |
| Consulta de movimientos | Respuestas válidas (2xx, 3xx, 4xx de negocio) / total | Primera página ≤ 800 ms | 99,95 % | ≈ 21,6 min |
| Posición consolidada | Ídem | ≤ 500 ms con caché | 99,95 % | ≈ 21,6 min |
| Transferencia propia | Completadas sin error técnico / solicitadas | ≤ 2 s | **99,9 %** (§10, SLO; limitada por el Core) | ≈ 43 min |
| Transferencia interbancaria | Aceptación `202` sin error técnico; % con estado final dentro del plazo **por riel** (RPI segundos, SPI ciclo) | Aceptación ≤ 2 s | 99,95 % aceptación; 99 % en plazo | — |
| Onboarding | Expedientes aceptados sin error técnico | Pasos de captura ≤ 3 s (sin tiempo del usuario) | 99,9 % | ≈ 43 min |
| Notificación de movimiento | Entregadas por ≥ 2 canales en ≤ 60 s | ≤ 60 s | 99,9 % | — |

### 4.2 Alertas por *burn rate* (*multiwindow, multi-burn-rate*, SRE Workbook cap. 5)

| Severidad | Condición | Acción |
|---|---|---|
| Página (P1) | Consumo 14,4× en 1 h **y** 5 min (≈ 2 % del presupuesto mensual en 1 h) | Guardia del journey, *runbook* |
| Página (P2) | Consumo 6× en 6 h **y** 30 min | Guardia |
| Ticket | Consumo 1× en 3 días | *Backlog* de fiabilidad |

### 4.3 Alarmas accionables adicionales (cada una con dueño y *runbook*)

| Alarma | Fuente | Severidad |
|---|---|---|
| **IdP no disponible o con latencia anómala** | *Canary* de login cada minuto (2 regiones) + tasa de errores del *authorizer* + JWKS no renovable | P1 |
| **Tasa de aprobación/rechazo de liveness y de coincidencia 1:1 por versión del SDK IDV** (detección de anomalías) | Métricas de negocio de `svc-onboarding` | P2 (caída brusca ⇒ regresión o ataque de presentación) |
| Altas de dispositivos anómalas, reuso de tokens DPoP (`jti` repetido), *step-up* fallidos en serie, intentos desde instancias revocadas (`auth.request.rejected_revoked`) | `svc-dispositivos`, *authorizer* | P2 → SOC |
| *Circuit breaker* abierto hacia Core / complementario / red BCE | Resilience4j (métricas OTel) | P1 (Core) / P3 |
| DLQ > 0 en flujos monetarios; compensación fallida | SQS, Step Functions | P1 |
| Outbox con antigüedad > 60 s; *lag* CDC > 60 s | Métricas de `svc-transferencias`, `IteratorAge` | P2 |
| Caída anómala del volumen de transferencias vs línea base | CloudWatch *anomaly detection* | P2 |
| Estado de DX (`ConnectionState`), BGP caído, túnel VPN | CloudWatch DX/VPN | P2 (uno) / P1 (ambos) |
| Hallazgos críticos de **GuardDuty** (p. ej. *runtime* en Fargate, exfiltración S3), controles fallidos de **Security Hub** (FSBP/CIS), cambios en SCP/Object Lock/KMS | EventBridge → SIEM/SOC 24×7 de BP | P1/P2 |
| Eventos DDoS de Shield Advanced, picos de bloqueo de WAF/ATP | Shield, WAF | P2 + SRT |
| Presupuesto de AWS Budgets > 80 % / anomalía de costos (Cost Anomaly Detection) | Billing | Ticket FinOps |

### 4.4 Telemetría

| Señal | Implementación | Retención / costo |
|---|---|---|
| Trazas | OpenTelemetry (auto-instrumentación Java) → ADOT *sidecar* → X-Ray / Application Signals; W3C `traceparent` en HTTP, SQS, EventBridge, Kinesis y Step Functions; **muestreo 10 % + 100 % de errores y lentas** | 30 días |
| Métricas | *Golden signals* por servicio vía EMF/OTel; métricas de negocio (transferencias/min, rechazos, compensaciones, *hit ratio* de caché, entregas por canal, liveness por SDK) | 15 meses (CloudWatch) |
| *Logs* | JSON estructurado con `traceId`, `correlationId`, `sub` seudonimizado; **enmascaramiento de PII** en origen + *data protection policies*; clase **Standard** 30 días para servicios Tier 1, **Infrequent Access** para *workers* y depuración; exportación a S3 | 30–90 días en CloudWatch; 1 año en S3 |
| Experiencia real | CloudWatch RUM (SPA, **muestreo 25 %**), SDK de *crash/performance* en la app | 30 días |
| Sintéticos | CloudWatch Synthetics: login, posición, movimientos, transferencia propia (cuentas de prueba) **cada minuto desde us-east-1** y cada 5 min desde us-east-2; resto cada 5 min | — |
| Tableros | Managed Grafana: **un tablero por journey** (negocio: SLO, presupuesto restante), **por servicio** (técnico: RED/USE), **DR** (réplicas y *lag*), **seguridad** (WAF, GuardDuty, dispositivos), **FinOps** | — |

### 4.5 Operación

- ***On-call*** por equipo de dominio ("you build it, you run it", S-26) con rotación semanal, escalamiento a plataforma y a SOC; SNS → herramienta de guardia + canal de chat.
- **Auto-remediación** local con SSM Automation solo para acciones seguras: escalar, reciclar tareas, abrir *circuit breaker* manual, drenar AZ. La conmutación regional va en el plan de ARC Region switch (§3.1).
- ***Postmortems*** sin culpables en ≤ 5 días hábiles para todo P1/P2, con acciones con dueño y fecha; revisión mensual de SLO y de alertas ruidosas; **política de presupuesto de error** (si se agota, se congelan funcionalidades y se prioriza fiabilidad).
- Revisión trimestral **Well-Architected** (*lens* de servicios financieros) y FinOps mensual.

---

## 5. Costos mensuales (USD, producción us-east-1 + DR us-east-2)

### 5.1 Supuestos de volumen (de `01-requisitos.md` y nuevos)

| ID | Supuesto | Valor usado |
|---|---|---|
| S-06 | Llamadas a la API | 120 M/mes (46 req/s promedio; diseño 1.000 req/s) |
| S-07 | Transacciones monetarias | 80.000/día ≈ 2,4 M/mes; 40 % interbancarias ⇒ 960.000 sagas/mes × 12 transiciones |
| S-08 | Movimientos | 120 M/mes (escrituras CDC + eventos `MovimientoRegistrado`); 13 meses en línea ≈ 650 GB |
| S-09 | Auditoría | 160 M eventos ≈ 160 GB/mes crudos ⇒ ~32 GB/mes Parquet |
| S-04/S-05 | Sesiones | 8 M/mes (30 % web ⇒ 2,4 M logins web analizados por ATP) |
| **S-29 (nuevo)** | Onboarding digital | **30.000 inicios/mes** y ~25.000 verificaciones biométricas (incluye re-verificación por alta de dispositivo) |
| **S-30 (nuevo)** | Telemetría | ~700 GB/mes de *logs* de aplicación, ~1 TB/mes de *logs* vendidos (VPC Flow, WAF), 13 M trazas, 1.500 métricas |
| **S-31 (nuevo)** | Tráfico híbrido | ~2 TB/mes hacia on-prem por DX; ~0,5 TB/mes entre regiones |
| S-16 | Presupuesto | USD 25.000–45.000/mes prod + DR, **sin** licencia de IdP ni costo por transacción de biometría/SMS |

### 5.2 Resumen por capa

| Capa | Prod (us-east-1) | DR (us-east-2) | Total | Partidas principales |
|---|---:|---:|---:|---|
| Borde (CloudFront, WAF global y regional + Bot/ATP/ACFP, Shield Advanced, Route 53, ARC, plan Region switch) | 8.071 | — | 8.071 | Shield Advanced 3.000 + 88 de transferencia **[V]**, WAF ATP 2.283 **[V]**, ARC 1.825 **[V]**, WAF regional 87 |
| API (API Gateway REST, *authorizer*, ALB con VPC Link V2) | 515 | 40 | 555 | API Gateway 420 **[V]** |
| Cómputo (Fargate ARM64, Lambda) | 1.970 | 922 | 2.892 | 44 tareas promedio, 68 vCPU / 136 GB **[V]**; DR 22 tareas |
| Datos (Aurora, DynamoDB, ElastiCache, Kinesis, DMS, Firehose, S3, Athena, AWS Backup) | 2.485 | 962 | 3.447 | ElastiCache 789, Aurora 926, DMS 270, bóveda *air-gapped* 200 |
| Mensajería (EventBridge + Pipes, SQS, Step Functions) | 596 | — | 596 | Step Functions 288 **[V]** |
| Red (DX, VPN, TGW, Network Firewall, NAT exento, endpoints, Resolver, transferencias) | 2.620 | 1.272 | 3.892 | Network Firewall 898 **[V]**, DX 478, TGW 372, endpoints 327, Resolver 365 |
| Seguridad (KMS, Secrets, GuardDuty, Security Hub/Config, Inspector/Macie/Detective, Private CA vida corta) | 2.253 | 175 | 2.428 | GuardDuty 700, Security Hub/Config 650 |
| Observabilidad (Logs, métricas, X-Ray, Synthetics, RUM, Grafana, FIS) | 2.125 | 175 | 2.300 | Métricas/Application Signals 650, Logs 675 |
| **Total** | **20.635** | **3.546** | **24.181** | |
| Contingencia 15 % (precios [E], crecimiento, servicios menores no listados) | | | **3.627** | |
| **Total con contingencia** | | | **≈ 27.800** | Dentro de S-16 (25–45k), tercio inferior del rango |
| *Condicional*: AWS Private CA de propósito general para mTLS hacia on-prem (si BP no usa su PKI), 400 USD/CA-mes × 2 regiones **[V]** | | | +800 (+920 con contingencia) | ⇒ ≈ 28.700 |

Detalle fila a fila (supuesto, precio unitario, fuente, elasticidad) en [`costos/estimacion-costos.csv`](costos/estimacion-costos.csv). El DR representa ~15 % del total (por debajo del 25–35 % orientativo de ADR-013) porque el borde, Shield, ARC, WAF y la seguridad organizacional son globales o se cobran una vez; si se le imputan ARC, el plan Region switch y las réplicas de datos, el costo atribuible a DR sube a ~24 %.

Cómputo: el costo de Fargate corregido (≈ USD 1.970/mes en producción, con tamaños válidos de Fargate y el proxy de Service Connect) queda en el **extremo inferior** del orden de magnitud de ADR-002 (2.000–4.000), con el precio ARM verificado (vCPU 0,03238 USD/h, GB 0,00356 USD/h **[V]**) y tareas promedio en lugar de máximas. El pico de quincena (×4 durante pocas horas) añade ~10 % al promedio, ya incluido en la elasticidad de la sensibilidad.

### 5.3 Precios unitarios clave

| Servicio | Precio unitario (us-east-1) | Estado |
|---|---|---|
| Fargate ARM64 | 0,03238 USD/vCPU-h · 0,00356 USD/GB-h | [V] |
| API Gateway REST | 3,50 USD/M (primeros 333 M) | [V] 2026-10-13 |
| CloudFront (EE. UU.) | 0,085 USD/GB primeros 10 TB · 0,0100 USD/10.000 HTTPS | [V] 2026-10-03 |
| WAF (global exento por Shield; **regional del API Gateway no exento**) | 5 USD/Web ACL · 1 USD/regla · 0,60 USD/M; Bot Control *targeted* 10 USD/mes + 10 USD/M (> 1 M); ATP/ACFP 10 USD/mes + *Fraud Control* 1,00 USD/1.000 (10k–2M), 0,70 (2M–5M) | [V] 2026-09-14 |
| Shield Advanced | 3.000 USD/mes (1 año) + ~0,025 USD/GB de transferencia de recursos protegidos | [V] página pública |
| Aurora PostgreSQL `db.r8g.large` | 0,276 USD/h (estándar) · 0,359 USD/h (I/O-Optimized) · 0,10 USD/GB-mes · 0,20 USD/M I/O · 0,20 USD/M I/O replicadas (Global) | [V] |
| DynamoDB *on-demand* | 0,625 USD/M WRU · 0,125 USD/M RRU · 0,25 USD/GB-mes · 0,625 USD/M rWRU | [V] 2026-09-11 |
| ElastiCache Valkey | `cache.r7g.large` 0,1752 USD/h · `cache.m7g.large` 0,1264 USD/h | [V] 2026-10-14 |
| Kinesis *on-demand* | 0,04 USD/stream-h · 0,08 USD/GB entrada · 0,04 USD/GB lectura | [V] |
| DMS `r6i.large` Multi-AZ | 0,353 USD/h | [V] |
| Step Functions Standard | 0,000025 USD/transición | [V] |
| EventBridge / Pipes / SQS | 1,00 USD/M eventos · 0,40 USD/M · 0,40 USD/M | [V] |
| S3 Standard / Deep Archive | 0,023 USD/GB · ~0,00099 USD/GB | [V] / [E] |
| Direct Connect | Puerto dedicado 1G 0,30 USD/h · 10G 2,25 USD/h · salida 0,02 USD/GB | [V] |
| Transit Gateway / VPN | 0,05 USD/h adjunto · 0,02 USD/GB · 0,05 USD/h VPN | [V] |
| VPC Interface Endpoint | 0,01 USD/h por AZ | [V] |
| Network Firewall | 0,395 USD/h endpoint · 0,065 USD/GB | [V] página pública |
| NAT Gateway | 0,045 USD/h · 0,045 USD/GB, **exentos** por hora y por GB cuando está en la misma ruta que Network Firewall (quedan IPv4 públicas) | [V] página pública |
| Route 53 Resolver endpoints | ~0,125 USD/h por ENI | [E] |
| ARC Region switch | 70 USD/plan-mes | [V] página pública |
| KMS | 1 USD/llave-mes · 0,03 USD/10k · 0,15 USD/10k asimétricas | [V] |
| Secrets Manager | 0,40 USD/secreto · 0,05 USD/10k | [V] |
| GuardDuty | 4 USD/M eventos CloudTrail · 1,5 USD/vCPU-mes Fargate (primeros 500) | [V] |
| Private CA vida corta | 50 USD/CA-mes · 0,058 USD/certificado (propósito general: 400 USD/CA-mes, condicional para mTLS on-prem) | [V] |
| CloudWatch Logs | 0,50 USD/GB Standard · 0,25 USD/GB IA · 0,03 USD/GB-mes | [V] 2026-10-07 |
| Synthetics | 0,0012 USD/ejecución | [V] |
| Managed Grafana | 9 USD/editor · 5 USD/lector | [V] |
| Route 53 ARC (cluster) | 2,50 USD/h | [V] página pública |

### 5.4 NO incluido en S-16 (se lista aparte, orden de magnitud)

| Concepto | Supuesto | Orden de magnitud mensual | Estado |
|---|---|---|---|
| Licencia / suscripción del IdP (producto existente de BP; si fuera Keycloak, soporte comercial) | Ya pagado por BP o a cotizar en RFP (02 §4) | USD 5.000–30.000 según producto y nº de instancias registradas (~2,4 M) | [E] cotizar |
| Proveedor biométrico IDV (liveness + 1:1) | ~25.000 verificaciones/mes (S-29) × USD 0,5–1,5 | USD 12.000–38.000 | [E] cotizar |
| Consulta a la fuente oficial de identidad (Registro Civil / DINARDAP) | ~25.000 consultas/mes | Según tarifa oficial | [NV] |
| SMS (OTP, alertas cuando no hay *push*) | ~300.000 SMS/mes a números de Ecuador × USD 0,03–0,08 | USD 9.000–24.000 | [E] |
| Correo (SES) y *push* (FCM/APNs vía End User Messaging) | ~5 M correos (0,10 USD/1.000 **[E]**); *push* sin costo de FCM/APNs | USD 500–1.000 | [E] |
| Circuitos del *carrier* local Ecuador → ubicación Direct Connect (Miami/Ashburn) | 2 circuitos internacionales de 1 Gbps, proveedores distintos | USD 4.000–15.000 | [E] cotizar |
| Soporte AWS | **Enterprise** (mín. ~15.000 USD/mes) recomendado para banca por TAM/IEM; alternativa Business (~10 % hasta 10k, 7 % de 10k a 80k ⇒ ~1.800) | USD 1.800–15.000 | [E] |
| Entornos no productivos (`staging`, `qa`, `dev`) | Sin Shield/ARC/ATP, 1 AZ en dev, Fargate Spot, apagado nocturno | USD 3.500–6.000 | [E] |
| CloudHSM (solo si BP/supervisor exige custodia mono-inquilino) | 2 HSM por región × 2 regiones | ~USD 4.400–9.000 | [E] |
| Herramienta de guardia y SIEM/SOC de BP | Existentes en BP | — | — |

### 5.5 Sensibilidad al volumen

Cada fila del CSV tiene una **elasticidad** (0 = costo fijo, p. ej. Shield, ARC, puertos DX; 1 = lineal, p. ej. API Gateway, WAF ATP, logs, Step Functions; valores intermedios para servicios con capacidad mínima, como Aurora o ElastiCache).

| Escenario | Volumen (S-03..S-09) | Total mensual (prod + DR, sin contingencia) | vs S-16 |
|---|---|---:|---|
| ×0,5 | 1 M clientes, 60 M llamadas/mes | **≈ 19.100** (≈ 22.000 con contingencia) | Por debajo (los fijos — Shield, ARC, Network Firewall, DX, DR — pesan ~50 %) |
| **Base** | Supuestos de §5.1 | **≈ 24.200** (≈ 27.800 con contingencia) | Tercio inferior del rango |
| ×2 | 4 M clientes, 240 M llamadas/mes | **≈ 34.300** (≈ 39.400 con contingencia) | Dentro del rango |

Lectura: la arquitectura escala de forma **sub-lineal** (×2 de volumen ⇒ ×1,42 de costo) porque los costos de seguridad y resiliencia son mayormente fijos; el costo variable dominante es el **WAF ATP**, seguido de *logs* y API Gateway.

### 5.6 Palancas de optimización

| Palanca | Acción | Ahorro estimado / mes | Riesgo / trade-off |
|---|---|---:|---|
| ATP con *scope-down* | Analizar con ATP solo logins sin *cookie* de dispositivo conocido o con señal de riesgo (IP *reputation*, ASN anónimo) | ~1.300 (≈ 60 % de 2.283) | Menor cobertura en dispositivos conocidos (mitigado por *rate-based rules*) |
| **Palanca documentada, NO adoptada (descartada en §10)**: ARC Region switch **en lugar** del clúster de *routing control* | El plan Region switch (70 USD/mes, ya incluido para codificar el runbook) conmuta con sus bloques de *health checks* de Route 53 sin clúster dedicado; alternativa: compartir el clúster con otras cargas de BP | ≈ 1.755 | **No es la opción por defecto**: el clúster de *routing control* es el plano de datos de conmutación más robusto (5 *endpoints* regionales) |
| DR con 1 tarea por servicio Tier 1 | Bajar de 2 a 1 tarea en us-east-2 (escala al conmutar) | ≈ 460 | Menos capacidad inicial tras el *failover*; depende de cuotas pre-elevadas |
| **Compute Savings Plans** (1 año, sin pago adelantado) sobre Fargate y Lambda | Comprometer ~70 % del consumo base (cómputo corregido) | ~400–500 **[E]** | Compromiso de 1 año |
| **Nodos reservados** ElastiCache y **instancias reservadas** Aurora (1 año; incluye `plataforma` aprovisionada) | Clústeres de base estables | ~400 **[E]** | Compromiso; menor flexibilidad de tipo |
| **Graviton** en todo | Ya aplicado (Fargate ARM, r8g/r7g/m7g) | Incluido (~20 % vs x86) | Imágenes multi-arquitectura en CI |
| DynamoDB clase **Standard-IA** para `movimientos` | Almacenamiento 0,10 vs 0,25 USD/GB-mes (escrituras 0,78 vs 0,625 USD/M) en ambas regiones | ~150 | Mayor costo por escritura; conviene por el peso del almacenamiento |
| *Logs* en clase **Infrequent Access** y niveles por entorno | IA para *workers*, depuración y auditoría técnica; DEBUG solo bajo demanda | ~100–200 | IA sin algunas funciones (p. ej. *metric filters*) |
| Muestreo de trazas y RUM | Ya aplicado (10 % + errores; RUM 25 %); bajar a 5 % fuera de picos | ~50 | Menos detalle en casos raros |
| Retención por niveles | Logs 30 días en CloudWatch, luego S3 → Glacier; auditoría ya en Deep Archive | ~50 (crece con el tiempo) | Consulta más lenta del histórico |
| DX 1 Gbps + IPsec en lugar de 10 Gbps + MACsec | Ya aplicado (§10) | ~2.850 evitados | MACsec solo si BP ya tiene 10G |
| Autoscaling y apagado de no productivos | Escalado a mínimos fuera de horario; `dev` apagado de noche/fines de semana | 30–50 % del costo no productivo | — |
| **Total de palancas aplicables** (sin las ya incluidas; la exención de NAT por Network Firewall ya está en la base) | | **≈ 3.000–5.500** (con la palanca descartada en el extremo alto) | Total base ⇒ **≈ 18.700–21.200** sin contingencia |

**Validación pendiente**: replicar esta estimación en **AWS Pricing Calculator** (exportar el enlace al repositorio) y contrastar con AWS Cost Explorer tras el primer mes de staging con carga sintética.

---

## 6. Equivalencias Azure (anexo A-9)

| Servicio AWS | Equivalente Azure | Nota |
|---|---|---|
| AWS Organizations + SCP | Management Groups + Azure Policy | |
| IAM Identity Center | Microsoft Entra ID (PIM) | |
| Route 53 / Route 53 ARC | Azure DNS + Traffic Manager / Azure Front Door (*origin groups*) | Sin equivalente exacto de ARC |
| CloudFront | Azure Front Door (Standard/Premium) | |
| AWS WAF (Bot Control, ATP, ACFP) | Azure WAF en Front Door (Bot Manager); fraude de cuenta con soluciones de terceros | ATP/ACFP sin equivalente nativo directo |
| Shield Advanced | Azure DDoS Protection (Network Protection) | |
| API Gateway REST + Lambda authorizer | Azure API Management (políticas `validate-jwt`, *zone redundancy*) | |
| ALB + VPC Link V2 | Application Gateway / Private Link | |
| ECS Fargate + Service Connect | Azure Container Apps (Dapr / *service discovery*) o AKS | |
| Lambda | Azure Functions | |
| Aurora PostgreSQL Global Database | Azure Database for PostgreSQL Flexible Server (HA de zona + réplica de lectura geo) | RPO mayor que Aurora Global |
| Aurora PostgreSQL `plataforma` (`db.r8g.large`, §10) | PostgreSQL Flexible Server (General Purpose, alta disponibilidad con redundancia de zona) | |
| DynamoDB Global Tables | Azure Cosmos DB (NoSQL, multi-región) | |
| ElastiCache for Valkey (Global Datastore) | Azure Cache for Redis (Enterprise, geo-replicación) / Azure Managed Redis | |
| S3 + Object Lock + Glacier Deep Archive | Blob Storage con *immutability policies* (WORM) + nivel Archive | |
| Kinesis Data Streams | Azure Event Hubs | |
| Data Firehose | Event Hubs Capture / Stream Analytics | |
| Athena + Glue + Lake Formation | Synapse serverless SQL / Fabric + Purview | |
| AWS DMS (CDC) | Azure Database Migration Service / Debezium en Container Apps | |
| EventBridge (+ Pipes) | Azure Event Grid | |
| SQS (+ DLQ) | Azure Service Bus (colas, *dead-letter*) | |
| Step Functions Standard | Durable Functions / Logic Apps Standard | |
| End User Messaging (SMS/push) + SES | Azure Communication Services (SMS, correo) + Notification Hubs | |
| KMS / CloudHSM / XKS | Azure Key Vault (Premium HSM) / Managed HSM | |
| Secrets Manager | Azure Key Vault (secretos) | |
| AWS Private CA | Key Vault certificates + CA privada propia / AKV integrada con CA | |
| Direct Connect + DX Gateway (+ MACsec) | ExpressRoute (+ ExpressRoute Global Reach, MACsec en ExpressRoute Direct) | |
| Site-to-Site VPN | Azure VPN Gateway | |
| Transit Gateway | Azure Virtual WAN / *hub* VNet | |
| Network Firewall + DNS Firewall | Azure Firewall Premium + DNS Private Resolver con políticas | |
| NAT Gateway | Azure NAT Gateway | |
| VPC Endpoints (PrivateLink) | Private Endpoints | |
| GuardDuty | Microsoft Defender for Cloud (planes de servidores, contenedores, almacenamiento) | |
| Security Hub + Config | Defender for Cloud (*secure score*, *regulatory compliance*) + Azure Policy | |
| Inspector | Defender for Containers (escaneo de imágenes) | |
| Macie | Microsoft Purview (clasificación de datos) | |
| Detective | Microsoft Sentinel (investigación) | |
| CloudTrail | Azure Activity Log + Diagnostic Settings | |
| CloudWatch (Logs, métricas, Application Signals, Synthetics, RUM) | Azure Monitor, Log Analytics, Application Insights (disponibilidad y *browser*) | |
| X-Ray + ADOT | Application Insights con OpenTelemetry | |
| Managed Grafana | Azure Managed Grafana | |
| AWS FIS | Azure Chaos Studio | |
| AWS Backup (bóveda *air-gapped*) | Azure Backup (bóveda inmutable) | |
| ECR | Azure Container Registry (geo-replicación) | |

---

## 7. Lista para el diagrama de infraestructura AWS

### 7.1 Nodos agrupados

```
G0  Internet / Clientes
    N01 Cliente web (navegador, SPA)            N02 Cliente móvil (app Flutter)
G1  Servicios globales de AWS (fuera de región)
    N03 Route 53 (zonas públicas, health checks) N04 Route 53 ARC (cluster, routing controls, safety rules) + plan ARC Region switch
    N05 CloudFront (dist. SPA y dist. API)      N06 AWS WAF global (Bot Control, ATP, ACFP, rate-based)
    N07 Shield Advanced (solo prod)              N08 IAM Identity Center / Organizations (SCP)
G2  Cuenta prod — Región us-east-1 (principal)
    G2.R Servicios regionales (fuera de VPC)
        N10 API Gateway REST regional + WAF regional    N11 Lambda authorizer (JWT + DPoP, en VPC → ver G2.V app)
        N12 DynamoDB (movimientos, perfil-digital, notificaciones, preferencias, dispositivos) [Global Tables]
        N13 Kinesis Data Streams (cdc-movimientos, auditoria)   N14 Data Firehose (auditoria → S3)
        N15 EventBridge bus bp-dominio + Pipes + schema registry   N16 SQS (colas + DLQ por consumidor; tareas de saga)
        N17 Step Functions Standard (saga interbancaria)   N18 S3 spa-web (OAC) y kyc-evidencias (KMS dedicada)
        N19 KMS (llaves multi-región) · Secrets Manager (réplicas) · Private CA (vida corta)
        N20 End User Messaging (SMS/push) · SES          N21 AWS DMS (instancia Multi-AZ en subred app)
        N22 ECR (replicado desde shared-services)
    G2.V VPC prod-use1 10.10.0.0/16 — 3 AZ (AZ-a, AZ-b, AZ-c), cada AZ con 4 subredes:
        Subred integración (10.10.0–2.0/24):  N24 ALB interno (destino directo del VPC Link V2; sin NLB — N23 eliminado)
        Subred app (10.10.16/20, 32/20, 48/20): ECS cluster bp-prod-use1 (Service Connect bp.internal):
            N25 bff-web  N26 bff-movil  N27 svc-dispositivos  N28 svc-clientes  N29 svc-cuentas  N30 svc-cuentas-proyector
            N31 svc-transferencias  N32 svc-transferencias-worker  N33 svc-notificaciones  N34 svc-auditoria
            N35 svc-onboarding  N36 acl-core  N37 acl-complementario  N38 acl-interbancario   (+ sidecar ADOT en cada tarea)
            N11 Lambda authorizer (ENI)
        Subred datos (10.10.64/22, 68/22, 72/22): N39 Aurora PostgreSQL transferencias (escritor AZ-a, lector AZ-b)
            N40 Aurora PostgreSQL plataforma (`db.r8g.large` aprovisionado, escritor + lector; §10)   N41 ElastiCache Valkey datos (2 shards, réplicas en otra AZ)
            N42 ElastiCache Valkey seguridad (sesiones, jti DPoP, LISTA DE DENEGACIÓN; 3 nodos, 1 por AZ)
        Subred endpoints (10.10.80–82.0/24): N43 Interface endpoints (KMS, Secrets, ECR api/dkr, STS, Logs, Monitoring, X-Ray, Kinesis, SQS, EventBridge, States, SSM)
            N44 Gateway endpoints S3 y DynamoDB
        Subred TGW (10.10.96.0/28 ×3): N45 adjunto TGW
G3  Cuenta network — us-east-1
    N46 Transit Gateway us-east-1 (CIDR 10.9.255.0/24; tablas prod-rt, noprod-rt, onprem-rt, insp-rt; ECMP VPN desactivado)
    N47 VPC inspección 10.9.0.0/22: Network Firewall (3 endpoints) + DNS Firewall + NAT Gateway ×3 (exentos) + Internet Gateway; adjunto TGW con APPLIANCE MODE
    N68 Route 53 Resolver endpoints salientes (DNS híbrido hacia el CPD) en us-east-1 y us-east-2
    N48 Direct Connect Gateway (global, asociado a TGW use1 y use2)
    N49 DX-1 (1 Gbps dedicado, ubicación A, transit VIF, BFD) · N50 DX-2 (1 Gbps dedicado, ubicación B)
    N51 2 × private IP VPN (IPsec sobre DX) en el TGW use1 · N52b Site-to-Site VPN por Internet (respaldo)
G4  Cuenta log-archive — us-east-1 (+ réplica us-east-2)
    N52 S3 auditoría (Object Lock COMPLIANCE 10 años, Parquet) → Glacier Deep Archive   N53 S3 sellos Merkle (Object Lock)
    N54 Glue Catalog + Athena + Lake Formation    N55 CloudTrail organizacional · logs VPC/WAF   N56 Lambda de sellado (KMS asimétrica)
G5  Cuenta security-tooling
    N57 GuardDuty · Security Hub · Inspector · Macie · Detective · Config (agregador)  → N58 SIEM/SOC de BP
G6  Cuenta observability
    N59 CloudWatch cross-account (logs, métricas, Application Signals, alarmas)  N60 X-Ray  N61 Managed Grafana
    N62 CloudWatch Synthetics (canaries use1/use2)  N63 CloudWatch RUM  N64 SNS → herramienta de guardia / chat  N65 SSM Automation (auto-remediación)
G7  Cuenta shared-services
    N66 CI/CD (GitHub Actions OIDC) · ECR origen · IaC Terraform
G8  Cuenta backup-vault
    N67 AWS Backup (bóveda logically air-gapped; copias de Aurora, DynamoDB, S3)
G9  Cuenta prod — Región us-east-2 (DR warm standby)  [estilo punteado / "tibio"]
    N70 API Gateway + WAF regional (tibio)   N71 ALB (tibio)   N72 ECS bp-prod-use2 (Tier 1: 8 × 2 tareas, Tier 2: 6 × 1 = 22)
    N73 Aurora Global secundaria (caliente)  N74 DynamoDB réplicas Global Tables (caliente)
    N75 ElastiCache seguridad Global Datastore (caliente)  N76 ElastiCache datos (frío)
    N77 S3 CRR (auditoría WORM, KYC, SPA)    N78 Kinesis/Firehose/EventBridge/SQS/Step Functions (tibios)  N79 DMS (frío)
    N80 TGW use2 (CIDR 10.19.255.0/24) + 2 private IP VPN propias sobre DX + VPN por Internet propia + VPC inspección use2 10.17.0.0/22 (Network Firewall 2 AZ, appliance mode) + peering TGW con N46
    N78b EventBridge Pipes en us-east-2 `movimientos` y `dispositivos` (desplegados, DETENIDOS)  N73b Aurora plataforma secundaria headless
G10 On-premise (CPD de BP, 10.200.0.0/16 de ejemplo)
    N81a CGW-1 (circuito/carrier A) · N81b CGW-2 (circuito/carrier B) (BGP, IPsec, clamping de MSS ≈ 1360–1379)   N82 Core bancario (+ réplica/standby para CDC)
    N83 Sistema complementario   N84 Conector de pagos BCE → N85 BCE: RPI (ISO 20022 pacs.008/pacs.002) y SPI
    N86 IdP OAuth/OIDC de BP (si es on-prem; si es SaaS, va en G0 como externo)
G11 Externos SaaS (salen por N47 con lista blanca)
    N87 Proveedor IDV (liveness + 1:1)   N88 Registro Civil   N89 Proveedor alterno de SMS/correo   N90 FCM / APNs   N91 Apple App Attest / Play Integrity
```

### 7.2 Flechas (protocolo y seguridad)

| # | Origen → destino | Protocolo / seguridad |
|---|---|---|
| E01 | N01/N02 → N05 CloudFront | **HTTPS TLS 1.3** (1.2 mín.), HSTS; móvil con *pinning* de llave pública (conjunto de respaldo) |
| E02 | N03 → N05 / N10 | DNS (registros gobernados por ARC N04) |
| E03 | N05 → N18 spa-web | HTTPS, **OAC** (firma SigV4) |
| E04 | N05 (+N06/N07) → N10 API Gateway | HTTPS TLS 1.3 + **cabecera secreta de origen** (rotada en Secrets Manager) + WAF regional |
| E05 | N10 → N11 authorizer | Invocación Lambda (IAM); N11 → N42 (TLS + AUTH: `jti` DPoP y **lista de denegación**) |
| E06 | N10 → N24 ALB | **VPC Link V2** privado, TLS (API, VPC link y ALB en la misma cuenta) |
| E07 | N24 → N25 bff-web / N26 bff-movil | HTTPS TLS 1.3 (SG solo desde ALB) |
| E08 | BFF → `svc-*` (N27–N35) | **HTTP/2 + TLS 1.3 Service Connect** (certs Private CA vida corta) + **JWT** propagado (re-validación `aud`/scopes/propiedad) |
| E09 | `svc-*` → `acl-*` (N36–N38) | TLS 1.3 Service Connect + JWT / token de servicio (`client_credentials` + `private_key_jwt`) |
| E10 | N36/N37/N38 → N45 → N46 TGW → N51 private IP VPN → N48 DXGW → N49/N50 → N81a/N81b → N82/N83/N84 | **mTLS** de aplicación sobre **IPsec sobre DX** (o **MACsec** si 10G+ dedicado); BFD en el *underlay*, BGP del túnel con DPD; MTU 1446 |
| E11 | N46 → N52b VPN → N81a/N81b | IPsec por Internet (respaldo, menor preferencia BGP) |
| E11b | N80 TGW use2 → sus private IP VPN → N48 DXGW → N81a/N81b | Igual que E10, independiente de us-east-1 |
| E12 | N84 → N85 BCE | Red del BCE (fuera del alcance AWS), ISO 20022 |
| E13 | Servicios → N86 IdP | HTTPS TLS 1.3 (PAR, token, JWKS, DCR) vía DX si on-prem o egress N47 si SaaS; `private_key_jwt` |
| E14 | N26 bff-movil → N91 | HTTPS (verificación de atestación) por egress N47 |
| E15 | N35 svc-onboarding → N87 / N88 | HTTPS TLS 1.2+ con credenciales en Secrets Manager, por egress N47 (lista blanca) |
| E16 | N33 svc-notificaciones → N20 / N89 / N90 | HTTPS (API AWS por endpoint privado; terceros por N47) |
| E17 | **Todos los servicios de la subred app** (N25–N38) y N11 *authorizer* → N43/N44 | PrivateLink / *gateway endpoints* con políticas anti-exfiltración |
| E18 | `svc-*` → N39/N40 | PostgreSQL TLS + **IAM auth** (SG solo del dueño) |
| E19 | `svc-*` → N12 DynamoDB / N41 ElastiCache datos | HTTPS vía *gateway endpoint* / TLS + RBAC |
| E20 | N82 (réplica) → N21 DMS → N13 Kinesis | CDC por DX (TLS a la BD de la réplica); Kinesis vía endpoint |
| E21 | N13 → N30 proyector → N12; N12 Streams → N15 Pipes → EventBridge → N16 SQS → consumidores | Eventos CloudEvents 1.0 con `traceparent` |
| E22 | N32 worker ↔ N17 Step Functions ↔ N16 (tareas `.waitForTaskToken`) | API AWS vía endpoint |
| E23 | Servicios (outbox / librería) → N13 `auditoria` → N34 → N14 Firehose → N52 (cuenta log-archive) | Cross-account con política de bucket + SSE-KMS; Object Lock |
| E24 | N56 → N53 | Firma KMS asimétrica de la raíz Merkle horaria |
| E25 | Tareas (sidecar ADOT) → N59/N60 | OTLP → CloudWatch/X-Ray (endpoints privados), cross-account |
| E26 | N59 alarmas → N64 SNS → guardia; N59 → N65 SSM Automation | Eventos |
| E27 | N57 hallazgos → EventBridge → N58 SIEM/SOC | Cross-account / integración del SIEM |
| E28 | N46 TGW → N47 inspección → Internet | Egress con Network Firewall (lista blanca por dominio) + NAT |
| E29 | N66 CI/CD → N22 ECR → ECS | OIDC sin llaves de larga vida; imágenes firmadas |
| E30 | N68 Route 53 Resolver (*endpoints* salientes) → DNS on-prem del CPD (G10) | DNS híbrido: reenvío condicional de las zonas internas de BP por TGW → DX; *endpoints* salientes en us-east-1 y us-east-2 |

### 7.3 Elementos de DR (dibujar como flechas punteadas)

| # | Replicación / conmutación | Mecanismo | RPO |
|---|---|---|---|
| D01 | N39 → N73 | **Aurora Global Database** (replicación de almacenamiento) | ≈ 1 s |
| D02 | N12 ↔ N74 | **DynamoDB Global Tables** | segundos |
| D03 | N42 → N75 | **ElastiCache Global Datastore** | segundos |
| D04 | N18/N52/N53 → N77 | **S3 CRR** con Object Lock | minutos |
| D05 | N19 → us-east-2 | KMS multi-región + réplicas de Secrets Manager | — |
| D06 | N22 → us-east-2 | Replicación de ECR | — |
| D07 | N46 ↔ N80 | **TGW peering** inter-región (rutas estáticas); mismo **DX Gateway** (N48) con *private IP VPN* propias por región hacia el Core | — |
| D08 | N04 ARC → N03 | *Routing controls* `api-use1`/`api-use2`, `web-use1`/`web-use2` con *safety rule*, conmutados en **una sola llamada** desde el plan Region switch | — |
| D09 | N39/N12/N52 → N67 | AWS Backup cross-account (bóveda *air-gapped*) | 24 h (Tier 3) |
| D10 | N62 | *Canaries* en ambas regiones (detectan y validan la conmutación) | — |
| D11 | N78b | Pipes `movimientos` y `dispositivos` **detenidos** en us-east-2; se arrancan en el paso 4b del runbook (§3.1) | — |

---

## 8. Ajustes aplicados a documentos anteriores

| Documento | Ajuste |
|---|---|
| `01-requisitos.md` S-20 | "*refresh token rotation*" ⇒ "*refresh token* ligado al remitente (DPoP / credencial de instancia), rotación solo en modo degradado"; el IdP debe soportar **DCR (RFC 7591), PAR (RFC 9126) y DPoP (RFC 9449)**; nota: Amazon Cognito no los soporta ⇒ respaldo BFF confidencial (02 §4) |
| `01-requisitos.md` §5 | Nuevos supuestos S-29 (onboarding), S-30 (telemetría), S-31 (tráfico híbrido) |
| `adr/ADR-009` | Nueva §3.4: `acl-interbancario` con estrategias de riel **RPI** (tiempo real ≤ USD 15.000, ISO 20022 `pacs.008`/`pacs.002`) y **SPI** (confirmación diferida); `TimeoutSeconds` de la saga parametrizado por riel; traducción ISO 20022 solo en el ACL |
| `adr/ADR-004` | Referencia a ISO 20022 cerrada (apunta a ADR-009 §3.4) |

## 9. Justificación teórica de las decisiones de este documento

1. **AWS Well-Architected — Fiabilidad (REL10, REL11, REL13)** y *whitepaper* "Disaster Recovery of Workloads on AWS": multi-AZ, auto-healing, DR por RTO/RPO (warm standby) y conmutación con plano de datos (ARC).
2. **AWS Well-Architected — Optimización de Costos (COST05–COST07)**: seleccionar el tipo y tamaño de recurso por datos (Graviton, *on-demand* vs reservado, tiers de almacenamiento), medir y gobernar (Budgets, Cost Anomaly Detection), y **FinOps Foundation Framework** (*inform → optimize → operate*).
3. **AWS Security Reference Architecture (AWS SRA)** y *whitepaper* "Organizing Your AWS Environment Using Multiple Accounts": OU de seguridad, infraestructura y cargas; cuentas de *log-archive* y *security-tooling*; egress centralizado e inspeccionado.
4. **Google SRE Book (cap. 3–4) y SRE Workbook (cap. 2 y 5)**: SLO por journey, presupuesto de error, alertas por *burn rate*; la disponibilidad de un servicio no puede superar la de sus dependencias críticas en serie (cálculo §2.2).
5. **Nygard, *Release It!* (2018)**: *Circuit Breaker*, *Bulkhead*, *Fail Fast*, *Shed Load* en la tabla de modos de falla; **Basiri et al., "Chaos Engineering" (IEEE Software, 2016)** para FIS y *game days*.
6. **AWS Direct Connect — Resiliency Recommendations** (modelo de máxima/alta resiliencia con ubicaciones y *carriers* distintos) y **NIST SP 800-207** (cifrado de extremo a extremo incluso en enlaces privados).

## 10. Decisiones de diseño adoptadas

Las decisiones siguientes se adoptaron tras la revisión de nube, costos y seguridad. El texto de los demás apartados las referencia por su tema.

| Tema | Decisión adoptada | Motivo | Alternativa descartada |
|---|---|---|---|
| **Cuentas y DR** | DR en la **misma cuenta `prod`** (región us-east-2) + cuenta **`backup-vault`** con bóveda *air-gapped*; se elimina la cuenta `dr` de 02 §2.3 | Aurora Global Database no admite secundarias en otra cuenta (solo *snapshots*, RPO de horas, incumple Tier 1); DynamoDB sí admite Global Tables multi-cuenta, pero con configuración unificada y sin convertir tablas existentes, y partir un *bounded context* entre cuentas complica el *failover* sin aislamiento real; VPC Link V2 exige API, VPC link y balanceador en la misma cuenta. Aislamiento: `backup-vault` + SCP | Cuenta `dr` separada con réplicas por *snapshots*/AWS Backup (RPO peor, no cumple Tier 1) |
| **Enlaces DX** | **2 × DX dedicados de 1 Gbps + IPsec sobre DX** (MACsec solo si BP ya tiene dedicadas de 10/100/400G) | Consumo real ≈ decenas de Mbps; MACsec no existe en 1G ni en *hosted* **[V]** y 10G cuesta ≈ 2 × (2,25 − 0,30) × 730 ≈ USD 2.850/mes más. **Trade-offs**: MTU 1446 / *clamping* de MSS, conmutación de túnel ≈ 30 s (no < 1 s), ≤ 1,25 Gbps por túnel (suficiente) y 2 conexiones *private IP VPN* por TGW, también en us-east-2 | 2 × 10G + MACsec |
| **svc-dispositivos** | Nuevo **`svc-dispositivos`** (contexto Identidad) con DynamoDB `dispositivos` y escritura de la **lista de denegación** en ElastiCache de seguridad; `bff-movil` solo atestación y DCR | ADR-003 prohíbe reglas de negocio en BFF; la política de 3 dispositivos es regla de dominio | Módulo dentro de `bff-movil` (menos servicios, pero BFF "gordo") |
| **Authorizer** | *Authorizer* **fail-closed** para operaciones monetarias y *fail-open* acotado (lecturas con tokens ≤ 5 min) si cae todo el clúster de seguridad | Equilibra seguridad (revocación inmediata) y disponibilidad | *Fail-closed* total (más seguro, más indisponibilidad) |
| **Modo degradado** | **Modo degradado *fail-static***: el *authorizer* mantiene en memoria una copia de la lista de denegación (refresco 15–30 s) y, si el clúster cae, aplica la última copia conocida. Operaciones monetarias (por método + recurso, p. ej. `POST /transferencias*`): *fail-closed*. Lecturas: se omite solo la verificación de `jti`, con ventana `iat` ≤ 60 s y tokens ≤ 5 min. El modo degradado **expira a los 15 min** y pasa a *fail-closed* total salvo extensión manual; emite `auth.degraded_mode` (P1 + SOC). Refina la decisión de *authorizer*; el detalle se valida en la revisión de seguridad | Revisión de infraestructura (M2): las revocaciones siguen aplicándose durante la caída del clúster | Mantener la decisión de *authorizer* sin modo degradado |
| **Lista de denegación** | Clúster ElastiCache de **seguridad** replicado con **Global Datastore** a us-east-2 (el de datos queda frío) | La lista de denegación debe sobrevivir al *failover* | Reconstruirla desde `dispositivos` al conmutar (más lento) |
| **SLO** | SLO de 99,9 % para login y transferencia propia; resto 99,95 %; se recomienda a BP un IdP ≥ 99,99 % | Recomendar a BP **IdP ≥ 99,99 %** o renegociar el SLO de login y transferencia propia a **99,9 %** | Mantener 99,95 % como objetivo aspiracional del canal, excluyendo dependencias |
| **Contingencia** | Presupuesto con **contingencia 15 %** (≈ USD 27.800/mes; ≈ 28.700 si aplica la Private CA condicional) y no productivos fuera de S-16 | Precios [E] y servicios menores | Incluir no productivos (≈ USD 31.300–33.800/mes) |
| **Region switch** | **Descartada**: se mantiene el clúster de *routing control*; Region switch queda solo como palanca de ahorro documentada | Sustituir el clúster de *routing control* de ARC (1.825 USD/mes) por los bloques de conmutación del plan **ARC Region switch** (70 USD/mes, ya incluido) | **Por defecto se mantiene el clúster** (plano de datos más robusto) |
| **Aurora plataforma** | Aurora `plataforma` en `db.r8g.large` aprovisionado con secundaria *headless* en us-east-2 (en lugar de Serverless v2) | Serverless v2 en Global Database requiere ≥ 8 ACU en el primario (≈ USD 1.400/mes) | Serverless v2 con 8 ACU mínimo |

## 11. Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Precios [E]/[S] (Shield, ARC, Network Firewall, NAT, observabilidad) difieren de los reales | ±10–15 % del total | Pricing Calculator + contingencia 15 % + Cost Anomaly Detection |
| Volumen real de ATP o de *logs* mayor al supuesto | Costo variable sube | *Scope-down* de ATP, clase IA, alarmas de presupuesto |
| Core e IdP de BP sin la disponibilidad o DR exigidos | SLO 99,95 % incumplible (por eso login y transferencia propia quedan en 99,9 %, §10) | Decisión de SLO (§10); acuerdos de nivel de servicio internos; modo degradado |
| Prohibición de Aurora Global *cross-account* basada en evidencia indirecta; promoción de Global Datastore con Valkey; valor exacto de DPD de AWS VPN; cuota de *allowed prefixes* DXGW–TGW (no verificados por infra) | Ajustes menores de runbook o red | Validar en PoC de infraestructura / con AWS antes de producción |
| Circuitos Ecuador → ubicación DX y CGW | Limitan la disponibilidad híbrida (≈ 99,995 %) | 2 *carriers*, 2 CGW, VPN por Internet de respaldo |
| Costos fuera de AWS (biometría, SMS, *carrier*, IdP) superan al de la nube | El TCO real puede ser 2–3× el costo AWS | Listado aparte §5.4; cotizar en RFP |
| Latencia real Ecuador → us-east-1 peor que la estimada (S-11) | p95 de S-13 | Medición previa (ADR-002), CloudFront, caché |
| Conmutación regional nunca ensayada | RTO no garantizado | *Game day* semestral obligatorio con evidencia |
