# Registro de decisiones de arquitectura (ADR) — Banca por internet BP

> **Contenido:** decisiones de arquitectura (ADR) · **Fecha:** 2026-10-18 · **Estado global:** aceptados el 2026-10-19 (incluye los ajustes de §10 del documento 03).
> **Fuente de requisitos:** [`../01-requisitos.md`](../01-requisitos.md) (IDs RF/RNF/S/A). Cada ADR sigue la misma plantilla: contexto, ≥2 opciones con tabla ponderada, decisión, ≥2 justificaciones teóricas con referencia, consecuencias y riesgos, patrones, equivalente Azure y trazabilidad.
> **Autoría:** ADR-007 y ADR-008 se centran en seguridad e identidad; el resto, en arquitectura de solución. Los criterios de rúbrica se numeran como en `01-requisitos.md` §7.

## Índice

| ADR | Título | Decisión en una línea | Estado | Criterios de rúbrica |
|---|---|---|---|---|
| [ADR-001](ADR-001-estilo-arquitectonico-y-eventos.md) | Estilo arquitectónico y comunicación entre servicios | Microservicios por *bounded context*; REST síncrono solo donde el cliente espera, eventos de dominio en EventBridge + SQS/DLQ por consumidor; Transactional Outbox con *relay* por *polling*; catálogo ampliado con `svc-dispositivos` (§10 del documento 03) | Aceptado (2026-10-19) | 1, 3, 4, 5 |
| [ADR-002](ADR-002-plataforma-aws-region-y-computo.md) | Plataforma AWS: región y cómputo | `us-east-1` (3 AZ) + DR `us-east-2`, condicionado a medir latencia desde Ecuador; ECS Fargate ARM64 para servicios, Lambda solo para *glue* | Aceptado (2026-10-19) | 1, 8, 9, 14 |
| [ADR-003](ADR-003-api-gateway-y-bff.md) | API Gateway + BFF por canal | CloudFront + WAF → API Gateway REST regional (JWT + DPoP + *step-up* RFC 9470) → `bff-web` (token handler) y `bff-movil` (solo atestación + DCR; política de dispositivos en `svc-dispositivos`); *authorizer* con lista de denegación y modo *fail-static* (modo degradado) | Aceptado (2026-10-19) | 1, 3, 4, 5, 6, 10 |
| [ADR-004](ADR-004-integracion-core-y-modelo-lectura.md) | Integración con Core/complementario y modelo de lectura | ACL dedicados por sistema externo con presupuesto de TPS; 2 Direct Connect + VPN; movimientos por CQRS alimentado por CDC (DMS → Kinesis → DynamoDB) con conmutación al Core | Aceptado (2026-10-19) | 1, 3, 4, 5, 7, 14 |
| [ADR-005](ADR-005-spa-framework-y-arquitectura.md) | SPA: framework y arquitectura | Angular, SPA modular en monorepo Nx con *lazy loading*; S3 + CloudFront OAC; sin tokens en el navegador | Aceptado (2026-10-19) | 1, 6, 10, 13 |
| [ADR-006](ADR-006-app-movil-multiplataforma.md) | App móvil multiplataforma | Flutter (finalista alterno: React Native) con módulo nativo de seguridad (llaves en hardware, DPoP, atestación, RASP) | Aceptado (2026-10-19) | 1, 6, 10, 11, 13 |
| [ADR-007](ADR-007-autenticacion-oauth2-oidc.md) | Autenticación OAuth 2.0/OIDC, métodos y *step-up* | Authorization Code + PKCE + PAR (FAPI 2.0 sin desviación); SPA vía BFF (RFC 10017) con DPoP de punta a punta (IdP y servicios internos) y *step-up* web por redirección; móvil como cliente confidencial por instancia (DCR + `private_key_jwt` en hardware, atestación vía `bff-movil`; respaldo BFF confidencial) con DPoP y *device binding*; sesión móvil 2 min / segundo plano / 30 min, web 5/30 min; passkeys/biometría; *step-up* con RAR | Aceptado (2026-10-19) (seguridad) | 6, 10, 13 |
| [ADR-008](ADR-008-onboarding-reconocimiento-facial.md) | Onboarding con reconocimiento facial | Servicio de Onboarding propio (puertos/adaptadores) con IDV FacePhi recomendado (ISO/IEC 30107-3, conformidad nivel 2 de iBeta), Registro Civil, KYC/AML; primer token por el flujo estándar del IdP | Aceptado (2026-10-19) (seguridad) | 10, 11, 13 |
| [ADR-009](ADR-009-orquestacion-transferencias-y-pagos.md) | Orquestación de transferencias y pagos | Idempotency-Key + validación de `authorization_details`; internas síncronas contra Core; interbancarias con saga orquestada en Step Functions Standard y compensación | Aceptado (2026-10-19) | 1, 4, 5, 7, 14 |
| [ADR-010](ADR-010-notificaciones-multicanal.md) | Notificaciones multicanal | `svc-notificaciones` con Strategy (push, correo, SMS) + Adapter (proveedor primario y alterno); ≥2 canales por movimiento; sin Pinpoint (fin de soporte) | Aceptado (2026-10-19) | 1, 4, 5, 13, 14 |
| [ADR-011](ADR-011-persistencia-y-clientes-frecuentes.md) | Persistencia por servicio y clientes frecuentes | Aurora PostgreSQL (transaccional), DynamoDB (lecturas), ElastiCache Valkey; clientes frecuentes con Cache-Aside + Refresh-Ahead selectivo + invalidación por eventos | Aceptado (2026-10-19) | 1, 3, 4, 7, 14 |
| [ADR-012](ADR-012-auditoria-inmutable.md) | Auditoría inmutable | Kinesis → `svc-auditoria` (cadena de *hashes* + sellos firmados con KMS) → S3 Object Lock Compliance + Athena; 12 meses en línea, 10 años archivado; sin QLDB ni CloudTrail Lake | Aceptado (2026-10-19) | 1, 4, 7, 12, 13 |
| [ADR-013](ADR-013-ha-dr-y-resiliencia.md) | HA, DR y resiliencia | 3 AZ con auto-healing; DR *warm standby* en `us-east-2` para Tier 1–2, backup & restore Tier 3; Resilience4j + ECS Service Connect (sin App Mesh); degradación del *authorizer* (§3.4) | Aceptado (2026-10-19) | 1, 8, 9, 14 |
| [ADR-014](ADR-014-observabilidad-y-slo.md) | Observabilidad y SLO | AWS nativo con OpenTelemetry (CloudWatch, Application Signals, X-Ray, Grafana, Synthetics, RUM); SLO por journey con alertas por *burn rate*; login y transferencia propia 99,9 % (SLO de §10) | Aceptado (2026-10-19) | 1, 8, 9, 14, 15 |

### Cobertura de la rúbrica por ADR

| Criterio | ADR que lo cubren | Además (fuera de ADR) |
|---|---|---|
| 1 Requerimientos con justificación | Todos | Autoevaluación (PDF) |
| 2 Calidad de diagramas C4 | — (los ADR alimentan los elementos) | Diagramas (documento 04) · anexo de herramienta C4 |
| 3 Segmentación y desacoplamiento | 001, 003, 004, 011 | — |
| 4 Patrones | 001, 003, 004, 009, 010, 011, 012, 013 | Anexo de patrones |
| 5 Integración con externos | 001, 003, 004, 009, 010 | — |
| 6 Front-end y móvil | 003, 005, 006, 007 | — |
| 7 Acceso a datos | 004, 009, 011, 012 | — |
| 8 Nube | 002, 013, 014 (y servicios en todos) | Anexo Azure (A-9) |
| 9 Costos | 002, 013, 014 (órdenes de magnitud en todos) | Estimación de costos (documento 03) |
| 10 Autenticación | 003, 005, 006, **007** | — |
| 11 Onboarding | 006, **008** | — |
| 12 Auditoría | **012** | — |
| 13 Regulaciones y seguridad | 005, 006, 007, 008, 010, 012 | Documento de seguridad: perímetro/zero trust, cifrado/KMS/HSM, STRIDE, matriz normativa |
| 14 HA y tolerancia a fallos | 002, 004, 009, 010, 011, **013** | — |
| 15 Monitoreo | **014** | — |

## Consolidación 25 → 14

| ADR consolidado | Absorbe (numeración de candidatos iniciales) |
|---|---|
| ADR-001 | 001 Estilo arquitectónico + 012 Mensajería y eventos |
| ADR-002 | 002 Región(es) AWS + 003 Plataforma de cómputo |
| ADR-003 | 004 Capa de integración y API por canal |
| ADR-004 | 005 Integración con Core/complementario + 016 Modelo de lectura de movimientos |
| ADR-005 | 010 Framework y arquitectura de la SPA |
| ADR-006 | 009 Framework móvil |
| ADR-007 | 006 Flujo OAuth 2.0/OIDC + 008 Métodos de autenticación y *step-up* |
| ADR-008 | 007 Onboarding con reconocimiento facial |
| ADR-009 | 011 Orquestación de transferencias y pagos |
| ADR-010 | 013 Notificaciones multicanal |
| ADR-011 | 014 Persistencia por servicio + 015 Mecanismo para clientes frecuentes |
| ADR-012 | 017 Solución de auditoría |
| ADR-013 | 018 Estrategia de HA y DR + 019 Patrones de resiliencia |
| ADR-014 | 020 Observabilidad y SLO |
| Fuera de ADR | 021 Seguridad perimetral/zero trust y 022 Cifrado/llaves/secretos → documento de seguridad; 023 Costos → estimación de costos; 024 Herramienta C4 y 025 CI/CD e IaC → anexo del PDF |

## Ajustes de diseño incorporados (2026-10-19)

Decisiones de `../03-nube-ha-dr-monitoreo-costos.md` §10 incorporadas en los ADR:

| Decisión | Efecto en los ADR |
|---|---|
| `svc-dispositivos` (contexto Identidad) | ADR-001 §3 (catálogo), ADR-003 (`bff-movil` solo atestación + DCR), ADR-013 (Tier 1) |
| *Authorizer* *fail-static* (modo degradado) | ADR-003 §3 (lista de denegación y modo degradado), ADR-013 §3.4 |
| Global Datastore del ElastiCache de seguridad | ADR-013 §3.2 |
| SLO 99,9 % en login y transferencia propia; IdP ≥ 99,99 % solicitado a BP | ADR-013 §1, ADR-014 §3, `01-requisitos.md` S-14 |
| Descartada: se mantiene el clúster de *routing control* de ARC) | Sin cambio en ADR-013 |

## Supuestos nuevos introducidos en el diseño

| ID | Supuesto | Origen |
|---|---|---|
| S-27 | BP autoriza la lectura del *log* transaccional de una réplica/standby de la BD del Core mediante CDC (motor soportado por AWS DMS), sin modificar el Core | ADR-004 |
| S-28 | El Core acepta una referencia única por asiento (deduplica) o permite consultar un asiento por referencia | ADR-009 |

## Estado de servicios AWS verificado (2026-10-18)

| Servicio | Estado | Consecuencia |
|---|---|---|
| Amazon QLDB | Fin de soporte (2025-07-31) | No se usa para auditoría (ADR-012) |
| AWS CloudTrail Lake | Cerrado a nuevos clientes desde 2026-05-31 | No se usa para auditoría (ADR-012) |
| Amazon Pinpoint | Fin de soporte el 2026-10-30; SMS/*push* continúan como AWS End User Messaging | Se usa End User Messaging + SES (ADR-010) |
| AWS App Mesh | Fin de soporte (2026-09-30) | Resiliencia con Resilience4j + ECS Service Connect (ADR-013) |
