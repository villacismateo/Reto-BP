"""Lámina 04b · DR multi-región us-east-1 (activa) ↔ us-east-2 (warm standby).

Elementos D01-D10 de docs/03 §7.3. Estados en us-east-2: [CALIENTE] [TIBIO] [FRÍO].
Leyenda de estados en un cluster propio con tres líneas de color; cada etiqueta
de estado de us-east-2 lleva el color de su estado.
    uv run --python 3.12 --with diagrams python 04b-dr-multi-region.py
"""
import html
import re

from diagrams import Cluster, Edge
from diagrams.aws.database import Aurora, Dynamodb, ElasticacheForRedis, DatabaseMigrationService
from diagrams.aws.analytics import KinesisDataStreams
from diagrams.aws.compute import ElasticContainerService
from diagrams.aws.integration import Eventbridge, EventbridgePipes
from diagrams.aws.network import APIGateway, DirectConnect, Route53, TransitGateway
from diagrams.aws.storage import Backup, SimpleStorageServiceS3
from estilo import ASINCRONA, DR, FUENTE, SINCRONA, Caja, diagrama, grupo, leyenda

NOMBRE = "04b-dr-multi-region"
TITULO = ("Infraestructura AWS 5/5 · recuperación ante desastres (DR)\n"
          "us-east-1 activa → us-east-2 warm standby · flechas punteadas = replicación D01-D10")

# Código de color por estado (borde de la leyenda y etiqueta del nodo)
ESTADOS = {
    "CALIENTE": ("#1E8449", "replicando y listo para servir"),
    "TIBIO":    ("#B9770E", "desplegado a capacidad mínima, escala al conmutar"),
    "FRÍO":     ("#5D6D7E", "se crea o se carga al conmutar"),
}


def etiqueta(texto):
    """Etiqueta HTML: texto plano del nodo, con cada [ESTADO] en negrita y de su color."""
    lineas = []
    for linea in texto.split("\n"):
        esc = html.escape(linea, quote=False)
        esc = re.sub(r"\[(CALIENTE|TIBIO|FRÍO)\]",
                     lambda m: f'<FONT COLOR="{ESTADOS[m.group(1)][0]}"><B>[{m.group(1)}]</B></FONT>',
                     esc)
        lineas.append(esc)
    return "<" + "<BR/>".join(lineas) + ">"


def linea_estado(nombre):
    """Línea de leyenda: 'NOMBRE · significado', en negrita y del color del estado."""
    color, significado = ESTADOS[nombre]
    return (f'<B><FONT POINT-SIZE="18" COLOR="{color}">{nombre}</FONT></B>'
            f'<FONT POINT-SIZE="17"> · {html.escape(significado, quote=False)}</FONT>')


# Tres líneas en un único nodo de texto: en LR, nodos encadenados quedarían en horizontal.
LEYENDA_ESTADOS = "<" + "<BR ALIGN=\"LEFT\"/><BR ALIGN=\"LEFT\"/>".join(linea_estado(n) for n in ESTADOS) + "<BR ALIGN=\"LEFT\"/>>"


with diagrama(NOMBRE, TITULO, "LR"):
    leyenda()

    with Cluster("Estados en us-east-2", graph_attr={
        "fontname": FUENTE, "fontsize": "13", "fontcolor": "#117A65",
        "color": "#117A65", "style": "rounded", "margin": "14",
    }):
        Caja(LEYENDA_ESTADOS, shape="plaintext", fontname=FUENTE, margin="0.1", fixedsize="false", width="0", height="0", labelloc="c", imagescale="false")

    with grupo("us-east-1 · región principal (referencias)", "prod"):
        a_aur = Aurora("N39 Aurora transferencias\n(primaria)")
        a_dyn = Dynamodb("N12 DynamoDB\n(Global Tables)")
        a_val = ElasticacheForRedis("N42 Valkey seguridad\n(primario)")
        a_s3 = SimpleStorageServiceS3("N18 · N52 · N53 S3\nWORM · KYC · SPA · sellos")
        a_arc = Route53("N04 ARC\nrouting controls")
        a_tgw = TransitGateway("N46 TGW use1\n(ver 04c)")

    with grupo("us-east-2 · DR warm standby", "dr", punteado=True):
        b_api = APIGateway(etiqueta("N70-N71 API GW + ALB\n[TIBIO] capacidad mínima"))
        b_ecs = ElasticContainerService(etiqueta("N72 ECS · 22 tareas\n[TIBIO] Tier 1: 8×2 · Tier 2: 6×1"))
        b_aur = Aurora(etiqueta("N73 Aurora Global secundaria\n[CALIENTE] · N73b plataforma headless"))
        b_dyn = Dynamodb(etiqueta("N74 DynamoDB réplicas\n[CALIENTE] Global Tables"))
        b_val = ElasticacheForRedis(etiqueta("N75 Valkey seguridad\n[CALIENTE] Global Datastore"))
        b_dat = ElasticacheForRedis(etiqueta("N76 Valkey datos\n[FRÍO] Refresh-Ahead"))
        b_s3 = SimpleStorageServiceS3(etiqueta("N77 S3 CRR\n[CALIENTE] Object Lock"))
        b_evt = Eventbridge(etiqueta("N78 Kinesis\nEventBridge · SQS\nStep Functions\n[TIBIO] cap. mínima"))
        p_cta = EventbridgePipes("N78b Pipe svc-cuentas\n[DETENIDO] evita duplicar eventos")
        p_dis = EventbridgePipes("N78c Pipe svc-dispositivos\n[DETENIDO] evita duplicar eventos")
        b_dms = DatabaseMigrationService(etiqueta("N79 DMS\n[FRÍO] lanzado por IaC"))
        b_tgw = TransitGateway(etiqueta("N80 TGW use2 · VPN propias\n[CALIENTE] · VPC insp. use2"))
        b_dxgw = DirectConnect("N48 DX Gateway\ncompartido")

    # Replicación DR (D01-D10): punteadas
    a_aur >> Edge(label="D01 Aurora Global ≈1 s", **DR) >> b_aur
    a_dyn >> Edge(label="D02 Global Tables · seg", **DR) >> b_dyn
    a_val >> Edge(label="D03 Global Datastore · seg", **DR) >> b_val
    a_s3 >> Edge(label="D04 S3 CRR · Object Lock · min", **DR) >> b_s3
    a_tgw >> Edge(label="D07 TGW peering · rutas estáticas", **DR) >> b_tgw
    a_arc >> Edge(label="D08 routing controls api/web\nsafety rule · una llamada", **DR) >> b_api

    # Operación en us-east-2 (sincrónica)
    b_api >> Edge(label="E04 · E06 VPC Link V2", **SINCRONA) >> b_ecs
    b_ecs >> Edge(label="E18 PostgreSQL TLS + IAM", **SINCRONA) >> b_aur
    b_ecs >> Edge(label="E19 TLS + RBAC", **SINCRONA) >> b_val
    b_ecs >> Edge(label="E19 HTTPS", **SINCRONA) >> b_dyn
    b_dms >> Edge(label="E20 CDC por DX (al conmutar)", **SINCRONA) >> b_evt
    b_tgw >> Edge(label="E11b IPsec sobre DX\n(independiente de use1)", **SINCRONA) >> b_dxgw
    b_dyn >> Edge(label="DynamoDB Streams réplica\nmovimientos", **ASINCRONA) >> p_cta
    b_dyn >> Edge(label="DynamoDB Streams réplica\ndispositivos", **ASINCRONA) >> p_dis
    p_cta >> Edge(label="detenido hasta conmutar", **ASINCRONA) >> b_evt
    p_dis >> Edge(label="auth.device.* detenido", **ASINCRONA) >> b_evt
