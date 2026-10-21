# 01 · Requisitos, supuestos y trazabilidad

> **Proyecto:** Banca por internet para la entidad BP (reto de arquitectura de solución).
> **Contenido:** requisitos, supuestos, restricciones y trazabilidad.
> **Fuente de verdad:** [`00-enunciado.md`](00-enunciado.md) (transcripción fiel de `docs/enunciado/pagina-1..5.jpeg`). Toda cita textual de este documento proviene de ahí.
> **Fecha:** 2026-10-18 · **Estado:** versión final, con las ambigüedades del enunciado resueltas (§6) y la lista de ADR consolidada (§8).
> **Alcance:** este documento fija requisitos, supuestos y restricciones; las decisiones de arquitectura se documentan en los ADR.

## Convenciones

| Prefijo | Significado |
|---|---|
| `RF-xx` | Requisito funcional del sistema |
| `RNF-xx` | Requisito no funcional (atributo de calidad) |
| `RE-xx` | Requisito de **entregable** del reto (forma de la respuesta, no del sistema) |
| `S-xx` | Supuesto declarado. **Todos están "a validar con BP"** |
| `R-xx` / `FA-xx` | Restricción / Fuera de alcance |
| `A-xx` | Ambigüedad del enunciado que requiere decisión o validación |

**Prioridad MoSCoW:** **M** = Must (exigido explícitamente), **S** = Should (implícito o fuertemente recomendado), **C** = Could (valor agregado opcional), **W** = Won't (esta entrega).
**"(derivado)"** = requisito no literal, inferido de la cita indicada; se marca para que el lector vea el razonamiento.

---

## 1. Requisitos funcionales (RF)

| ID | Requisito | Cita textual del enunciado | Prio. | Criterio de aceptación verificable |
|---|---|---|---|---|
| RF-01 | Consultar el **histórico de movimientos** de las cuentas del cliente. | "los usuarios podrán acceder al histórico de sus movimientos" | M | Dado un cliente autenticado con ≥1 cuenta, el canal muestra sus movimientos paginados, filtrables por rango de fechas, con la profundidad definida en S-08; el diagrama de componentes muestra el servicio y su origen de datos. |
| RF-02 | Realizar **transferencias entre cuentas propias**. | "realizar transferencias y pagos entre cuentas propias e interbancarias" | M | Una transferencia propia válida queda confirmada al cliente con número de comprobante; un reintento con la misma *idempotency key* no duplica el débito (ver RNF-13). |
| RF-03 | Realizar **transferencias interbancarias** (hacia otras entidades financieras). | "realizar transferencias y pagos entre cuentas propias e interbancarias" | M | La transferencia interbancaria se registra, se envía a la red interbancaria y su estado final (acreditada / rechazada / reversada) se refleja en el canal y se notifica; ningún estado intermedio deja el saldo inconsistente. |
| RF-04 | Realizar **pagos** entre cuentas propias e interbancarios (ver ambigüedad A-1 sobre el alcance de "pagos"). | "realizar transferencias y pagos entre cuentas propias e interbancarias" | M | El diseño soporta al menos pago a producto propio (p. ej. tarjeta/préstamo de BP) y pago a terceros en otra entidad, reutilizando el mismo flujo transaccional de RF-02/RF-03. |
| RF-05 | Consultar **datos básicos del cliente** desde la plataforma Core. | "una plataforma Core que contiene información básica de cliente, movimientos, productos" · "3 servicios principales, consulta de datos básicos…" | M | Existe un servicio de "consulta de datos básicos" expuesto vía API Gateway cuya fuente autoritativa es el Core. |
| RF-06 | Consultar **información complementaria/detallada del cliente** desde el sistema independiente, solo cuando se requiera detalle. | "un sistema independiente que complementa la información del cliente cuando los datos se requieren en detalle" | M | La vista de detalle del cliente combina Core + sistema complementario; la vista resumida **no** invoca el sistema complementario (verificable en el diagrama de secuencia). |
| RF-07 | Consultar los **productos** del cliente (cuentas y otros) desde el Core. | "una plataforma Core que contiene información básica de cliente, movimientos, productos" | M | La posición consolidada muestra los productos del cliente con su saldo disponible obtenido del Core (o de su réplica de lectura con frescura declarada). |
| RF-08 | **Notificar al cliente sobre los movimientos realizados** usando **mínimo 2** sistemas de notificación (externos o propios). | "la norma exige que los usuarios sean notificados sobre los movimientos realizados, el sistema utilizará sistemas externos o propios de envío de notificaciones, mínimo 2" | M | Cada movimiento monetario genera una notificación entregada por ≥2 canales/proveedores; la caída de uno no impide la entrega por el otro; el envío queda registrado en auditoría. |
| RF-09 | Proveer una **aplicación web SPA**. | "Este sistema contará con 2 aplicaciones en el Front, una SPA" | M | El diagrama de contenedores incluye la SPA con su tecnología, hosting y protocolo hacia el backend. |
| RF-10 | Proveer una **aplicación móvil en framework multiplataforma**, mencionando **2 opciones** y justificando la elección. | "una Aplicación móvil desarrollada en un Framework multiplataforma. (Mencione 2 opciones y justifique el porqué de su elección)" | M | Existe un ADR con ≥2 frameworks evaluados, criterios explícitos y ≥2 justificaciones teóricas para el elegido. |
| RF-11 | **Autenticar** a los usuarios de ambas apps mediante un servicio **OAuth 2.0** existente y configurable, **recomendando el mejor flujo** según el estándar. | "Ambas aplicaciones autenticarán a los usuarios mediante un servicio que usa el estándar Oauth2.0 … la compañía cuenta con un producto que puede ser configurado para este fin; Sin embargo, debe dar recomendaciones sobre cuál es el mejor flujo de autenticación" | M | ADR que recomienda el flujo por tipo de cliente (SPA y app móvil), descarta explícitamente flujos inseguros con referencia al estándar (RFC/BCP), e incluye diagrama de secuencia del login. |
| RF-12 | **Onboarding de nuevos clientes en la app móvil con reconocimiento facial**, integrado al flujo de **autorización y autenticación**. | "el sistema de Onboarding para nuevos clientes en la aplicación móvil usa reconocimiento facial, por tanto, su arquitectura deberá considerarlo como parte del flujo de autorización y autenticación" | M | Diagrama de secuencia del onboarding que muestra: captura facial con prueba de vida, validación de identidad, alta del usuario en el IdP, enrolamiento del dispositivo y emisión del primer token. |
| RF-13 | Tras el onboarding, permitir el ingreso por **usuario y clave, huella u otro método**, **especificando** cuál(es) en la arquitectura. | "a partir del Onboarding el nuevo usuario podrá ingresar al sistema mediante usuario y clave, huella o algún otro método especifique alguno de los anteriores dentro de su arquitectura" | M | La arquitectura nombra los métodos soportados por canal (web/móvil), cuándo se exige un segundo factor o *step-up* y cómo se ligan al IdP. |
| RF-14 | **Recomendar herramientas de industria** para biometría/autenticación que robustezcan la solución. | "también puede recomendar herramientas de industria que realicen estas tareas y robustezca su aplicación" | S | Tabla comparativa de ≥2 herramientas (nube y/o terceros) con criterio de selección. |
| RF-15 | Registrar en una **base de datos de auditoría todas las acciones del cliente**. | "El sistema utiliza una base de datos de auditoría que registra todas las acciones del cliente" | M | Toda operación (login, consulta, transferencia, cambio de datos, onboarding, notificación) produce un evento de auditoría con actor, acción, recurso, resultado, timestamp, canal, IP/dispositivo y *correlation id*; el registro es inmutable (RNF-14). |
| RF-16 | **Persistencia de información para clientes frecuentes**, propuesta con **patrones de diseño** y relacionando los componentes que interactúan (ver ambigüedad A-2). | "cuenta con un mecanismo de persistencia de información para clientes frecuentes, para este caso proponga una alternativa basada en patrones de diseño que relacione los componentes que deben interactuar para conseguir el objetivo" | M | ADR con patrón(es) nombrados (p. ej. de caché/vista materializada), diagrama de componentes con los participantes, política de expiración/invalidación y comportamiento ante fallo de la caché. |
| RF-17 | **Capa de integración con API Gateway** para obtener los datos del cliente y enrutar según el tipo de transacción. | "el sistema pasa por una capa de integración compuesta por un api Gateway y consumir los servicios necesarios de acuerdo con el tipo de transacción" | M | Todo acceso desde los canales al backend pasa por el API Gateway (verificable en C4 contenedores); no hay llamadas directas del front a servicios internos. |
| RF-18 | Servicio de **consulta de datos básicos**. | "inicialmente usted cuenta con 3 servicios principales, consulta de datos básicos" | M | Aparece como contenedor/microservicio con su API, fuente y consumidores. |
| RF-19 | Servicio de **consulta de movimientos**. | "…consulta de movimientos…" | M | Ídem RF-18. |
| RF-20 | Servicio de **transferencias** que invoca **servicios externos según el tipo** (propia / interbancaria / pago). | "…y transferencias que realizan llamados a servicios externos dependiendo del tipo" | M | El diagrama de componentes muestra el ruteo por tipo hacia Core y red interbancaria, con patrones de resiliencia y compensación. |
| RF-21 | **Agregar servicios adicionales** cuando mejoren el rendimiento o la respuesta al cliente (p. ej. notificaciones, auditoría, onboarding, BFF, lectura optimizada). | "si considera que debería agregar más servicios para mejorar el rendimiento de su arquitectura o agregar más servicios para mejorar la respuesta de información a sus clientes, es libre de hacerlo" | C | Cada servicio agregado tiene su justificación en un ADR y su responsabilidad única declarada. |

**Total RF: 21** (19 M · 1 S · 1 C).

---

## 2. Requisitos no funcionales (RNF)

| ID | Requisito | Cita textual del enunciado | Prio. | Criterio de aceptación verificable |
|---|---|---|---|---|
| RNF-01 | **Cumplimiento normativo**: identificar y aplicar elementos normativos de entidades financieras (datos personales, seguridad, etc.). | "mencione aquellos elementos normativos que consideren importantes a tener en cuenta para entidades financieras, Ejemplo ley de datos personales, seguridad, etc." · "la norma exige que los usuarios sean notificados" | M | Sección de normativa con matriz *norma → obligación → control de arquitectura → componente*; normas locales citadas por nombre y verificadas (números de resolución solo si se verifican en fuente oficial). |
| RNF-02 | **Alta disponibilidad (HA)**. | "Garantía en su arquitectura, alta disponibilidad (HA)" | M | Ningún componente de la ruta crítica (login, consulta, transferencia) es punto único de falla dentro de la región; despliegue en ≥2 zonas de disponibilidad; SLO de disponibilidad según S-14. |
| RNF-03 | **Tolerancia a fallos** ante caída o lentitud de dependencias (Core, sistema complementario, red interbancaria, notificaciones, IdP, biometría). | "tolerancia a fallos" | M | Para cada dependencia externa se documenta timeout, reintento con *backoff*+*jitter*, *circuit breaker*, *bulkhead* y la degradación elegante que ve el cliente. |
| RNF-04 | **Recuperación ante desastres (DR)** con RTO/RPO declarados. | "recuperación ante desastres (DR)" | M | Tabla RTO/RPO por servicio (S-15) y estrategia DR justificada por costo vs. criticidad; procedimiento de *failover* descrito. |
| RNF-05 | **Seguridad** de extremo a extremo (perímetro, identidad, datos en tránsito y reposo, secretos, aplicación). | "Seguridad y Monitoreo" · "protocolos de comunicación con seguridad" | M | Cada flecha del diagrama de componentes indica protocolo y mecanismo de seguridad (TLS 1.2+/mTLS/JWT); cifrado en reposo con llaves gestionadas; modelo de amenazas (STRIDE) en el documento de seguridad. |
| RNF-06 | **Monitoreo / observabilidad**: métricas, logs, trazas, alertas. | "Seguridad y Monitoreo" · criterio "Implementación de Monitoreo" | M | SLO por *journey* (login, consulta, transferencia, onboarding), *golden signals* por servicio, trazas distribuidas con *correlation id* y alertas con *runbook* asociado. |
| RNF-07 | **Excelencia operativa**: despliegues automatizados, infraestructura como código, operación medible. | "Excelencia operativa" | M | Pipeline CI/CD e IaC descritos; despliegues sin downtime (blue/green o canary); revisión post-incidente. |
| RNF-08 | **Auto-healing**: recuperación automática de instancias/servicios fallidos sin intervención manual. | "auto-healing" | M | *Health checks* (liveness/readiness) y reemplazo automático de tareas/pods; *failover* automático de BD en la región; escalado automático. |
| RNF-09 | **Nube AWS con baja latencia** para usuarios de BP (decisión adoptada). | "su arquitectura puede contener elementos de infraestructura en la nube, Azure u AWS, garantía de baja latencia, cuenta con presupuesto para esto" | M | Región(es) justificadas con latencia estimada desde Ecuador (S-11); CDN en el borde; objetivos de latencia RNF-12 cumplibles. |
| RNF-10 | **Desacoplamiento, reutilización y cohesión** para incorporar componentes futuros. | "plantee una arquitectura desacoplada con elementos y reutilizables y cohesionados para otros componentes que puedan adicionarse en el futuro" | M | Servicios por *bounded context*, BD por servicio, contratos versionados, comunicación asíncrona donde no se necesita respuesta inmediata; un nuevo canal o producto se agrega sin modificar servicios existentes (demostrable en el diagrama). |
| RNF-11 | **Manejo de costos**: estimación y optimización del costo de la solución. | criterio "Manejo de costos" · "cuenta con presupuesto para esto" | M | Tabla de costo mensual por capa con supuestos de volumen y fuente (AWS Pricing Calculator / Price List API), palancas de optimización y comparación con el presupuesto S-16. |
| RNF-12 | **Rendimiento / tiempo de respuesta** al cliente. (derivado) | "agregar más servicios para mejorar el rendimiento de su arquitectura o … mejorar la respuesta de información a sus clientes" · "garantía de baja latencia" | S | Objetivos p95 de S-13 cumplidos en pruebas de carga sobre la volumetría S-04..S-07; el diseño indica qué componentes reducen latencia (caché, réplica de lectura, CDN). |
| RNF-13 | **Integridad e idempotencia de transacciones monetarias** (sin dobles débitos ni estados inconsistentes). (derivado) | "realizar transferencias y pagos" · "transferencias que realizan llamados a servicios externos" | M | *Idempotency key* obligatoria en transferencias/pagos; compensación definida para fallos parciales; prueba: reintentar N veces la misma solicitud produce un único movimiento. |
| RNF-14 | **Auditoría inmutable, completa y consultable** con retención acorde a la norma. (derivado) | "base de datos de auditoría que registra todas las acciones del cliente" · "elementos normativos … seguridad" | M | Registro *append-only* con protección contra borrado/alteración (WORM o equivalente), retención S-10, consulta por cliente/rango de fechas para auditor/regulador y verificación de integridad. |
| RNF-15 | **Escalabilidad elástica** para picos (quincena, fin de mes, feriados). (derivado) | "garantía de baja latencia" · "mejorar el rendimiento" | S | Escalado automático horizontal configurado para el pico S-06 con margen ≥2x; *queue-based load leveling* para cargas diferibles (notificaciones, auditoría). |
| RNF-16 | **Protección de datos personales** (minimización, finalidad, consentimiento, derechos del titular, cifrado). (derivado de RNF-01) | "Ejemplo ley de datos personales" | M | Clasificación de datos (PII/financiero/biométrico), cifrado en reposo y tránsito, enmascaramiento en logs, consentimiento explícito en onboarding para datos biométricos. |
| RNF-17 | **Usabilidad y consistencia entre canales** web y móvil. (derivado) | criterio "Calidad de arquitectura de aplicación front-end y móvil" | S | Mismas capacidades de negocio en ambos canales salvo onboarding (solo móvil, RF-12); contratos de API adaptados por canal. |

**Total RNF: 17** (14 M · 3 S).

---

## 3. Requisitos del entregable (RE)

| ID | Requisito | Cita textual | Prio. | Criterio de aceptación |
|---|---|---|---|---|
| RE-01 | Documento **PDF** bien organizado y fácil de leer. | "Cree un documento PDF … debe estar bien organizado y ser fácil de leer" | M | PDF con índice, numeración y secciones de §7.1. |
| RE-02 | **Imágenes** que expliquen los diagramas (p. ej. draw.io). | "Agregue imágenes para explicar los diagramas" | M | Todo diagrama embebido como imagen legible + texto explicativo. |
| RE-03 | Diagramas **C4 correctos y detallados**: Contexto (no técnicos), Contenedores (técnicos, con nube), Componentes (microservicios, patrones, protocolos con seguridad, nube). | "Cada uno de los diagramas de C4 es un entregable importante…" · definiciones de cada diagrama | M | 3 niveles C4 con leyenda, tecnología y protocolo en cada caja/flecha (contenedores y componentes). |
| RE-04 | **Otros diagramas** requeridos (infraestructura, a criterio). | "Preste también atención a los otros diagramas requeridos" · "la infraestructura la puede modelar como usted lo considera" | M | Diagrama de despliegue/infraestructura AWS + diagramas de secuencia de flujos críticos (S-24). |
| RE-05 | **Texto explicativo** suficiente. | "Asegúrese de agregar cualquier texto explicativo que considere necesario" | M | Cada diagrama y decisión con narrativa. |
| RE-06 | Cada decisión con **≥2 justificaciones teóricas** y **opciones evaluadas**. | "Cada decisión arquitectónica … debe tener una justificación teórica (Mínimo 2 por cada una) … ¿Qué opciones evaluaron?" | M | 100 % de ADR con ≥2 opciones y ≥2 fuentes/principios citados. |
| RE-07 | **Repositorio público en GitHub** con el PDF y URL en los comentarios del ejercicio. | "cree un repositorio público en Github y suba el PDF a ese repositorio. Colocar la URL…" | M | Repo público accesible sin autenticación, PDF en la raíz o `docs/`. |
| RE-08 | Entrega **dentro del tiempo de resolución**. | "Asegúrese de subirlo dentro del tiempo de resolución" | M | Entrega antes de 2026-10-20 ~11:40 (R-05). |
| RE-09 | Modelado **C4 hasta componentes**. | "describa hasta el modelo de componentes" | M | Nivel 4 (código) no requerido. |

---

## 4. Supuestos (todos "a validar con BP")

> Los valores numéricos son **estimaciones de dimensionamiento**; no provienen del enunciado. Se eligieron para un banco mediano-grande y se recalcularán si BP entrega cifras reales. Las fórmulas se muestran para que puedan reproducirse.

### 4.1 Negocio y volumetría

| ID | Supuesto | Valor | Razonamiento | Estado |
|---|---|---|---|---|
| S-01 | País y marco regulatorio de BP | **Ecuador** (persona jurídica regulada por la Superintendencia de Bancos) | El reto es local y menciona "la norma"; se usa normativa ecuatoriana + estándares internacionales. | A validar con BP |
| S-02 | Segmento de clientes en alcance | **Personas naturales** | El enunciado habla de "clientes" y onboarding en app móvil con reconocimiento facial (típico de personas naturales). Banca empresas fuera de alcance. | A validar con BP |
| S-03 | Clientes totales / usuarios digitales activos | **2.000.000** clientes; **800.000** activos mensuales en canales digitales (40 %); **250.000** activos diarios | Penetración digital típica de banca retail en la región (rango 30–50 %). | A validar con BP |
| S-04 | Mezcla de canales | **70 % móvil / 30 % web** | Tendencia de banca retail; impacta dimensionamiento de BFF. | A validar con BP |
| S-05 | Usuarios concurrentes | Promedio **8.000**; pico **25.000** sesiones concurrentes | 250k DAU × sesión media 5 min concentrada en ~10 h hábiles → ~2.000–8.000; pico ×3 en quincena/fin de mes. | A validar con BP |
| S-06 | Carga de API (gateway) | Promedio **~50 req/s**; hora pico **~150 req/s**; pico quincena/fin de mes **~600 req/s**; **diseño: 1.000 req/s** sostenidos | 8M sesiones/mes × 15 llamadas ≈ 4M llamadas/día ≈ 46 req/s; factor hora pico ×3; factor quincena ×4; margen ×1,7. | A validar con BP |
| S-07 | Transacciones monetarias (transferencias + pagos) | Promedio **~1 TPS** (≈80.000/día); pico quincena **~15 TPS**; **diseño: 50 TPS** | ~30 % de sesiones diarias hacen una operación monetaria; picos de pago de nómina. Interbancarias ≈ 40 % del total. | A validar con BP |
| S-08 | Volumen de movimientos | ~**120 M** movimientos/mes en el Core (2M clientes × 1,5 cuentas × 40 mov/mes); consulta en línea de **13 meses** | Determina tamaño del modelo de lectura y de la caché. | A validar con BP |
| S-09 | Eventos de auditoría | ~**160 M** eventos/mes (8M sesiones × ~20 acciones), ~1 KB c/u → **~160 GB/mes** sin comprimir | "todas las acciones del cliente" incluye consultas, no solo transacciones. | A validar con BP |
| S-10 | Retención de auditoría | **12 meses en caliente** (consultable en línea) + archivo inmutable hasta completar **10 años** | Se toma el plazo más exigente habitual de conservación de registros financieros y de prevención de lavado de activos; **plazo legal exacto a verificar**. | A validar con BP |

### 4.2 Nube, disponibilidad y presupuesto

| ID | Supuesto | Valor | Razonamiento | Estado |
|---|---|---|---|---|
| S-11 | Región AWS principal y de DR | Candidatas a evaluar en ADR: **us-east-1 (N. Virginia)** como principal y **us-east-2 (Ohio)** como DR; alternativas **sa-east-1 (São Paulo)** y **mx-central-1 (México)**. | No existe región AWS en Ecuador. La salida internacional de Ecuador se enruta mayoritariamente por cables submarinos hacia EE. UU. (Miami), por lo que la latencia de red estimada a us-east-1 (~60–90 ms RTT) suele ser menor que a São Paulo (~120–160 ms), cuya ruta muchas veces pasa también por EE. UU. **Cifras estimadas; a medir desde Quito/Guayaquil** (p. ej. pruebas de RTT a endpoints regionales) antes de decidir en el ADR-002. La residencia de datos fuera del país depende de S-21. | A validar con BP |
| S-12 | Conectividad nube ↔ on-premise | **2 enlaces AWS Direct Connect** (proveedores/ubicaciones distintas) + **VPN Site-to-Site** de respaldo | El Core y el sistema complementario permanecen en el centro de datos de BP; un único enlace sería punto único de falla. | A validar con BP |
| S-13 | Objetivos de latencia (p95, medidos en el gateway) | Login ≤ **1,5 s** (sin biometría); consulta de saldo/posición ≤ **500 ms** con caché, ≤ **1,2 s** sin caché; movimientos (primera página) ≤ **800 ms**; transferencia propia ≤ **2 s**; aceptación de interbancaria ≤ **2 s** (confirmación final asíncrona) | Valores de experiencia habituales en banca digital; el Core on-prem fija el piso de latencia en lecturas no cacheadas. | A validar con BP |
| S-14 | Disponibilidad objetivo (SLO) | **99,95 %** mensual para consultas (posición, movimientos) y aceptación de interbancarias (≈ 22 min/mes); **99,9 %** para **login y transferencia propia** (ajustado: el IdP y el Core en serie limitan a ≈ 99,90 %, `03` §2.2; se solicita a BP un IdP ≥ 99,99 % para subir el de login), onboarding y notificaciones | La disponibilidad compuesta depende del Core: se asume Core ≥ 99,95 % en horario y ventanas de mantenimiento programadas y comunicadas. | A validar con BP |
| S-15 | RTO / RPO por criticidad | **Tier 1** (auth, transferencias/pagos, auditoría): RTO ≤ **30 min**, RPO ≤ **1 min** (auditoría: RPO ≈ 0 intra-región). **Tier 2** (consultas, notificaciones, onboarding): RTO ≤ **1 h**, RPO ≤ **5 min**. **Tier 3** (reportería/analítica): RTO ≤ **24 h**, RPO ≤ **24 h** | Criticidad para el cliente y para el regulador; orienta la estrategia DR (ADR-013). | A validar con BP |
| S-16 | Presupuesto orientativo de nube | **USD 25.000 – 45.000 / mes** (producción + DR, sin licencias de IdP ni costo por transacción de biometría/SMS) | Orden de magnitud para la volumetría S-03..S-09; se contrastará con la estimación de costos (documento 03). "cuenta con presupuesto para esto". | A validar con BP |
| S-17 | Entornos | dev, qa, staging (pre-producción) y producción + DR, en **cuentas AWS separadas** (AWS Organizations) | Aislamiento de radio de impacto y de datos; buena práctica de Well-Architected. | A validar con BP |

### 4.3 Sistemas existentes e integraciones

| ID | Supuesto | Valor | Razonamiento | Estado |
|---|---|---|---|---|
| S-18 | **Core bancario** existente | On-premise, sistema de registro (fuente de verdad) de clientes, productos, saldos y movimientos; expone interfaz **conocida y documentada** (servicios REST/SOAP internos o mensajería); capacidad limitada estimada **~300 TPS de consultas**; ventanas batch nocturnas; **no se modifica**. | El enunciado lo presenta como dado; su capacidad finita justifica proteger el Core (caché, réplicas de lectura, *rate limiting*). | A validar con BP |
| S-19 | **Sistema complementario** de cliente | Sistema independiente (on-premise o SaaS) con API REST; datos de detalle (p. ej. datos demográficos ampliados, preferencias, contactos); disponibilidad menor que el Core (~**99,5 %**); se consulta **solo bajo demanda**. | "complementa la información del cliente cuando los datos se requieren en detalle". | A validar con BP |
| S-20 | **Producto OAuth existente** (IdP) | Servidor de autorización compatible con **OAuth 2.0 + OpenID Connect**, configurable, que soporta: Authorization Code + PKCE, **PAR** (RFC 9126), **DCR** (RFC 7591, registro dinámico protegido de instancias móviles), **DPoP** (RFC 9449), *refresh token* **ligado al remitente** (DPoP / credencial de instancia) con **rotación solo en modo degradado**, MFA, flujos/pasos de autenticación personalizables (para insertar biometría), emisión de JWT firmados y endpoint JWKS; alta disponibilidad provista por BP o el fabricante. | "la compañía cuenta con un producto que puede ser configurado para este fin". No se reemplaza; si carece de alguna capacidad se indicará como brecha. **Nota:** Amazon Cognito no soporta DCR ni PAR (verificado en `02-seguridad-y-normativa.md` §4) ⇒ si el producto fuera Cognito se usa el respaldo **BFF confidencial** (`bff-movil` como cliente `private_key_jwt`, 02 §4.1) y se declara la desviación FAPI 2.0. | A validar con BP |
| S-21 | Residencia de datos y nube | La normativa ecuatoriana **permite** procesar datos de clientes en nube fuera del país bajo condiciones (contrato, evaluación de riesgo de proveedor, garantías de transferencia internacional de datos, notificación/autorización al supervisor si aplica). | Condición necesaria para usar AWS; **norma exacta a verificar**. Si no se permitiera, cambia la topología (híbrida). | A validar con BP |
| S-22 | **Red interbancaria** | BP participa en el sistema de pagos interbancarios del país (administrado por el Banco Central del Ecuador; nombre y modalidad —lotes vs. tiempo real— **a verificar**), accesible desde la red de BP mediante un conector existente. | Las transferencias interbancarias requieren confirmación asíncrona y posible reverso. | A validar con BP |
| S-23 | **Proveedores de notificación** | Se dispone de (o se puede contratar) ≥2 canales: **push** (FCM/APNs), **correo electrónico** y **SMS**, con al menos un proveedor alterno. | Cumple "mínimo 2" con redundancia de canal y de proveedor (ver A-5). | A validar con BP |
| S-24 | "Otros diagramas requeridos" | Se entregarán: diagrama de **infraestructura/despliegue AWS**, diagramas de **secuencia** (login, onboarding, transferencia interbancaria, consulta con caché) y vista de **HA/DR**. | Interpretación de "Preste también atención a los otros diagramas requeridos" (nota T-5 del enunciado). | A validar con BP |
| S-25 | **Biometría** | Se usará un servicio de verificación facial con **prueba de vida (liveness)** y comparación 1:1 contra la foto del documento de identidad, y, si BP lo tiene habilitado, validación contra la fuente oficial de identidad del país (registro civil). **Proveedor a decidir en ADR**. | El enunciado exige reconocimiento facial en onboarding; la validación contra fuente oficial reduce suplantación. Disponibilidad del servicio oficial **a verificar**. | A validar con BP |
| S-26 | Equipos y Ley de Conway | Equipos de producto por dominio (cuentas/consultas, transferencias, identidad/onboarding, canales) + plataforma | Justifica la segmentación en *bounded contexts*. | A validar con BP |
| S-27 | **CDC del Core** (añadido en el diseño, ADR-004) | BP autoriza leer el *log* transaccional de una réplica/standby de la BD del Core mediante CDC (motor soportado por AWS DMS), **sin modificar el Core**. Alternativas si no: eventos de la mensajería del Core o carga *batch*. | Habilita el modelo de lectura CQRS de movimientos. | A validar con BP |
| S-28 | **Referencia única por asiento** (añadido en el diseño, ADR-009) | El Core acepta una referencia única por asiento (deduplica) o permite consultar un asiento por referencia. | Necesario para reintentos seguros de débitos/créditos (idempotencia extremo a extremo). | A validar con BP |
| S-29 | **Onboarding digital** (añadido en la estimación de costos) | ~**30.000** inicios/mes y ~**25.000** verificaciones biométricas (incluye re-verificación por alta de dispositivo) | Dimensiona WAF ACFP, proveedor IDV y costos fuera de AWS (`03-nube-ha-dr-monitoreo-costos.md` §5). | A validar con BP |
| S-30 | **Telemetría** (añadido en la estimación de costos) | ~700 GB/mes de *logs* de aplicación, ~1 TB/mes de *logs* vendidos (VPC Flow, WAF), ~13 M trazas, ~1.500 métricas | Principal impulsor del costo de observabilidad (ADR-014). | Estimado |
| S-31 | **Tráfico híbrido** (añadido en la estimación de costos) | ~2 TB/mes hacia on-prem por Direct Connect; ~0,5 TB/mes entre regiones | Justifica DX de 1 Gbps. | Estimado |
| S-32 | **Backoffice del centro de contacto** (añadido en el diseño de dispositivos) | La revocación asistida de dispositivos se hace por una **API de *backoffice*** de `svc-dispositivos` expuesta **solo por la red corporativa de BP / Direct Connect** (nunca por Internet), con **SSO corporativo + MFA** del asesor y su identidad registrada en la auditoría; la consola del centro de contacto queda fuera de alcance | Cierra el canal P3 → `svc-dispositivos` (`04-modelo-c4.md` R19, I-06) | A validar con BP |

**Total supuestos: 32** (S-27 y S-28 añadidos en el diseño de integración; S-29..S-31 en la estimación de costos; S-32 en el diseño de dispositivos).

---

## 5. Restricciones y fuera de alcance

### 5.1 Restricciones

| ID | Restricción | Origen |
|---|---|---|
| R-01 | Nube **AWS** (equivalentes Azure solo como referencia). | Decisión adoptada (el enunciado permite AWS o Azure). |
| R-02 | Se **reutiliza el producto OAuth existente**; no se construye un IdP propio. | Enunciado: "no requiere implementar toda la lógica". |
| R-03 | **Core y sistema complementario existentes**; se integran, no se reemplazan ni modifican. | Enunciado: "se tomará de 2 sistemas". |
| R-04 | Acceso de los canales a datos **solo vía capa de integración con API Gateway**. | Enunciado: "pasa por una capa de integración compuesta por un api Gateway". |
| R-05 | Plazo de entrega: **2026-10-20 ~11:40** (≈40 h desde 2026-10-18 ~19:40). | Restricción del ejercicio (tiempo de resolución). |
| R-06 | Repositorio **público**: sin datos reales de clientes, sin credenciales, sin información de terceros ni de otros proyectos del autor. | Enunciado (repo público) + política del autor. |
| R-07 | Modelo **C4** obligatorio hasta componentes. | Enunciado. |
| R-08 | Infraestructura como código **de referencia** (validación sintáctica), **sin aprovisionar** recursos reales. | Decisión adoptada (sin credenciales AWS; costo cero). |

### 5.2 Fuera de alcance

| ID | Fuera de alcance | Motivo |
|---|---|---|
| FA-01 | Banca empresas / multiusuario con esquemas de firmas. | S-02. |
| FA-02 | Originación de créditos, apertura de nuevos productos (salvo el alta de usuario digital del onboarding), inversiones, seguros. | No mencionados en el enunciado. |
| FA-03 | Pagos de servicios básicos / recaudaciones a terceros no bancarios (salvo que A-1 se resuelva en contrario). | Ambigüedad A-1. |
| FA-04 | Implementación productiva, pruebas de carga reales y aprovisionamiento en AWS. | Alcance = diseño + PDF + repo + PoC mínima. |
| FA-05 | Cambios internos del Core, del sistema complementario o del IdP. | R-02, R-03. |
| FA-06 | Construcción de un motor biométrico propio. | Se recomiendan herramientas de industria (RF-14). |
| FA-07 | Backoffice operativo completo (atención al cliente, conciliación contable); solo se modela el acceso del **auditor/operador** a la auditoría y monitoreo. | Fuera del enunciado; se menciona como actor en C4 contexto. |
| FA-08 | Diagramas C4 de nivel 4 (código). | "describa hasta el modelo de componentes". |

---

## 6. Ambigüedades del enunciado y resolución adoptada

> **Resolución adoptada:** se acepta la propuesta de la última columna para A-1 a A-9. Precisiones: A-1 pagos de servicios básicos fuera de alcance pero extensible (nueva *Strategy*, ADR-009); A-2 beneficiarios frecuentes como extensión (ADR-011); A-4 el IdP no se nombra (caja negra OIDC); A-6 se resolvió en ADR-014; A-8 la PoC demuestra gateway local + movimientos con *cache-aside* + transferencia idempotente con outbox + auditoría; A-9 anexo Azure sí (cada ADR trae su equivalente en una línea).

| ID | Ambigüedad | Opciones | Resolución adoptada |
|---|---|---|---|
| A-1 | Alcance de **"pagos"** ("transferencias y pagos entre cuentas propias e interbancarias"). | (a) Pagos = pagar productos propios (tarjeta, préstamo) y a terceros en BP u otros bancos. (b) Incluir también pagos de servicios básicos/recaudaciones. | **(a)**, dejando el diseño extensible para (b) mediante un adaptador de "tipo de pago". |
| A-2 | Significado de **"clientes frecuentes"**. | (a) Clientes que ingresan con frecuencia → persistir/cachear su perfil, productos y últimos movimientos para responder rápido y proteger el Core. (b) **Beneficiarios/contactos frecuentes** del cliente. (c) Ambos. | **(a) como respuesta principal** (encaja con "mecanismo de persistencia" y "patrones de diseño") y **(b) como extensión** breve. |
| A-3 | País/jurisdicción de BP. | Ecuador vs. genérico LATAM. | **Ecuador** (S-01) con estándares internacionales (ISO 27001, PCI DSS si hay tarjetas, OWASP, NIST, FAPI). |
| A-4 | Identidad del **producto OAuth existente**. | Tratarlo como caja negra OIDC vs. suponer un producto concreto. | **Caja negra OIDC** con capacidades mínimas S-20; mencionar ejemplos de mercado sin atarse. |
| A-5 | **"mínimo 2" sistemas de notificación**: ¿2 canales o 2 proveedores? | (a) 2 canales (p. ej. push + correo). (b) 2 proveedores redundantes del mismo canal. (c) ≥2 canales con proveedor alterno. | **(c)**: push + correo + SMS (crítico), con conmutación de proveedor. |
| A-6 | **Herramienta de monitoreo**. | AWS nativo (CloudWatch, X-Ray/ADOT, Managed Grafana) vs. APM comercial (Dynatrace/Datadog). | Decidir en ADR-014; propuesta inicial AWS nativo + OpenTelemetry (portabilidad). |
| A-7 | **Volumetría** (S-03..S-09). | Usar los valores propuestos o ajustarlos. | Usar los propuestos; todos parametrizados en la estimación de costos. |
| A-8 | **Alcance de la PoC mínima**. | (a) API Gateway local + 2 servicios (movimientos con caché *cache-aside*, transferencia con idempotencia + outbox) + auditoría. (b) Solo un servicio. | **(a)** acotada a lo demostrable en el plazo. |
| A-9 | ¿Anexo de **equivalencias Azure**? | Sí (tabla breve) / No. | Sí, tabla de una página (bajo costo, suma en "Conocimientos de Nube"). |

---

## 7. Matriz de trazabilidad: rúbrica → requisitos → dónde se cubre

> Referencias de ADR con la **numeración consolidada** de §8 (14 ADR). Secciones del PDF según la estructura de §7.1.

| # | Criterio de la rúbrica | Requisitos que lo cubren | ADR previstos | Diagramas C4 / otros | Sección del PDF |
|---|---|---|---|---|---|
| 1 | Que la solución satisfaga los requerimientos y todo cuente con justificación | RF-01..RF-21, RNF-01..RNF-17, RE-06 | Todos | Todos | §2 Requisitos · §16 ADR · §17 Autoevaluación |
| 2 | Calidad y profundidad de los diagramas C4 | RE-02, RE-03, RE-04, RE-05, RE-09 | — (los ADR alimentan los elementos; herramienta C4 en anexo del PDF) | Contexto, Contenedores, Componentes (BFF, Transferencias, Consultas, Identidad), Infraestructura, Secuencias | §4, §5, §6, §7 |
| 3 | Segmentación de responsabilidades y desacoplamiento | RNF-10, RF-17..RF-21 | ADR-001, ADR-003, ADR-004, ADR-011 | Contenedores, Componentes | §3 Visión · §5 · §6 |
| 4 | Uso de patrones de arquitectura | RF-16, RF-20, RNF-03, RNF-10, RNF-13 | ADR-001, ADR-003, ADR-004, ADR-009, ADR-010, ADR-011, ADR-012, ADR-013 | Componentes (patrones rotulados) | §6 · §9 · §10 · anexo de patrones |
| 5 | Integración con servicios externos | RF-05, RF-06, RF-08, RF-20, RNF-03 | ADR-003, ADR-004, ADR-009, ADR-010, ADR-013 | Contexto, Contenedores, Componentes (adaptadores/ACL), secuencia interbancaria | §10 Integración |
| 6 | Calidad de arquitectura front-end y móvil | RF-09, RF-10, RNF-17 | ADR-005, ADR-006, ADR-003 (BFF), ADR-007 | Contenedores, Componentes de la app móvil/SPA | §8.x Front y móvil |
| 7 | Arquitectura de acceso a datos | RF-01, RF-07, RF-16, RNF-12, RNF-14 | ADR-004, ADR-009, ADR-011, ADR-012 | Componentes (repositorios, caché, outbox), secuencia de consulta con caché | §9 Datos |
| 8 | Conocimientos de nube (AWS o Azure) | RNF-09, RNF-02, RNF-04, RNF-08 | ADR-002, ADR-013, ADR-014 | Infraestructura AWS, Contenedores (servicios AWS) | §11 Nube · anexo Azure (A-9) |
| 9 | Manejo de costos | RNF-11, S-16 | Estimación de costos (documento 03) + órdenes de magnitud en ADR-002, ADR-013, ADR-014 | — | §15 Costos |
| 10 | Arquitectura de autenticación | RF-11, RF-13, RNF-05 | ADR-007 (+ ADR-003, ADR-005, ADR-006) | Componentes Identidad, secuencia de login | §8 Seguridad e identidad |
| 11 | Arquitectura de integración con onboarding | RF-12, RF-14, RNF-16 | ADR-008 (+ ADR-006) | Componentes Onboarding, secuencia de onboarding | §8.x Onboarding |
| 12 | Diseño de solución de auditoría | RF-15, RNF-14, S-09, S-10 | ADR-012, ADR-001 | Componentes Auditoría, Infraestructura (almacenamiento inmutable) | §9.x Auditoría |
| 13 | Regulaciones bancarias y estándares de seguridad | RNF-01, RNF-05, RNF-16, RF-08 | ADR-007, ADR-008, ADR-010, ADR-012 (+ documento de seguridad: perímetro/zero trust, cifrado/KMS/HSM, STRIDE y matriz normativa) | Componentes (controles de seguridad) | §14 Normativa · §8 Seguridad |
| 14 | Alta disponibilidad y tolerancia a fallos | RNF-02, RNF-03, RNF-04, RNF-08, RNF-13, RNF-15 | ADR-013, ADR-002, ADR-004, ADR-009 | Infraestructura (multi-AZ, DR), vista HA/DR | §12 HA/DR y resiliencia |
| 15 | Implementación de monitoreo | RNF-06, RNF-07 | ADR-014 (+ CI/CD e IaC en anexo del PDF) | Contenedores (observabilidad), Infraestructura | §13 Monitoreo y excelencia operativa |

### 7.1 Estructura tentativa del PDF

1. Resumen ejecutivo · 2. Requisitos y supuestos · 3. Visión y principios de arquitectura · 4. C4 Contexto · 5. C4 Contenedores · 6. C4 Componentes · 7. Flujos clave (secuencias) · 8. Seguridad, autenticación, onboarding y front/móvil · 9. Datos (acceso, clientes frecuentes, CQRS, auditoría) · 10. Integración con sistemas externos · 11. Nube e infraestructura AWS · 12. HA, DR y resiliencia · 13. Monitoreo y excelencia operativa · 14. Normativa y cumplimiento · 15. Costos · 16. Registro de decisiones (ADR) · 17. Autoevaluación contra la rúbrica · 18. PoC, repositorio y nota de elaboración.

### 7.2 Cobertura inversa (requisito → criterio)

Todos los RF/RNF quedan asociados a ≥1 criterio de la rúbrica. RF-21 (servicios adicionales) se cubre transversalmente en los criterios 3 y 4. No se detectan criterios sin requisitos de soporte.

---

## 8. Lista consolidada de ADR

> Las 25 decisiones candidatas iniciales se consolidaron en **14** ADR. Detalle, índice y estado en [`adr/README.md`](adr/README.md).

| ADR | Título | Absorbe (candidatos iniciales) | Requisitos principales |
|---|---|---|---|
| ADR-001 | Estilo arquitectónico y comunicación entre servicios | 001 + 012 | RNF-10, RF-17..21, RF-08, RF-15 |
| ADR-002 | Plataforma AWS: región principal/DR y cómputo | 002 + 003 | RNF-09, RNF-02, RNF-08, RNF-11, S-11, S-21 |
| ADR-003 | API Gateway + BFF por canal | 004 | RF-17, RNF-17 |
| ADR-004 | Integración con Core y complementario (ACL, conectividad híbrida) + modelo de lectura de movimientos | 005 + 016 | RF-01, RF-05..07, RF-20, S-12, S-18, S-19 |
| ADR-005 | SPA: framework y arquitectura | 010 | RF-09 |
| ADR-006 | App móvil multiplataforma | 009 | RF-10 |
| ADR-007 | Autenticación OAuth 2.0/OIDC por canal + métodos y *step-up* (seguridad) | 006 + 008 | RF-11, RF-13, RNF-05 |
| ADR-008 | Onboarding con reconocimiento facial y liveness (seguridad) | 007 | RF-12, RF-14 |
| ADR-009 | Orquestación de transferencias y pagos | 011 | RF-02..04, RF-20, RNF-13 |
| ADR-010 | Notificaciones multicanal | 013 | RF-08 |
| ADR-011 | Persistencia por servicio + clientes frecuentes | 014 + 015 | RNF-10, RF-16, RNF-12 |
| ADR-012 | Auditoría inmutable | 017 | RF-15, RNF-14 |
| ADR-013 | HA/DR por tier + resiliencia y auto-healing | 018 + 019 | RNF-02..04, RNF-08 |
| ADR-014 | Observabilidad, monitoreo y SLO | 020 | RNF-06, RNF-07 |
| — | Fuera de ADR: 021 perímetro/zero trust y 022 cifrado/KMS/HSM → documento de seguridad; 023 costos → estimación de costos; 024 herramienta C4 y 025 CI/CD/IaC → anexo del PDF | 021–025 | RNF-05, RNF-11, RNF-07, RE-02/03 |

## 9. Riesgos identificados

| ID | Riesgo | Impacto | Mitigación prevista |
|---|---|---|---|
| RK-01 | Supuestos de volumetría alejados de la realidad de BP. | Dimensionamiento y costos erróneos. | Parametrizar la estimación de costos; tabla de sensibilidad ×0,5 / ×2. |
| RK-02 | Normativa local citada sin verificación. | Pérdida de credibilidad en el criterio 13. | Verificar en fuentes oficiales; nombrar genéricamente lo no verificado. |
| RK-03 | El Core on-premise limita latencia y disponibilidad compuesta. | Incumplir S-13/S-14. | Patrones de lectura desacoplada y protección del Core (ADR-004, ADR-011). |
| RK-04 | Restricción regulatoria sobre datos en nube fuera del país (S-21). | Cambio de topología. | Diseñar con plano de datos sensible desacoplado; documentar alternativa híbrida (ADR-002). |
| RK-05 | Plazo de 40 h para diseño + PDF + repo + PoC. | Entregables incompletos. | Priorizar diagramas C4 y ADR (peso en la rúbrica); PoC mínima acotada (A-8). |
| RK-06 | Servicios AWS retirados o en fin de soporte usados por error (p. ej. Amazon QLDB tiene fin de soporte anunciado). | Diseño obsoleto. | Verificar el estado de cada servicio en la documentación oficial antes de cerrar cada ADR. |
| RK-07 | Exposición de información sensible en el repositorio público. | Riesgo reputacional/legal. | Revisión previa a cada push (R-06). |
