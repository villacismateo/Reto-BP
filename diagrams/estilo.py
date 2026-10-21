"""Guía de estilo compartida de los diagramas del Reto BP (AWS y, después, C4).

Uso desde un script hermano:  from estilo import *
Ver diagrams/README.md (sección "Guía de estilo") para la descripción completa.
"""
import os

from diagrams import Cluster, Diagram, Edge, Node

CARPETA_PNG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "png")
FUENTE = "Arial"
DPI = "170"

# Color de borde y color de fondo por tipo de grupo (borde, fondo)
COLORES = {
    "cliente":   ("#7F8C8D", "#F4F6F6"),  # Internet / clientes
    "global":    ("#2E4053", "#EEF1F4"),  # servicios globales AWS
    "prod":      ("#1168BD", "#EBF4FB"),  # cuenta prod us-east-1 (activa)
    "red":       ("#438DD5", "#EEF5FC"),  # red, inspección, TGW, DX
    "seguridad": ("#B03A2E", "#FBEDEC"),  # cuenta security-tooling / cifrado
    "obs":       ("#7D3C98", "#F5EEF8"),  # observabilidad
    "respaldo":  ("#1E8449", "#EAF7EF"),  # AWS Backup / bóveda / CI-CD
    "dr":        ("#117A65", "#E8F6F3"),  # región DR us-east-2 (tibia/fría)
    "onprem":    ("#6C6C6C", "#F2F2F2"),  # CPD de BP
    "externo":   ("#B9770E", "#FEF5E7"),  # SaaS / terceros / BCE
}

# Convención de flechas (única para todos los diagramas)
FLECHA = {
    "sincrona":  dict(color="#1B2631", style="solid", penwidth="1.3", fontcolor="#1B2631"),
    "asincrona": dict(color="#1B2631", style="dashed", penwidth="1.3", fontcolor="#1B2631"),
    "dr":        dict(color="#117A65", style="dotted", penwidth="2.4", fontcolor="#117A65"),
}
SINCRONA, ASINCRONA, DR = FLECHA["sincrona"], FLECHA["asincrona"], FLECHA["dr"]


class Caja(Node):
    """Nodo solo de texto (sin icono): conectores fuera de página y leyenda."""
    _provider = "estilo"
    _type = "caja"
    _icon_dir = None
    _icon = None


def diagrama(nombre, titulo, direccion="LR"):
    """Diagram con fondo blanco, fuente sans, título arriba y 170 DPI."""
    return Diagram(
        name=titulo,
        filename=os.path.join(CARPETA_PNG, nombre),
        direction=direccion,
        outformat="png",
        show=False,
        graph_attr={
            "fontname": FUENTE, "fontsize": "22", "fontcolor": "#1B2631",
            "bgcolor": "white", "dpi": DPI, "pad": "0.3",
            "nodesep": "0.18", "ranksep": "0.55", "splines": "spline",
            "labelloc": "t", "labeljust": "c", "compound": "true", "pad": "0.5",
        },
        node_attr={"fontname": FUENTE, "fontsize": "11", "fontcolor": "#1B2631"},
        edge_attr={"fontname": FUENTE, "fontsize": "10", "fontcolor": "#1B2631"},
    )


def grupo(etiqueta, tipo, punteado=False):
    """Cluster con borde y fondo según la paleta; punteado para la región DR."""
    borde, fondo = COLORES[tipo]
    estilo = "rounded,filled,dashed" if punteado else "rounded,filled"
    return Cluster(etiqueta, graph_attr={
        "fontname": FUENTE, "fontsize": "13", "fontcolor": borde,
        "color": borde, "style": estilo, "fillcolor": fondo,
        "penwidth": "1.6", "margin": "12",
    })


def conectar(origenes, destinos, etiqueta, tipo="sincrona", **extra):
    """Dibuja una flecha de cada origen a cada destino con la convención de estilo."""
    estilo = dict(FLECHA[tipo])
    estilo.update(extra)
    for o in origenes:
        for d in destinos:
            o >> Edge(label=etiqueta, **estilo) >> d


def conector(texto):
    """Conector fuera de página: la flecha continúa en otro diagrama (ver texto)."""
    return Caja(texto, shape="box", style="rounded,dashed", color="#7F8C8D",
                fontcolor="#34495E", fontsize="10")


def leyenda(estados=False):
    """Leyenda con las convenciones de flecha (y, opcional, estados de la región DR)."""
    with Cluster("Leyenda", graph_attr={
        "fontname": FUENTE, "fontsize": "12", "color": "#999999",
        "style": "rounded", "margin": "10",
    }):
        a1, a2 = Caja("", shape="plaintext", width="0.4", height="0.1"), Caja("", shape="plaintext", width="0.4", height="0.1")
        a1 >> Edge(label="Síncrona: petición-respuesta", **SINCRONA) >> a2
        b1, b2 = Caja("", shape="plaintext", width="0.4", height="0.1"), Caja("", shape="plaintext", width="0.4", height="0.1")
        b1 >> Edge(label="Asíncrona: evento / cola", **ASINCRONA) >> b2
        c1, c2 = Caja("", shape="plaintext", width="0.4", height="0.1"), Caja("", shape="plaintext", width="0.4", height="0.1")
        c1 >> Edge(label="Replicación DR (us-east-1 → us-east-2)", **DR) >> c2
        if estados:
            nota = Caja("[CALIENTE] replicando, listo para servir · "
                        "[TIBIO] capacidad mínima, escala al conmutar · "
                        "[FRÍO] se crea al conmutar", shape="plaintext", fontsize="10")
            c2 >> Edge(style="invis") >> nota
