# ADR-003 — Capa de integración: API Gateway + BFF por canal

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-17, RF-21, RNF-05, RNF-10, RNF-12, RNF-17, R-04, S-04, S-06 | 1, 3, 4, 5, 6, 10 | Contenedores (CloudFront, WAF, API Gateway + *Lambda authorizer*, `bff-web`, `bff-movil`, `svc-dispositivos`), Componentes de `bff-web` / `bff-movil` / *authorizer* |

## 1. Contexto

"El sistema pasa por una capa de integración compuesta por un api Gateway y consumir los servicios necesarios de acuerdo con el tipo de transacción" (RF-17, R-04). Hay dos canales con necesidades distintas (S-04: 70 % móvil / 30 % web):

- **Web (SPA)**: la dirección de seguridad (ADR-007) es **no guardar tokens en el navegador** (patrón *token handler*: cookie `HttpOnly` y el *access token* custodiado en el servidor).
- **Móvil**: **cliente confidencial por instancia** (cada instalación registrada en el IdP por DCR, RFC 7591, con `private_key_jwt` y llave en hardware; ADR-007) con *access token* propio ligado por DPoP, *device binding* y redes móviles con latencia variable → necesita respuestas agregadas y compactas (una llamada por pantalla).
- Las pantallas combinan datos de varios servicios (posición consolidada = cliente + productos + saldos + últimos movimientos).

Fuerzas: 1.000 req/s de diseño (S-06); protección del borde (WAF, anti-DDoS, *rate limiting*); evitar que el front conozca la topología interna; equipos de canal que evolucionan su API sin coordinar con todos los dominios.

## 2. Opciones evaluadas

### 2.1 Patrón de la capa de entrada

| Criterio (peso) | A. API Gateway único, API genérica | B. API Gateway + BFF por canal | C. GraphQL federado (AppSync/Apollo) |
|---|---|---|---|
| Seguridad por canal (token handler web, superficie expuesta) (25) | 3 | 5 | 3 |
| Adaptación de *payload* y agregación por pantalla (20) | 2 | 5 | 5 |
| Rendimiento en móvil (menos *round-trips*) (15) | 2 | 4 | 4 |
| Autonomía de equipos / desacoplamiento (15) | 2 | 4 | 3 |
| Complejidad operativa (15) | 5 | 3 | 2 |
| Costo (10) | 5 | 3 | 3 |
| **Puntaje ponderado** | **3,00** | **4,20** | **3,40** |

- **A. Gateway único**: simple y barato, pero obliga a una API "mínimo común denominador": el móvil hace muchas llamadas, la SPA tendría que manejar tokens en el navegador y cada cambio de canal toca servicios de dominio.
- **B. Gateway + BFF por canal**: el gateway se encarga de lo transversal (WAF, *throttling*, validación, enrutamiento) y cada BFF agrega, adapta y aplica la política de sesión de su canal. Contra: dos servicios más y riesgo de duplicar lógica entre BFF.
- **C. GraphQL federado**: flexible para el front, pero complica el control de costo de consultas, el *caching* HTTP, la autorización por campo y el análisis de seguridad (consultas anidadas, introspección); menor madurez del equipo asumida.

### 2.2 Producto de API Gateway

| Opción | Pros | Contras |
|---|---|---|
| **Amazon API Gateway REST API (regional)** | Gestionado y multi-AZ; integración con AWS WAF; *usage plans* y *throttling* por ruta/cliente; validación de esquema de solicitud; dominio propio con mTLS; *VPC Link* a balanceador interno; *access logs* | ~USD 3,50 por millón de llamadas (≈ USD 420/mes para ~120 M llamadas/mes, S-06) |
| Amazon API Gateway HTTP API | ~70 % más barato; *JWT authorizer* nativo | Sin asociación directa con WAF ni *usage plans*/validación de esquema |
| Gateway autogestionado (Kong / Spring Cloud Gateway en ECS) | Portabilidad, *plugins* | Se opera, parchea y escala como un servicio crítico más; HA y seguridad son responsabilidad propia |

## 3. Decisión

**Opción B** con **Amazon API Gateway REST API regional** como única puerta de entrada al backend:

```
SPA / App ─HTTPS(TLS 1.2+)─→ CloudFront + AWS WAF + Shield ─→ API Gateway (REST, regional, WAF, throttling)
           ─VPC Link─→ ALB interno ─→ bff-web | bff-movil (ECS) ─HTTP/2 + TLS 1.3 (Service Connect) + JWT (+ prueba DPoP desde bff-web)─→ svc-* / acl-* ─mTLS─→ on-premise
```

| Responsabilidad | API Gateway | BFF |
|---|---|---|
| WAF (OWASP, *bots*, geobloqueo opcional), anti-DDoS | Sí (con CloudFront) | — |
| *Rate limiting* por IP, por cliente y por ruta | Sí (WAF + *usage plans*) | Límite de negocio por cliente (p. ej. intentos de transferencia) |
| Validación de esquema de entrada, tamaño de *payload* | Sí | Validación semántica |
| Autenticación | Móvil: *Lambda authorizer* que valida el JWT (firma con JWKS en caché, `iss`, `aud`, `exp`, `nbf`, `acr`) y la **prueba DPoP** (`htm`, `htu`, `iat`, `jti` con caché anti-*replay* en ElastiCache); si el `acr` no alcanza el mínimo de la ruta responde `401 insufficient_user_authentication` con `acr_values` (**RFC 9470**, *step-up*, ADR-007). Web (`/web/v1/*`): exige cookie `__Host-sid` y cabecera anti-CSRF; no hay llamada `bff-web` → gateway: `bff-web` llama a los servicios por Service Connect y, en web, el reto de *step-up* (RFC 9470) lo emite el servicio de dominio | `bff-web` = **cliente OAuth confidencial** (`private_key_jwt`, llave en KMS), sesión y tokens cifrados en **ElastiCache Multi-AZ con cifrado en reposo y tránsito**, verificación de `Origin`, *access* y *refresh token* ligados por **DPoP con una llave efímera por sesión** (cifrada en el ElastiCache de seguridad) y una **prueba DPoP en cada llamada interna**, refresh **sin rotación** (FAPI 2.0 §5.3.2.1), inactividad 5 min / absoluta 30 min y *step-up* web por redirección a `/authorize` (ADR-007 §3.6). `bff-movil` = **atestador y registrador de instancias**: valida App Attest / Play Integrity y registra cada instalación en el IdP por **DCR (RFC 7591)** con *initial access token* y *software statement* firmado (llave en KMS); impone en servidor la **inactividad de 2 min** (sesión bloqueada ⇒ `401` hasta re-autenticación). La **política de dispositivos** (máx. 3, enfriamiento, revocación) y la **lista de denegación** son reglas de dominio y viven en **`svc-dispositivos`** (contexto Identidad, §10 del documento 03): `bff-movil` le pide la autorización de alta antes de cada DCR y le delega las revocaciones. **Respaldo** si el IdP no soporta DCR: `bff-movil` actúa como cliente OAuth confidencial (`private_key_jwt`) y mantiene con la app una sesión ligada a la llave del dispositivo (ADR-007 §3.3). Ambos BFF propagan el *access token* (`bff-web` siempre con prueba DPoP); **cada microservicio re-valida `aud`/*scopes* y la propiedad del recurso** (la cuenta pertenece al `sub`, defensa contra BOLA — OWASP API1) y, para el canal web, verifica JWT, `acr` y la prueba DPoP contra `cnf.jkt` con la **librería común de validación** (anti-*replay* de `jti` en el ElastiCache de seguridad) |
| Agregación / adaptación por pantalla | — | Sí (composición paralela con *timeouts* y respuestas parciales) |
| Enrutamiento por tipo de transacción | Rutas `/web/v1/*` y `/movil/v1/*` | Ruta a `svc-transferencias` según tipo (propia/interbancaria/pago) |
| Correlación | Genera/propaga `traceparent` (W3C) y `X-Request-Id` | Propaga a servicios internos |

Nota de implementación: como la prueba DPoP cambia en cada solicitud, **no** se cachea el resultado del *authorizer* por token (anularía la verificación de `jti`); solo se cachean las JWKS.

**Lista de denegación y modo degradado del *authorizer*** (decisiones de §10 del documento 03): además de la firma y DPoP, el *authorizer* rechaza (`401 instance_revoked`) los tokens cuyo `client_id`/`jkt` figure en la lista de denegación que `svc-dispositivos` escribe en el ElastiCache de seguridad. El *authorizer* mantiene **una copia en memoria** de esa lista (refresco cada 15–30 s). Si cae todo el clúster de seguridad entra en **modo *fail-static***: aplica la última copia conocida; las **operaciones monetarias son *fail-closed***; en lecturas solo omite la verificación de `jti` y únicamente para pruebas con `iat` ≤ 60 s y tokens ≤ 5 min; el modo **expira a los 15 min** y pasa a *fail-closed* total salvo extensión manual, y emite `auth.degraded_mode` (alarma P1 + SOC). Un entorno nuevo (arranque en frío) carga la lista desde DynamoDB y, si no puede, queda *fail-closed*. La librería común de los servicios aplica la misma política al `jti` DPoP del canal web. Detalle operativo en ADR-013 §3.4 y `03` §2.1 (F12).

Reglas: (1) **ningún** servicio interno es accesible desde Internet (subredes privadas, *security groups* que solo aceptan al ALB interno o a Service Connect); (2) los BFF **no contienen reglas de negocio** de dominio (solo composición y presentación); (3) contratos OpenAPI versionados por canal.

La PoC reemplaza API Gateway por un gateway local (Spring Cloud Gateway) que demuestra enrutamiento, *rate limiting* y propagación de *correlation id* (A-8).

## 4. Justificación teórica

1. **Backends for Frontends** — Sam Newman, "Pattern: Backends For Frontends" (2015) y *Building Microservices* 2.ª ed., cap. 14: un backend por experiencia de usuario evita la API de propósito general que no sirve bien a ningún canal y permite que el equipo de canal sea dueño de su API.
2. **API Gateway** — Richardson, *Microservices Patterns* (2018), cap. 8: punto único de entrada que encapsula la estructura interna, centraliza preocupaciones transversales (autenticación, *rate limiting*, *routing*) y reduce *round-trips* del cliente.
3. **RFC 10017 — OAuth 2.0 for Browser-Based Applications** (IETF, agosto 2026; patrón *Backend For Frontend*, coherente con ADR-007) y **OWASP ASVS 5.0 V7 (Gestión de sesiones)**: mantener los tokens fuera del alcance de JavaScript elimina su robo vía XSS; cookies `HttpOnly`, `Secure`, `SameSite`.
4. **Defensa en profundidad / Zero Trust** — NIST SP 800-207: no confiar en la red interna; cada salto se cifra (TLS 1.3, mTLS al cruzar hacia on-premise), se autentica por token y cada servicio re-valida autorización (detalle en `02-seguridad-y-normativa.md` §2.5); AWS Well-Architected, pilar de Seguridad (SEC05/SEC06: proteger la red en capas y el cómputo).

## 5. Consecuencias

**Positivas:** superficie de ataque mínima y centralizada; móvil con una llamada por pantalla; cambios de UI no tocan dominios; *throttling* protege al Core aguas abajo (junto con el *rate limiter* del ACL, ADR-004).

**Negativas / trade-offs:** un salto adicional (~5–15 ms); dos BFF que mantener; costo de API Gateway REST mayor que HTTP API (se paga por WAF, *usage plans* y validación).

| Riesgo | Mitigación |
|---|---|
| BFF acumula lógica de negocio ("BFF gordo") | Revisión de arquitectura; lógica compartida solo en servicios de dominio (p. ej. la política de dispositivos se extrajo a `svc-dispositivos`, §10 del documento 03); *lint* de dependencias |
| BFF como punto único de falla | ≥3 tareas en 3 AZ, autoscaling, *bulkhead* por servicio aguas abajo, respuestas parciales con degradación (p. ej. posición sin detalle complementario) |
| Límite de 29 s de integración de API Gateway | Operaciones largas (interbancarias) son asíncronas: aceptación `202` + consulta de estado/notificación (ADR-009) |
| Duplicación entre BFF | Librería común de cliente resiliente y DTOs generados desde OpenAPI |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| API Gateway, Gateway Routing/Offloading | Amazon API Gateway + CloudFront/WAF |
| Backend for Frontend | `bff-web`, `bff-movil` |
| Token Handler (BFF de seguridad) | `bff-web` (ADR-007) |
| API Composition | Posición consolidada en BFF |
| Rate Limiting / Throttling | WAF, *usage plans*, BFF |
| Correlation Identifier (EIP) | `traceparent` en todo el recorrido |

## 7. Equivalente Azure

Azure Front Door + WAF → Azure API Management (tier con *zone redundancy*) → BFF en Azure Container Apps.

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RF-17, R-04 | Contenedores (CDN/WAF → API Gateway → BFF) | §5 C4 Contenedores · §10 Integración |
| RNF-17, RF-21 | Componentes `bff-movil` / `bff-web` | §6 · §8 Front y móvil |
| RNF-05 | Componentes (authorizer, token handler) · secuencia de login | §8 Seguridad · §7 Flujos |
