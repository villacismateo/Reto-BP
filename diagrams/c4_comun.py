"""Librería común de los diagramas C4 del reto (DC-01, DC-02a/b, DC-03a..d).

Sigue docs/04-modelo-c4.md §0 (leyenda de colores, convención de flechas, abreviaturas).
Reutiliza el lienzo, la convención de flechas y la fuente de estilo.py.
Cada caja: nombre + [tipo: tecnología] + descripción. Cada flecha: verbo + [protocolo · seguridad].

Uso desde un script hermano:  from c4_comun import *
"""
import html
import textwrap

from diagrams import Cluster, Edge, Node

from estilo import CARPETA_PNG, DPI, FLECHA, FUENTE, diagrama  # noqa: F401  (diagrama se reexporta)

# Colores C4 estándar (§0.1)
PALETA = {
    "persona":    dict(fill="#08427B", font="white", borde="#08427B"),
    "sistema":    dict(fill="#1168BD", font="white", borde="#0B4F92"),
    "existente":  dict(fill="#6B7B8C", font="white", borde="#4F5C68"),
    "externo":    dict(fill="#999999", font="white", borde="#737373"),
    "contenedor": dict(fill="#438DD5", font="white", borde="#2F6FB0"),
    "componente": dict(fill="#85BBF0", font="black", borde="#5A9AD0"),
}

SINC = FLECHA["sincrona"]
ASINC = FLECHA["asincrona"]
DRF = FLECHA["dr"]


def _esc(t):
    return html.escape(t, quote=False)


class Elemento(Node):
    """Nodo sin icono; la etiqueta HTML lleva nombre, [tipo: tecnología] y descripción."""
    _provider = "c4"
    _type = "elemento"
    _icon_dir = None
    _icon = None


def _etiqueta(nombre, tipo, tecnologia, descripcion, ancho):
    partes = [f'<B>{_esc(nombre)}</B>']
    if tipo or tecnologia:
        clave = tipo + (f": {tecnologia}" if tecnologia else "")
        partes.append(f'<FONT POINT-SIZE="9">[{_esc(clave)}]</FONT>')
    if descripcion:
        for linea in textwrap.wrap(descripcion, ancho):
            partes.append(f'<FONT POINT-SIZE="9">{_esc(linea)}</FONT>')
    return "<" + "<BR/>".join(partes) + ">"


def elemento(nombre, tipo="", tecnologia="", descripcion="", clase="contenedor",
             forma="box", gestionado=False, ancho=40, **extra):
    """Crea una caja C4. clase = persona | sistema | existente | externo | contenedor | componente.

    gestionado=True dibuja borde discontinuo (contenedor gestionado AWS).
    forma="cylinder" para bases de datos.
    """
    p = PALETA[clase]
    estilo = "rounded,filled,dashed" if gestionado else "rounded,filled"
    if forma == "cylinder":
        estilo = "filled"
    attrs = dict(
        shape=forma, style=estilo, fillcolor=p["fill"], fontcolor=p["font"],
        color=p["borde"], fontname=FUENTE, fontsize="11", margin="0.14,0.07",
        penwidth="1.4", fixedsize="false", width="1.6",
    )
    attrs.update(extra)
    return Elemento(_etiqueta(nombre, tipo, tecnologia, descripcion, ancho), **attrs)


def flecha(origen, destino, verbo, proto="", asinc=False, dr=False, color=None,
           ancho=30, **extra):
    """Dibuja una relación. Síncrona = sólida; asíncrona = discontinua; DR = punteada."""
    estilo = dict(DRF if dr else (ASINC if asinc else SINC))
    if color:
        estilo["color"] = color
        estilo["fontcolor"] = color
    lineas = [_esc(l) for l in textwrap.wrap(verbo, ancho)]
    texto = "<BR/>".join(lineas)
    if proto:
        pl = [_esc(l) for l in textwrap.wrap("[" + proto + "]", ancho + 6)]
        texto += ('<BR/><FONT POINT-SIZE="8" COLOR="#4D4D4D">'
                  + "<BR/>".join(pl) + "</FONT>")
    etiqueta = '<' + f'<FONT POINT-SIZE="10">{texto}</FONT>' + '>'
    estilo.update(extra)
    origenes = origen if isinstance(origen, (list, tuple)) else [origen]
    destinos = destino if isinstance(destino, (list, tuple)) else [destino]
    for o in origenes:
        for d in destinos:
            o >> Edge(label=etiqueta, **estilo) >> d


def limite(nombre, color="#5F6B73", fondo="white", punteado=True, fuente=12, **extra):
    """Límite (boundary): marco gris discontinuo con título, o sólido si punteado=False."""
    g = dict(
        label=_esc(nombre), fontname=FUENTE, fontsize=str(fuente), fontcolor="#2C3E50",
        style="dashed,rounded" if punteado else "rounded", color=color,
        bgcolor=fondo, margin="14", penwidth="1.3",
    )
    g.update(extra)
    return Cluster(nombre, graph_attr=g)


def leyenda(personas=True, componentes=False, dr=False, contenedor=True):
    """Leyenda compacta (una sola tabla HTML) con colores C4 y convención de flechas (§0.1, §0.2)."""
    claves = []
    if personas:
        claves.append(("Persona", "persona"))
    claves += [("Sistema en alcance", "sistema"), ("Sistema existente de BP", "existente"),
               ("Externo (tercero)", "externo")]
    if contenedor:
        claves.append(("Contenedor propio", "contenedor"))
    if componentes:
        claves.append(("Componente", "componente"))
    filas = []
    for texto, clase in claves:
        p = PALETA[clase]
        filas.append(f'<TR><TD BGCOLOR="{p["fill"]}" WIDTH="16" HEIGHT="12" BORDER="1" COLOR="{p["borde"]}"></TD>'
                     f'<TD ALIGN="LEFT"><FONT POINT-SIZE="9">{_esc(texto)}</FONT></TD></TR>')
    filas.append('<TR><TD COLSPAN="2" ALIGN="LEFT"><FONT POINT-SIZE="9">'
                 '&#8212;&#8212;&gt; Síncrona</FONT></TD></TR>')
    filas.append('<TR><TD COLSPAN="2" ALIGN="LEFT"><FONT POINT-SIZE="9">'
                 '- - &gt; Asíncrona</FONT></TD></TR>')
    if dr:
        filas.append('<TR><TD COLSPAN="2" ALIGN="LEFT"><FONT POINT-SIZE="9">'
                     '&middot;&middot;&gt; Replicación DR</FONT></TD></TR>')
    tabla = ('<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="2" CELLPADDING="3" COLOR="#999999">'
             '<TR><TD COLSPAN="2" ALIGN="LEFT"><B><FONT POINT-SIZE="10">Leyenda</FONT></B></TD></TR>'
             + "".join(filas) + "</TABLE>>")
    return _Muestra(tabla, shape="plaintext", fontname=FUENTE, margin="0")


class _Muestra(Node):
    _provider = "c4"
    _type = "muestra"
    _icon_dir = None
    _icon = None


# ---------------------------------------------------------------------------
# Funciones añadidas (2026-10-19) para láminas A4 apaisadas: tipografía mayor y
# lienzo compacto. No modifican las firmas de las funciones anteriores.
# ---------------------------------------------------------------------------
def diagrama_c4(nombre, titulo, direccion="LR", ranksep="0.5", nodesep="0.16"):
    """Lienzo compacto para láminas C4 (mismo estilo que estilo.diagrama, con espaciado menor)."""
    import os
    from diagrams import Diagram
    return Diagram(
        name=titulo,
        filename=os.path.join(CARPETA_PNG, nombre),
        direction=direccion,
        outformat="png",
        show=False,
        graph_attr={
            "fontname": FUENTE, "fontsize": "26", "fontcolor": "#1B2631",
            "bgcolor": "white", "dpi": DPI, "pad": "0.2",
            "nodesep": nodesep, "ranksep": ranksep, "splines": "spline",
            "labelloc": "t", "labeljust": "c", "compound": "true",
        },
        node_attr={"fontname": FUENTE, "fontsize": "12", "fontcolor": "#1B2631"},
        edge_attr={"fontname": FUENTE, "fontsize": "11", "fontcolor": "#1B2631"},
    )


def elemento_v2(nombre, tipo="", tecnologia="", descripcion="", clase="contenedor",
                forma="box", gestionado=False, ancho=28, fs=12, fd=10, **extra):
    """Como elemento(), con tipografía mayor (nombre fs, resto fd) para A4 apaisado."""
    p = PALETA[clase]
    estilo = "rounded,filled,dashed" if gestionado else "rounded,filled"
    if forma == "cylinder":
        estilo = "filled"
    partes = [f'<B><FONT POINT-SIZE="{fs}">{_esc(nombre)}</FONT></B>']
    if tipo or tecnologia:
        clave = tipo + (f": {tecnologia}" if tecnologia else "")
        partes.append(f'<FONT POINT-SIZE="{fd}">[{_esc(clave)}]</FONT>')
    if descripcion:
        for linea in textwrap.wrap(descripcion, ancho):
            partes.append(f'<FONT POINT-SIZE="{fd}">{_esc(linea)}</FONT>')
    etiqueta = "<" + "<BR/>".join(partes) + ">"
    attrs = dict(
        shape=forma, style=estilo, fillcolor=p["fill"], fontcolor=p["font"],
        color=p["borde"], fontname=FUENTE, margin="0.14,0.12", penwidth="1.4",
        fixedsize="false", width="1.4",
    )
    attrs.update(extra)
    return Elemento(etiqueta, **attrs)


def flecha_v2(origen, destino, verbo, proto="", asinc=False, dr=False, color=None,
              ancho=32, fs=11, fp=9, **extra):
    """Como flecha(), con tipografía mayor. Acepta listas en origen/destino."""
    estilo = dict(DRF if dr else (ASINC if asinc else SINC))
    if color:
        estilo["color"] = color
        estilo["fontcolor"] = color
    texto = "<BR/>".join(_esc(l) for l in textwrap.wrap(verbo, ancho))
    if proto:
        texto += (f'<BR/><FONT POINT-SIZE="{fp}" COLOR="#4D4D4D">'
                  + "<BR/>".join(_esc(l) for l in textwrap.wrap("[" + proto + "]", ancho + 6))
                  + "</FONT>")
    etiqueta = "<" + f'<FONT POINT-SIZE="{fs}">{texto}</FONT>' + ">"
    estilo.update(extra)
    origenes = origen if isinstance(origen, (list, tuple)) else [origen]
    destinos = destino if isinstance(destino, (list, tuple)) else [destino]
    for o in origenes:
        for d in destinos:
            o >> Edge(label=etiqueta, **estilo) >> d
