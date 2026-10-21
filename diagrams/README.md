# Diagramas de infraestructura AWS · Reto BP

Fuente de verdad: `docs/03-nube-ha-dr-monitoreo-costos.md` §7.1 (nodos N01-N91), §7.2 (flechas E01-E29), §7.3 (DR D01-D10).

## Láminas

| Archivo fuente | PNG | Contenido | Nodos (aprox.) |
|---|---|---|---|
| `04-infra-vista-general.py` | `png/04-infra-vista-general.png` | Vista de conjunto agregada para el lector | 18 |
| `04a-borde-y-entrada.py` | `png/04a-borde-y-entrada.png` | G0, G1, API Gateway, authorizer, ALB, ElastiCache seguridad, S3 spa-web | 14 |
| `04a2-computo-y-datos.py` | `png/04a2-computo-y-datos.png` | ECS Fargate por boundary (Canales/BFF, Dominio, ACL, Plataforma), datos, endpoints | 18 |
| `04c-red-hibrida-y-cuentas.py` | `png/04c-red-hibrida-y-cuentas.png` | TGW, inspección, DXGW, DX ×2, VPN, CGW, CPD on-prem, SaaS, cuentas de plataforma | 18 |
| `04b-dr-multi-region.py` | `png/04b-dr-multi-region.png` | DR us-east-1 ↔ us-east-2, estados caliente/tibio/frío | 17 |

Los archivos PNG se regeneran al ejecutar el script. El formato es A4 apaisado (proporción cercana a 1,4:1 en la vista general y las láminas de detalle).

## Cómo regenerar

Requisitos: Graphviz en el PATH (`dot -V`) y `uv`. No usar el Python de Anaconda (3.8).

```bash
cd diagrams
uv run --python 3.12 --with diagrams python 04-infra-vista-general.py
uv run --python 3.12 --with diagrams python 04a-borde-y-entrada.py
uv run --python 3.12 --with diagrams python 04a2-computo-y-datos.py
uv run --python 3.12 --with diagrams python 04c-red-hibrida-y-cuentas.py
uv run --python 3.12 --with diagrams python 04b-dr-multi-region.py
```

`estilo.py` es un módulo compartido: no se ejecuta solo. Todos los scripts lo importan.

## Guía de estilo

Módulo: `estilo.py` (reutilizable para los diagramas C4).

**Paleta de grupos** (borde / fondo):

| Tipo | Uso | Borde | Fondo |
|---|---|---|---|
| `cliente` | Internet / clientes | `#7F8C8D` | `#F4F6F6` |
| `global` | Servicios globales AWS | `#2E4053` | `#EEF1F4` |
| `prod` | Cuenta prod us-east-1 (activa) | `#1168BD` | `#EBF4FB` |
| `red` | Red, TGW, inspección, DX | `#438DD5` | `#EEF5FC` |
| `seguridad` | Seguridad y cifrado | `#B03A2E` | `#FBEDEC` |
| `obs` | Observabilidad | `#7D3C98` | `#F5EEF8` |
| `respaldo` | Cuentas de plataforma, backup, CI/CD | `#1E8449` | `#EAF7EF` |
| `dr` | Región DR us-east-2 (punteada) | `#117A65` | `#E8F6F3` |
| `onprem` | CPD de BP | `#6C6C6C` | `#F2F2F2` |
| `externo` | SaaS y terceros | `#B9770E` | `#FEF5E7` |

**Flechas** (convención única):
- Síncrona (petición-respuesta): línea sólida, color `#1B2631`.
- Asíncrona (evento / cola): línea discontinua, color `#1B2631`.
- Replicación DR: línea punteada, color `#117A65`, trazo más grueso.

**Tipografía y tamaño**: Arial. Título 22 pt. Etiquetas de nodo 11 pt. Etiquetas de flecha 10 pt. 170 DPI, fondo blanco.

**Rótulos**: nodo = `Exx`/`Nxx` + nombre + detalle en una o dos líneas. Flecha = código Exx + protocolo corto. El detalle completo va en la tabla Exx de este README.

**Leyenda**: se incluye en cada lámina con `leyenda()`. En la lámina DR se activa `estados=True` para mostrar [CALIENTE], [TIBIO] y [FRÍO].

**Conectores fuera de página**: `conector("Ver 04c …")`. Máximo uno por lámina.

**Para C4**: reutilizar `diagrama()`, `grupo()`, `conectar()` y `leyenda()`; los tipos de grupo son los mismos. Los contenedores C4 usan el color `#438DD5` y las personas `#08427B` (definidos en la leyenda de `docs/04-modelo-c4.md` §0.1).

## Tabla Exx (protocolo y seguridad)

| Código | Origen → destino | Protocolo / seguridad | Lámina |
|---|---|---|---|
| E01 | Clientes → CloudFront | HTTPS TLS 1.3 (1.2 mín.), HSTS; móvil con pinning | 04, 04a |
| E02 | Route 53 → CloudFront / API | DNS, registros gobernados por ARC | 04, 04a |
| E03 | CloudFront → S3 spa-web | HTTPS, OAC (SigV4) | 04a |
| E04 | CloudFront → API Gateway | HTTPS TLS 1.3 + cabecera secreta de origen + WAF regional | 04, 04a |
| E05 | API Gateway → authorizer → ElastiCache seguridad | Invocación IAM; TLS + AUTH (jti DPoP, lista de denegación) | 04a |
| E06 | API Gateway → ALB | VPC Link V2 privado, TLS | 04, 04a |
| E07 | ALB → BFF | HTTPS TLS 1.3, SG solo desde ALB | 04a, 04a2 |
| E08 | BFF → servicios de dominio | HTTP/2 + TLS 1.3 Service Connect + JWT propagado (aud, scopes, propiedad) | 04a2 |
| E09 | Servicios → ACL | TLS 1.3 Service Connect + token de servicio (client_credentials + private_key_jwt) | 04a2 |
| E10 | ACL → CPD (Core, complementario, BCE) | mTLS de aplicación sobre IPsec/DX; MTU 1446; MACsec si hay 10G dedicado | 04, 04c |
| E11 | TGW → VPN por Internet → CGW | IPsec por Internet (respaldo, menor preferencia BGP) | 04c |
| E11b | TGW use2 → DXGW | IPsec sobre DX, independiente de us-east-1 | 04b |
| E12 | Conector BCE → BCE | Red del BCE, fuera del alcance AWS; ISO 20022 | 04c |
| E13 | Servicios → IdP | HTTPS TLS 1.3 (PAR, token, JWKS, DCR), por DX si es on-prem | 04, 04c |
| E14 | bff-movil → Apple/Google | HTTPS de verificación de atestación, egress N47 | 04a2 |
| E15 | svc-onboarding → IDV / Registro Civil | HTTPS TLS 1.2+, credenciales en Secrets Manager, egress N47 | 04a2 |
| E16 | svc-notificaciones → SMS/correo/FCM | HTTPS por endpoint privado o egress N47 | 04a2 |
| E17 | Servicios de la subred app → endpoints | PrivateLink y gateway endpoints con políticas anti-exfiltración | 04a2 |
| E18 | Servicios → Aurora | PostgreSQL TLS + IAM auth; SG solo del dueño | 04a2, 04b |
| E19 | Servicios → DynamoDB / ElastiCache datos | HTTPS vía gateway endpoint; TLS + RBAC | 04a2, 04b |
| E20 | Réplica del Core → DMS → Kinesis | CDC por DX (TLS a la réplica) | 04b |
| E21 | Kinesis / DynamoDB Streams → Pipes → EventBridge → SQS | Eventos CloudEvents 1.0 con traceparent | 04a2 |
| E22 | Worker de saga ↔ Step Functions | .waitForTaskToken, API AWS vía endpoint | sin lámina propia |
| E23 | Servicios → Kinesis auditoría → Firehose → S3 WORM | Cross-account, SSE-KMS, Object Lock | sin lámina propia |
| E24 | Lambda de sellado → sello Merkle | Firma KMS asimétrica de la raíz horaria | sin lámina propia |
| E25 | Sidecar ADOT → CloudWatch / X-Ray | OTLP sobre endpoints privados, cross-account | 04a2 (nota) |
| E26 | Alarmas → SNS → guardia; SSM Automation | Eventos | sin lámina propia |
| E27 | GuardDuty / Security Hub → EventBridge → SIEM/SOC | Cross-account, integración del SIEM | sin lámina propia |
| E28 | TGW → inspección → Internet | Egress con Network Firewall (lista blanca por dominio) + NAT | 04, 04c |
| E29 | CI/CD → ECR → ECS | OIDC sin llaves de larga vida; imágenes firmadas | 04a2 (nota) |
| E30 | N68 Route 53 Resolver → DNS on-prem | DNS híbrido, reenvío condicional por TGW/DX | 04c |
| D01 | Aurora primaria → Aurora Global | Replicación de almacenamiento, RPO ≈ 1 s | 04b |
| D02 | DynamoDB ↔ Global Tables | Replicación activa, RPO segundos | 04b |
| D03 | Valkey seguridad → Global Datastore | Replicación, RPO segundos | 04b |
| D04 | S3 → S3 CRR | Object Lock, RPO minutos | 04b |
| D05 | KMS / Secrets → us-east-2 | Llaves multi-región y réplicas | sin lámina propia |
| D06 | ECR → us-east-2 | Replicación de imágenes | sin lámina propia |
| D07 | TGW use1 ↔ TGW use2 | Peering inter-región con rutas estáticas; DXGW compartido | 04b |
| D08 | ARC → Route 53 | Routing controls api/web con safety rule; un solo cambio | 04b |
| D09 | Aurora / DynamoDB / S3 → AWS Backup | Copia cross-account a bóveda air-gapped, RPO 24 h (Tier 3) | sin lámina propia |
| D10 | Canaries → us-east-2 | Synthetics en ambas regiones | sin lámina propia |

## Decisiones de representación y pendientes

- **N40 Aurora plataforma**: se dibuja como aprovisionada (db.r8g.large), según la corrección de §1.7. §7.1 todavía dice "Serverless v2". Pendiente de corregir en §7.1 del documento 03.
- **N68 Route 53 Resolver**: se dibuja en la lámina 04c con la flecha "DNS híbrido (reenvío condicional)" hacia el CGW (DNS on-prem).
- **E17**: se dibuja desde el cluster de dominio hacia los endpoints, no desde cada servicio.
- **Agregados en la vista general**: ECS 14 servicios + BFF como un nodo; Aurora ×2 como un nodo; cuentas de plataforma como un bloque. Las láminas de detalle conservan los nodos individuales.
- **Pendientes de dibujo**: E11, E12, E13, E22-E27 y D05, D06, D09, D10 no tienen flecha propia en las láminas. Se listan en la tabla para el PDF.
- **Leyenda de estados en 04b**: las etiquetas [CALIENTE]/[TIBIO]/[FRÍO] salen desplazadas respecto a sus puntos. Es un defecto de layout pendiente de corregir.
- **Etiquetas de nodo**: en la vista general, el texto de algunos nodos queda sobre el borde inferior del icono. Pendiente de ajuste de márgenes.

## C4 (DC-01, DC-02) y secuencias (05)

Fuente de verdad: `docs/04-modelo-c4.md` (§0 leyenda y convención, §1 DC-01, §2 DC-02, §5 secuencias).
Librería común: `c4_comun.py` (funciones `elemento`, `elemento_v2`, `flecha`, `flecha_v2`, `limite`, `leyenda`, `diagrama_c4`).

### Láminas y estado

| Lámina | Fuente | PNG | Estado |
|---|---|---|---|
| DC-01 Contexto (negocio) | `01-contexto.py` | `png/01-contexto.png` | Completa, legible en A4 apaisado |
| DC-02a Contenedores: canales y entrada | `02a-contenedores-canales-y-entrada.mmd` | `png/02a-contenedores-canales-y-entrada.png` | Una caja por contenedor |
| DC-02b Contenedores: dominio y datos | `02b-contenedores-dominio-y-bases.mmd` | `png/02b-contenedores-dominio-y-bases.png` | Una caja por contenedor |
| DC-02c Contenedores: integración on-premise y terceros | `02c-contenedores-integracion-on-prem-y-terceros.mmd` | `png/02c-contenedores-integracion-on-prem-y-terceros.png` | Una caja por contenedor |
| DC-02d Contenedores: eventos, notificaciones y auditoría | `02d-contenedores-eventos-notificaciones-auditoria.mmd` | `png/02d-contenedores-eventos-notificaciones-auditoria.png` | Una caja por contenedor |
| DC-03a..e Componentes | `03a..03e-*.mmd` (+ `03b-estados-fail-static.mmd`) | `png/03*-*.png` | Mermaid |
| DC-06 Arquitectura interna de canales | `06-arquitectura-front-movil.mmd` | `png/06-arquitectura-front-movil.png` | Mermaid |
| SQ-01..SQ-07 Secuencias | `05-sqNN-*.mmd` | `png/05-sqNN-*.png` | Mermaid |

### Regenerar

```
cd diagrams
# Graphviz (contexto e infraestructura)
uv run --python 3.12 --with diagrams python 01-contexto.py
uv run --python 3.12 --with diagrams python 04-infra-vista-general.py   # igual para 04a, 04a2, 04b, 04c
# Mermaid: C4 de contenedores y componentes (con la configuración común)
npx -y @mermaid-js/mermaid-cli@11.4.2 -i 02a-contenedores-canales-y-entrada.mmd -o png/02a-contenedores-canales-y-entrada.png -w 2000 -s 2 -b white -c mermaid-config.json
# Mermaid: secuencias
npx -y @mermaid-js/mermaid-cli@11.4.2 -i 05-sq01-login-web-bff.mmd -o png/05-sq01-login-web-bff.png -w 2000 -s 2 -b white
```

Las fuentes Mermaid son copia literal de la especificación (ADR-007 §3, ADR-008 §3, `02-seguridad-y-normativa.md` §6.5 y `04-modelo-c4.md` §5), con dos cambios de sintaxis: `;` reemplazado por `,` dentro del texto de dos mensajes (SQ-02 y SQ-05), que Mermaid interpretaba como separador.

| Secuencia | Fuente |
|---|---|
| SQ-01 Login web por BFF, DPoP de punta a punta y step-up por redirección | `05-sq01-login-web-bff.mmd` |
| SQ-02 Enrolamiento de instancia y login móvil | `05-sq02-enrolamiento-login-movil.mmd` |
| SQ-03 Reanudar sesión móvil | `05-sq03-reanudar-sesion-movil.mmd` |
| SQ-04 Onboarding con reconocimiento facial | `05-sq04-onboarding-facial.mmd` |
| SQ-05a Transferencia interbancaria: autorización con *step-up* y scoring (hasta el 202) | `05-sq05a-transferencia-autorizacion-stepup.mmd` |
| SQ-05b Transferencia interbancaria: saga, rieles RPI/SPI y compensación | `05-sq05b-transferencia-saga.mmd` |
| SQ-06 Robo de dispositivo | `05-sq06-robo-dispositivo.mmd` |
| SQ-07 Consulta de movimientos con caché | `05-sq07-consulta-movimientos.mmd` |

### Tabla de relaciones DC-02 (R01-R69)

Protocolo y seguridad tomados de `docs/04-modelo-c4.md` §2.2. Síncrona = sólida; asíncrona = discontinua.
Se reparten en las láminas 02a–02d; el código entre paréntesis de cada flecha remite a esta tabla.

| ID | Origen → destino | Protocolo · seguridad | Sinc./asinc. | Lámina |
|---|---|---|---|---|
| R01 | P1 → C01 | HTTPS (navegador) | Síncrono | 02a |
| R02 | P1, P2 → C02 | App instalada | Síncrono | 02a |
| R03 | C01 → C03 | HTTPS TLS 1.3 (1.2 mín.) · HSTS · cookie `__Host-sid` · anti-CSRF | Síncrono | 02a |
| R04 | C02 → C03 | HTTPS TLS 1.3 · pinning · DPoP | Síncrono | 02a |
| R05 | C03 → C04 | HTTPS · OAC (SigV4) | Síncrono | 02a |
| R06 | C03 → C05 | HTTPS TLS 1.3 · cabecera secreta · WAF regional | Síncrono | 02a |
| R07 | C05 → C06 | Invocación Lambda (IAM) | Síncrono | 02a |
| R08 | C06 → D08 | Valkey TLS + AUTH · timeout ≤ 30 ms · fail-static | Síncrono | 02a |
| R09 | C06 → X03 | HTTPS TLS 1.3 · caché | Síncrono (cacheado) | 02a |
| R10 | C05 → C07 | VPC Link V2 → ALB interno · TLS 1.3 | Síncrono | 02a |
| R11 | C05 → C08 | VPC Link V2 → ALB interno · TLS 1.3 | Síncrono | 02a |
| R12 | C07 → X03 | HTTPS TLS 1.3 · private_key_jwt · DPoP · PKCE | Síncrono | 02a |
| R13 | C07 → D08 | Valkey TLS + AUTH/RBAC | Síncrono | 02a |
| R14 | C02 → X03 | HTTPS TLS 1.3 · PKCE · private_key_jwt · DPoP | Síncrono | 02a |
| R15 | C08 → X11 | HTTPS TLS 1.3 · egress lista blanca | Síncrono | 02a |
| R16 | C08 → C17 | SC · JWT | Síncrono | 02a |
| R17 | C08 → X03 | HTTPS TLS 1.3 · initial access token · software statement (KMS) | Síncrono | 02a |
| R18 | C07 → C17 | SC · JWT con step-up | Síncrono | 02a |
| R19 | P3 → C17 | API backoffice · red corporativa/DX · SSO + MFA | Síncrono | 02a |
| R20 | C17 → D06 | HTTPS · VPCE gateway · IAM | Síncrono | 02b |
| R21 | C17 → D08 | Valkey TLS + AUTH | Síncrono | 02a |
| R22 | C17 → X03 | HTTPS TLS 1.3 · private_key_jwt | Síncrono | 02a |
| R23 | C07, C08 → C09 | SC · JWT | Síncrono | 02a |
| R24 | C07, C08 → C10 | SC · JWT | Síncrono | 02a |
| R25 | C07, C08 → C12 | SC · JWT con authorization_details · Idempotency-Key | Síncrono | 02a |
| R26 | C08 → C16 | SC · JWT ligado a jkt | Síncrono | 02a |
| R27 | C09 → C18 | SC · client_credentials + private_key_jwt | Síncrono | 02b |
| R28 | C09 → C19 | SC · token de servicio | Síncrono | 02b |
| R29 | C09 → D04, D07 | VPCE · IAM / Valkey TLS + RBAC | Síncrono | 02b |
| R30 | C10 → D03 | HTTPS · VPCE · IAM | Síncrono | 02b |
| R31 | C10 → D07 | Valkey TLS + RBAC | Síncrono | 02b |
| R32 | C10 → C18 | SC · token de servicio | Síncrono | 02b |
| R33 | C12 → D01 | PostgreSQL TLS · IAM (una transacción) | Síncrono | 02b |
| R34 | C12 → C18 | SC · token de servicio | Síncrono | 02b |
| R35 | C13 → D01 | PostgreSQL TLS · SKIP LOCKED | Síncrono | 02b |
| R36 | C13 → C14 | API AWS · VPCE · IAM | Síncrono | 02b |
| R37 | C14 → Q03 | API AWS · IAM | Asíncrono | 02b |
| R38 | Q03 → C13 | SQS long polling · IAM | Asíncrono | 02b |
| R39 | C13 → C18 | SC · token de servicio | Síncrono | 02b |
| R40 | C13 → C20 | SC · token de servicio (confirmación asíncrona) | Síncrono | 02b |
| R41 | C18 → X01 | mTLS sobre IPsec sobre Direct Connect | Síncrono | 02c |
| R42 | C19 → X02 | mTLS sobre IPsec-DX o HTTPS por egress | Síncrono | 02c |
| R43 | C20 → X04 | mTLS sobre IPsec-DX · ISO 20022 | Asíncrono (callback) | 02c |
| R44 | X04 → X05 | Red del BCE (fuera de AWS) | Asíncrono | 02c |
| R45 | X01 → Q05 | TLS a la BD de la réplica por DX | Asíncrono (CDC) | 02c |
| R46 | Q05 → Q04 | API Kinesis · VPCE · IAM | Asíncrono | 02c |
| R47 | Q04 → C11 | KCL · IAM | Asíncrono | 02c |
| R48 | C11 → D03 | HTTPS · VPCE · IAM (idempotente por secuencia) | Síncrono | 02b |
| R49 | D03 → Q01 | DynamoDB Streams → EventBridge Pipes · CloudEvents 1.0 | Asíncrono | 02b |
| R50 | C13 → Q01 | API EventBridge · VPCE · CloudEvents 1.0 + traceparent | Asíncrono | 02b |
| R51 | C16, C17 → Q01 | CloudEvents 1.0 (outbox / Streams + Pipes) | Asíncrono | 02b |
| R52 | Q01 → Q02 | Reglas EventBridge | Asíncrono | 02d |
| R53 | Q02 → C15 | SQS + DLQ | Asíncrono | 02d |
| R54 | Q02 → C09, C10 | SQS + DLQ | Asíncrono | 02d |
| R55 | C15 → D05 | VPCE · IAM | Síncrono | 02d |
| R56 | C15 → C25 | API SES · VPCE | Síncrono | 02c |
| R57 | C15 → C26 | API End User Messaging · VPCE | Síncrono | 02c |
| R58 | C15 → X09 | HTTPS TLS 1.3 · egress lista blanca | Síncrono | 02c |
| R59 | C15 → X10 | HTTPS TLS 1.3 · egress lista blanca · circuit breaker | Síncrono | 02c |
| R60 | C25, C26, X09, X10 → P1 | Correo / SMS / push | Asíncrono | 02c |
| R61 | C16 → D02, D09 | PostgreSQL TLS + IAM · S3 SSE-KMS dedicada | Síncrono | 02b |
| R62 | C16 → X06, X07, X08 | HTTPS TLS 1.2+ · Secrets Manager · egress lista blanca | Síncrono | 02c |
| R63 | C16 → X03 | HTTPS TLS 1.3 · private_key_jwt (SCIM) | Síncrono | 02a |
| R64 | B2, B3, X03 → Q06 | API Kinesis · VPCE · IAM | Asíncrono | 02d |
| R65 | Q06 → C21 | KCL · IAM | Asíncrono | 02d |
| R66 | C21 → C22 → D10 | Firehose cross-account · SSE-KMS · Object Lock | Asíncrono | 02d |
| R67 | C23 → D11 | KMS Sign (asimétrica) · Object Lock | Asíncrono (programado) | 02d |
| R68 | P4 → C24 → D10 | IAM Identity Center + MFA · Lake Formation | Síncrono | 02d |
| R69 | C06, C27 → X12 | Métrica/alarma P1 → EventBridge → SIEM | Asíncrono | 02d |

Nota: las filas 02b se dibujan en el lienzo de eventos. Las filas 02a corresponden a las relaciones agrupadas de la lámina.

### Pendientes conocidos
- DC-02a: el lienzo agrupado no alcanza A4 con fuente ≥ 14 pt por el layout automático de Graphviz (ancho del orden de 8000 px). Opciones: rediseñar con menos clusters anidados o dividir en dos láminas. Decisión pendiente sobre la distribución de láminas.
- DC-01: leyenda a la derecha, sin superposiciones.
- Revisión visual pendiente de SQ-03, SQ-04 y SQ-06.
