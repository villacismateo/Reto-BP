# ADR-005 — SPA: framework y arquitectura de la aplicación web

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-09, RF-11, RNF-05, RNF-07, RNF-12, RNF-17, S-04, S-26 | 1, 6, 10, 13 | Contenedores (`spa-web`, S3 + CloudFront), Componentes de la SPA (módulos de funcionalidad, cliente HTTP, gestión de sesión) |

## 1. Contexto

"Este sistema contará con 2 aplicaciones en el Front, una SPA…" (RF-09). La SPA atiende ~30 % del tráfico (S-04) y debe ofrecer las mismas capacidades de negocio que el móvil salvo onboarding (RNF-17). Fuerzas:
- **Seguridad**: es el canal más expuesto a XSS, *clickjacking* y robo de sesión; por ADR-007 los tokens **no** viven en el navegador (cookie de sesión `HttpOnly` gestionada por `bff-web`).
- **Mantenibilidad a largo plazo** (la banca vive 10+ años con su front), varios equipos de dominio contribuyendo (S-26), accesibilidad y consistencia visual.
- **Rendimiento**: carga inicial rápida en redes domésticas ecuatorianas, despliegues frecuentes sin *downtime*.

Preguntas: (a) ¿Angular o React? (b) ¿SPA modular o micro-frontends?

## 2. Opciones evaluadas

### 2.1 Framework

| Criterio (peso) | A. Angular (v20+, *standalone*, *signals*) | B. React (+ Next.js/Vite y librerías a elección) |
|---|---|---|
| Estructura y convenciones para equipos grandes (DI, *routing*, formularios, HTTP integrados) (25) | 5 | 3 |
| Seguridad incorporada (sanitización por defecto, Trusted Types, protección XSRF en `HttpClient`) (20) | 5 | 3 |
| Mantenibilidad / ciclo de soporte predecible (LTS, migraciones asistidas `ng update`) (20) | 4 | 3 |
| Ecosistema y disponibilidad de talento (15) | 4 | 5 |
| Rendimiento (carga diferida, SSR/hidratación, *zoneless*) (10) | 4 | 4 |
| Sinergia con la app móvil elegida (10) | 2 | 3 |
| **Puntaje ponderado** | **4,25** | **3,40** |

- **Angular**: *framework* completo y opinado (TypeScript estricto, inyección de dependencias, *router*, formularios reactivos, `HttpClient` con interceptores), lo que homogeneiza el código entre equipos; sanitización contextual por defecto y soporte de Trusted Types; calendario de versiones y LTS publicado. Contras: curva de aprendizaje mayor y *bundle* base algo mayor.
- **React**: librería de UI con el mayor ecosistema y talento disponible; flexibilidad total. Contras: cada equipo elige *routing*, estado, formularios y HTTP, lo que fragmenta la base de código; la seguridad depende más de disciplina (p. ej. `dangerouslySetInnerHTML`).

### 2.2 Arquitectura de la SPA

| Opción | Pros | Contras |
|---|---|---|
| **SPA modular en monorepo (Nx) con módulos de funcionalidad *lazy-loaded*** | Un solo *build* y despliegue; límites de módulo verificados por reglas de dependencias; carga diferida por funcionalidad; UX y diseño consistentes | Un despliegue para todos los módulos (mitigado con CI rápida y *feature flags*) |
| Micro-frontends (Module Federation) | Despliegue independiente por equipo | Complejidad de versiones compartidas, rendimiento, consistencia visual y superficie de seguridad (varios orígenes de código); se justifica con muchos equipos de front independientes, no con 1–2 |

## 3. Decisión

1. **Angular** (versión LTS vigente, componentes *standalone*, *signals*, TypeScript estricto).
2. **SPA modular en monorepo Nx**, con módulos de funcionalidad cargados de forma diferida: `posicion`, `movimientos`, `transferencias`, `pagos`, `perfil`, `seguridad`; librerías compartidas `ui-kit` (sistema de diseño, WCAG 2.2 AA), `data-access` (clientes generados desde el OpenAPI de `bff-web`) y `util-seguridad`. Reglas de dependencias de Nx impiden que un módulo de funcionalidad importe a otro. Micro-frontends quedan como evolución si el número de equipos de front supera ~3.
3. **Hosting**: S3 privado + **CloudFront con Origin Access Control**, *assets* inmutables con *hash*, *response headers policy* (CSP estricta con *nonces*, HSTS, `X-Frame-Options: DENY`/`frame-ancestors 'none'`, `Referrer-Policy`, `Permissions-Policy`), AWS WAF en la distribución.
4. **Sesión**: la SPA **no** maneja tokens; usa la cookie `__Host-sid` (`HttpOnly; Secure; SameSite=Strict; Path=/`) emitida por `bff-web` (*token handler*, cliente confidencial, ADR-007), con protección CSRF adicional (cabecera personalizada + verificación de `Origin` en el BFF). Ante un `401` de *step-up* (RFC 9470) la SPA redirige al flujo de autorización que inicia el BFF. Interceptores HTTP añaden `X-Request-Id` e *idempotency key* en operaciones monetarias.
5. **Observabilidad del cliente**: CloudWatch RUM (Core Web Vitals, errores JS) correlacionado con trazas del backend (ADR-014).

## 4. Justificación teórica

1. **OWASP Top 10 (A03 Inyección/XSS) y OWASP ASVS v4.0.3 (V3 sesiones, V14 configuración/cabeceras)**: sanitización contextual por defecto, CSP y tokens fuera del alcance de JavaScript reducen el impacto de XSS — el riesgo dominante en una SPA bancaria.
2. **ISO/IEC 25010 (mantenibilidad: modularidad, reusabilidad, modificabilidad)**: un *framework* opinado con DI y módulos con límites verificados reduce el acoplamiento accidental entre equipos.
3. **Ley de Conway y Team Topologies** (Skelton & Pais, 2019): micro-frontends son una respuesta organizacional a muchos equipos de front autónomos; con 1–2 equipos el costo cognitivo y de coordinación supera el beneficio (también advertido por Cam Jackson, "Micro Frontends", martinfowler.com, 2019).
4. **AWS Well-Architected, pilar de Eficiencia del Rendimiento**: contenido estático en el borde (CloudFront) con caché inmutable minimiza latencia y costo.

## 5. Consecuencias

**Positivas:** base de código homogénea; superficie XSS mínima y sin tokens robables; despliegue estático barato (S3 + CloudFront ≈ decenas a pocos cientos de USD/mes); *rollback* instantáneo invalidando el `index.html`.

**Negativas / trade-offs:** curva de Angular; no se reutiliza código de UI con la app Flutter (se reutilizan contratos OpenAPI, sistema de diseño a nivel de *tokens* y reglas de validación en el BFF).

| Riesgo | Mitigación |
|---|---|
| CSP estricta rompe librerías de terceros | Inventario de dependencias, sin *scripts* de terceros en páginas transaccionales, SRI |
| Cadena de suministro npm | SCA en CI (Dependabot/Snyk), *lockfile*, registro interno espejo |
| Un único despliegue para todos los módulos | *Feature flags*, despliegue continuo con pruebas E2E en *staging* |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| SPA modular / Lazy Loading | Módulos de funcionalidad Angular |
| Token Handler (BFF) | Sesión vía `bff-web` |
| Static Content Hosting | S3 + CloudFront OAC |
| Interceptor (cadena de responsabilidad) | Correlación, CSRF, *idempotency key* |

## 7. Equivalente Azure

Azure Static Web Apps o Blob Storage + Azure Front Door (WAF, reglas de cabeceras).

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RF-09 | Contenedores (`spa-web`, CloudFront/S3) | §5 · §8 Front y móvil |
| RNF-17 | Componentes de la SPA | §6 · §8 |
| RNF-05, RF-11 | Secuencia de login web (token handler) | §7 · §8 Seguridad |
