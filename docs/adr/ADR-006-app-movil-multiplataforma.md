# ADR-006 — App móvil multiplataforma

| Estado | Fecha | Requisitos | Criterios de rúbrica | Elementos C4 afectados |
|---|---|---|---|---|
| Aceptado (2026-10-19) | 2026-10-18 | RF-10, RF-11..RF-14, RNF-05, RNF-16, RNF-17, S-04, S-25 | 1, 6, 10, 11, 13 | Contenedores (`app-movil`), Componentes de la app (módulos de funcionalidad, módulo nativo de seguridad, SDK IDV, cliente HTTP) |

## 1. Contexto

"…una Aplicación móvil desarrollada en un Framework multiplataforma. (Mencione 2 opciones y justifique el porqué de su elección)" (RF-10). Es el canal principal (70 %, S-04) y el único con onboarding facial (RF-12).

Exigencias que fija la dirección de seguridad (ADR-007 y ADR-008), **neutral al framework** pero que este debe soportar sin fricción:
- Autorización en el **navegador del sistema** (`ASWebAuthenticationSession` / *Custom Tabs*), **nunca WebView**; redirección por **App Links / Universal Links**.
- **Llaves no exportables en hardware** (Secure Enclave / StrongBox): `K_inst` (credencial de la instancia de cliente OAuth: firma `client_assertion` `private_key_jwt` y pruebas **DPoP**) y `K_bind` (*device binding*, exige biometría fuerte en cada uso y se **invalida al cambiar la biometría enrolada**); desbloqueo con `BiometricPrompt` + `CryptoObject` / `LocalAuthentication` + `SecAccessControl .biometryCurrentSet`.
- **Sesión**: bloqueo tras 2 min de inactividad y **al pasar a segundo plano** (gracia ≤ 30 s), con contenido **oculto en el selector de apps**; reanudación con biometría (ADR-007 §3.4).
- **Atestación de la app** (App Attest / Play Integrity), **RASP** y *certificate pinning* (OWASP MASVS).
- Puente al **SDK de verificación de identidad**: FacePhi (recomendado), alternativas Veridas/Jumio, y Amazon Rekognition Face Liveness como motor alterno.

Fuerzas adicionales: una sola base de código para iOS y Android, UI consistente con el sistema de diseño, rendimiento en gamas medias, ciclo de vida largo.

## 2. Opciones evaluadas

| Criterio (peso) | A. Flutter | B. React Native (nueva arquitectura) | C. Kotlin Multiplatform (+ Compose Multiplatform) | D. .NET MAUI |
|---|---|---|---|---|
| Integración nativa de seguridad (llaves en hardware, biometría, atestación, RASP, *pinning*) (20) | 4 | 4 | 5 | 3 |
| Disponibilidad de SDK/*plugins* de proveedores IDV y de autenticación (AppAuth) (20) | 4 | 4 | 3 | 2 |
| Rendimiento y UI consistente entre plataformas (15) | 5 | 4 | 4 | 3 |
| Productividad / código compartido (UI + lógica) (15) | 5 | 4 | 3 | 4 |
| Madurez, ecosistema y talento (15) | 4 | 5 | 3 | 2 |
| Mantenibilidad y control de dependencias (15) | 4 | 3 | 4 | 3 |
| **Puntaje ponderado** | **4,30** | **4,00** | **3,70** | **2,80** |

- **A. Flutter (Dart)** — Pros: motor de renderizado propio ⇒ UI idéntica y fluida en ambas plataformas; compilación AOT a código nativo; fuerte soporte corporativo; ecosistema maduro para lo requerido (`flutter_appauth` sobre AppAuth con navegador del sistema, *platform channels*/Pigeon tipados para módulos nativos, ofuscación `--obfuscate`). Los proveedores IDV relevantes publican *plugins* Flutter o SDK nativos envolvibles. Contras: Dart es menos común que JS/TS; los componentes no son los nativos del sistema (se mitiga con el sistema de diseño propio); *plugins* de terceros deben auditarse.
- **B. React Native** — Pros: mayor bolsa de talento (JS/TS), componentes nativos reales, nueva arquitectura (JSI/TurboModules) con buen rendimiento; *plugins* IDV disponibles. Contras: mayor cadena de dependencias npm (superficie de cadena de suministro), *bridges* de terceros con mantenimiento irregular, actualizaciones de versión históricamente costosas; sin sinergia de código con la SPA Angular (ADR-005).
- **C. Kotlin Multiplatform** — Pros: la mejor integración nativa (lógica compartida en Kotlin, acceso directo a APIs de plataforma), ideal para seguridad. Contras: Compose Multiplatform en iOS es más reciente; menos *plugins* de proveedores; requiere más conocimiento nativo por plataforma.
- **D. .NET MAUI** — Pros: ecosistema .NET. Contras: menor adopción en banca móvil, menos SDK de terceros y rendimiento/estabilidad inferiores a A–C.

**Las dos opciones finalistas que pide el enunciado son Flutter y React Native.**

## 3. Decisión

**Flutter**, con React Native como alternativa evaluada (diferencia de 0,30 explicada por rendimiento/consistencia de UI, menor superficie de dependencias y mantenibilidad). Arquitectura de la app:

| Capa / componente | Decisión |
|---|---|
| Estructura | *Clean Architecture* por funcionalidad (`posicion`, `movimientos`, `transferencias`, `pagos`, `onboarding`, `seguridad`), estado con BLoC/Riverpod, inyección de dependencias, cliente HTTP generado desde el OpenAPI de `bff-movil` |
| **Módulo nativo de seguridad** (Kotlin/Swift vía Pigeon) | Generación en StrongBox/Secure Enclave de `K_inst` y `K_bind` (no exportables); firma de `client_assertion`, pruebas DPoP y retos de *device binding*; `BiometricPrompt` con `CryptoObject` / `LocalAuthentication` con `SecAccessControl` (biometría ligada criptográficamente, MASTG-BEST-0036) e invalidación de `K_bind` al cambiar la biometría (MASWE-0022); App Attest / Play Integrity + *key attestation* para el registro de instancia ante `bff-movil`. Las llaves **nunca** cruzan a Dart |
| Autenticación | `flutter_appauth` (AppAuth) con navegador del sistema, PKCE + PAR, `client_id` de la instancia y `client_assertion` (`private_key_jwt`) en `/par` y `/token`; App Links / Universal Links verificados (ADR-007) |
| Ciclo de vida de sesión | Bloqueo a los 2 min de inactividad y al pasar a segundo plano (gracia ≤ 30 s): descarta el *access token* de memoria y oculta el contenido en el selector de apps (`FLAG_SECURE` en Android, vista de privacidad en iOS); reanudación con biometría que desbloquea `K_bind` (ADR-007, secuencia de reanudación) |
| Onboarding | SDK IDV (FacePhi recomendado) mediante *plugin* oficial o envoltura nativa; puerto `IdentityVerificationSdk` con adaptador alterno para Rekognition Face Liveness (ADR-008). **Validación en la PoC del proveedor**: versión del *plugin* para Flutter y compatibilidad con la versión de Flutter adoptada |
| Protección de la app | RASP (detección de *root/jailbreak*, *hooking*, emulador, depuración), *certificate pinning* por SPKI con *pins* de respaldo, ofuscación, sin secretos embebidos, almacenamiento seguro (Keychain/Keystore) solo para datos mínimos |
| Red y resiliencia | *Timeouts* por llamada, reintentos solo en GET, *Idempotency-Key* generada en el cliente para operaciones monetarias (ADR-009), modo degradado (posición en caché cifrada de solo lectura, sin operar sin conexión) |
| Push | FCM / APNs con *token* registrado en `svc-notificaciones` (ADR-010); el contenido sensible no viaja en el *payload* |
| Distribución | App Store / Google Play; versión mínima forzada vía configuración remota; sin descarga de código ejecutable fuera de las tiendas |
| Calidad | Pruebas de *widgets* e integración, MASVS L2 + R verificado con MASTG antes de cada *release* |

## 4. Justificación teórica

1. **OWASP MASVS 2.x / MASTG** (MASVS-STORAGE, -CRYPTO, -AUTH, -NETWORK, -RESILIENCE): la app debe usar almacenamiento y criptografía respaldados por hardware, autenticación en navegador del sistema, *pinning* y resistencia a manipulación; Flutter lo satisface delegando lo sensible a un módulo nativo y no a código multiplataforma.
2. **RFC 8252 — OAuth 2.0 for Native Apps**: exige agente de usuario externo (navegador del sistema) y redirección por esquemas HTTPS verificados; AppAuth (disponible para Flutter) es la implementación de referencia del propio OpenID Foundation.
3. **ISO/IEC 25010 (portabilidad, mantenibilidad y eficiencia de desempeño)**: una base de código con renderizado propio maximiza la portabilidad y la consistencia; la compilación AOT favorece la eficiencia en dispositivos de gama media.
4. **Gestión de riesgo de cadena de suministro** — NIST SSDF (SP 800-218, práctica PW.4: reutilizar software bien asegurado) y OWASP Top 10 A06 (componentes vulnerables): menor número de dependencias transitivas reduce la superficie de ataque frente a la opción basada en npm.

## 5. Consecuencias

**Positivas:** una base de código, UI consistente, rendimiento nativo; seguridad crítica aislada en código nativo auditable; proveedor IDV reemplazable por puerto/adaptador.

**Negativas / trade-offs:** dos pequeños módulos nativos que mantener (Kotlin y Swift); talento Dart más escaso que JS/TS; dependencia del calendario de *plugins* de terceros.

| Riesgo | Mitigación |
|---|---|
| El proveedor IDV no publica *plugin* Flutter para la versión requerida | Envolver el SDK nativo con Pigeon (costo acotado); criterio de adjudicación en ADR-008 |
| *Plugins* de terceros inseguros o abandonados | Lista blanca de paquetes, SCA en CI, *fork* interno de los críticos |
| Ingeniería inversa de Dart AOT | Ofuscación + RASP + decisión siempre en servidor; ningún secreto en la app |
| Diferencias de comportamiento biométrico entre fabricantes Android | Matriz de dispositivos de prueba (gama media/baja del mercado local); respaldo clave + OTP |

## 6. Patrones aplicados

| Patrón | Dónde |
|---|---|
| Clean Architecture / Ports & Adapters | Capas de la app; puerto del SDK IDV |
| Device Binding, Proof-of-Possession (DPoP) | Módulo nativo de seguridad |
| Backend for Frontend | Consumo exclusivo de `bff-movil` |
| Idempotency Key (cliente) | Transferencias y pagos |
| Graceful Degradation | Posición en caché de solo lectura sin conexión |

## 7. Equivalente Azure

Igual en el cliente (Flutter); backend del canal en Azure Container Apps y *push* vía Azure Notification Hubs.

## 8. Trazabilidad

| Requisito | Diagrama C4 | Sección del PDF |
|---|---|---|
| RF-10 | Contenedores (`app-movil`) · tabla 2 | §5 · §8 Front y móvil |
| RF-11, RF-13 | Componentes de la app (módulo de seguridad) · secuencia de login móvil (ADR-007) | §6 · §7 · §8 |
| RF-12, RF-14 | Componentes (SDK IDV) · secuencia de onboarding (ADR-008) | §8.x Onboarding |
| RNF-17 | Componentes (módulos de funcionalidad equivalentes a la SPA) | §8 |
