"""DC-01 · Diagrama de contexto (nivel 1 C4). Audiencia: negocio, sin tecnologías.

Fuente: docs/04-modelo-c4.md §1 (elementos P1-P4, S0, E1-E12; relaciones R01-R19).
Leyenda fuera del lienzo principal (columna derecha inferior).
    uv run --python 3.12 --with diagrams python 01-contexto.py
"""
from c4_comun import Edge, diagrama_c4, elemento_v2, flecha_v2, leyenda, limite

NOMBRE = "01-contexto"
TITULO = ("DC-01 · Contexto del sistema: Banca por Internet BP\n"
          "Audiencia: negocio y cumplimiento · v1.0 · 2026-10-20")

with diagrama_c4(NOMBRE, TITULO, "LR", ranksep="0.6", nodesep="0.22"):
    # Personas (columna 0)
    p1 = elemento_v2("Cliente persona natural", "Persona", clase="persona", ancho=26,
                     descripcion="Consulta, transfiere y paga")
    p2 = elemento_v2("Persona que quiere ser cliente", "Persona", clase="persona", ancho=26,
                     descripcion="Abre su cuenta con rostro y cédula")
    p3 = elemento_v2("Asesor del centro de contacto", "Persona", clase="persona", ancho=26,
                     descripcion="Bloquea teléfonos robados")
    p4 = elemento_v2("Auditor / cumplimiento", "Persona", clase="persona", ancho=26,
                     descripcion="Revisa el registro de acciones")

    # Sistema en alcance (columna 1)
    s0 = elemento_v2("Banca por Internet BP", "Sistema", clase="sistema", ancho=26,
                     descripcion="Consulta, transfiere, paga y abre cuentas")

    # Banco BP (columna 2): sistemas existentes de BP
    with limite("Banco BP", color="#1F4E79"):
        e1 = elemento_v2("Core bancario", "Sistema existente de BP", clase="existente",
                         ancho=26, descripcion="Clientes, saldos y movimientos")
        e2 = elemento_v2("Sistema complementario", "Sistema existente de BP", clase="existente",
                         ancho=26, descripcion="Detalle del cliente bajo demanda")
        e3 = elemento_v2("Servicio de identidad de BP", "Sistema existente de BP",
                         clase="existente", ancho=26, descripcion="Verifica clave, huella o llave")
        e4 = elemento_v2("Conector de pagos de BP", "Sistema existente de BP", clase="existente",
                         ancho=26, descripcion="Enlace con la red de pagos del BCE")
        e12 = elemento_v2("Centro de seguridad (SOC)", "Sistema existente de BP",
                          clase="existente", ancho=26, descripcion="Atiende alertas de seguridad")

    # Terceros (columnas 3 y 4)
    e6 = elemento_v2("Verificación de identidad", "Sistema externo", clase="externo", ancho=26,
                     descripcion="Comprueba que la persona esté viva")
    e7 = elemento_v2("Registro Civil (DIGERCIC)", "Sistema externo", clase="externo", ancho=26,
                     descripcion="Fuente oficial de identidad")
    e8 = elemento_v2("Listas KYC/AML", "Sistema externo", clase="externo", ancho=26,
                     descripcion="Listas de lavado y sanciones")
    e11 = elemento_v2("Integridad de la app", "Sistema externo", clase="externo", ancho=26,
                      descripcion="Confirma que la app es original")
    e5 = elemento_v2("Banco Central (RPI y SPI)", "Sistema externo", clase="externo", ancho=26,
                     descripcion="Red de pagos: inmediata o por ciclos")
    e9 = elemento_v2("Push (Apple y Google)", "Sistema externo", clase="externo", ancho=26,
                     descripcion="Avisos al teléfono del cliente")
    e10 = elemento_v2("Correo y SMS", "Sistema externo", clase="externo", ancho=26,
                      descripcion="Si uno falla, actúa el alterno")

    # Disposición en columnas de terceros (solo para el ranking, invisibles)
    for ext in (e6, e7, e8, e11):
        e1 >> Edge(style="invis", minlen="1") >> ext
    for ext in (e5, e9, e10):
        e1 >> Edge(style="invis", minlen="2") >> ext

    # Leyenda fuera del lienzo principal: abajo a la derecha
    leyenda_nodo = None
    # (la leyenda se crea al final y se ancla bajo la columna de terceros)

    # Relaciones R01-R19, verbos de negocio (sin tecnología)
    flecha_v2(p1, s0, "Consulta movimientos, transfiere y paga", ancho=24)
    flecha_v2(p2, s0, "Abre su cuenta con su rostro y su cédula", ancho=24)
    flecha_v2(p3, s0, "Bloquea dispositivos robados del cliente", ancho=24)
    flecha_v2(p4, s0, "Consulta el registro de acciones", ancho=24)
    flecha_v2(p1, e3, "Ingresa con clave, huella o llave", ancho=24)
    flecha_v2(s0, e3, "Delega el inicio de sesión", ancho=24)
    flecha_v2(s0, e1, "Lee saldos; registra débitos y créditos", ancho=24)
    flecha_v2(s0, e2, "Pide el detalle del cliente", ancho=24)
    flecha_v2(s0, e4, "Envía transferencias a otros bancos", ancho=24)
    flecha_v2(e4, e5, "Liquida con el otro banco", asinc=True, ancho=24)
    flecha_v2(s0, e6, "Verifica rostro y prueba de vida", ancho=24)
    flecha_v2(s0, e7, "Confirma identidad con la fuente oficial", ancho=24)
    flecha_v2(s0, e8, "Revisa listas de control", ancho=24)
    flecha_v2(s0, e9, "Envía avisos push de cada movimiento", asinc=True, ancho=24)
    flecha_v2(s0, e10, "Envía correo y SMS (mínimo 2 canales)", asinc=True, ancho=24)
    flecha_v2(e9, p1, "Entrega el aviso en el teléfono", asinc=True, ancho=24)
    flecha_v2(e10, p1, "Entrega el correo o el SMS", asinc=True, ancho=24)
    flecha_v2(s0, e11, "Comprueba que la app es confiable", ancho=24)
    flecha_v2(s0, e12, "Reporta alertas de seguridad", asinc=True, ancho=24)

    # Leyenda: a la derecha, anclada a E10 con enlace invisible (no se superpone)
    lg = leyenda(personas=True, contenedor=False)
    e10 >> Edge(style="invis") >> lg
