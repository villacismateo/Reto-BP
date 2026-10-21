"""Lámina 04a · Borde y entrada (G0, G1, API Gateway, authorizer, VPC Link V2, ALB, WAF regional).

Nodos: N01-N08, N10, N11, N18, N24, N42. Flecha E07 continúa en 04a2 (ECS).
    uv run --python 3.12 --with diagrams python 04a-borde-y-entrada.py
"""
from diagrams import Edge
from diagrams.aws.database import ElasticacheForRedis
from diagrams.aws.compute import Lambda
from diagrams.aws.general import MobileClient, Users
from diagrams.aws.network import APIGateway, CloudFront, ElbApplicationLoadBalancer, Route53
from diagrams.aws.security import ShieldAdvanced, SingleSignOn, WAF
from diagrams.aws.storage import SimpleStorageServiceS3
from estilo import SINCRONA, conector, diagrama, grupo, leyenda

NOMBRE = "04a-borde-y-entrada"
TITULO = ("Infraestructura AWS 2/5 · borde y entrada\n"
          "G0 clientes · G1 servicios globales · API Gateway · authorizer · VPC Link V2")

with diagrama(NOMBRE, TITULO, "LR"):
    leyenda()

    with grupo("G0 · Internet / clientes", "cliente"):
        n01 = Users("N01 Cliente web\n(SPA)")
        n02 = MobileClient("N02 Cliente móvil\n(app)")

    with grupo("G1 · Servicios globales AWS (fuera de región)", "global"):
        n03 = Route53("N03 Route 53\nDNS · health checks")
        n04 = Route53("N04 Route 53 ARC\nrouting controls · safety rules")
        n05 = CloudFront("N05 CloudFront\nSPA (OAC) + API")
        n06 = WAF("N06 WAF global\nBot Control · ATP · ACFP")
        n07 = ShieldAdvanced("N07 Shield Advanced\n(solo producción)")
        n08 = SingleSignOn("N08 IAM Identity Center\n+ Organizations (SCP)")

    with grupo("G2 · cuenta prod · us-east-1 · entrada", "prod"):
        n10 = APIGateway("N10 API Gateway REST\n+ WAF regional · throttling")
        n11 = Lambda("N11 Lambda authorizer\nJWT + DPoP · en VPC")
        n42 = ElasticacheForRedis("N42 ElastiCache Valkey\nseguridad · jti DPoP\nLISTA DE DENEGACIÓN")
        n24 = ElbApplicationLoadBalancer("N24 ALB interno\ndestino VPC Link V2")
        n18 = SimpleStorageServiceS3("N18 S3 spa-web\n(OAC)")

    n01 >> Edge(label="E01 HTTPS TLS 1.3 · HSTS", **SINCRONA) >> n05
    n02 >> Edge(label="E01 HTTPS TLS 1.3 · pinning", **SINCRONA) >> n05
    n03 >> Edge(label="E02 DNS", **SINCRONA) >> n05
    n05 >> Edge(label="E03 HTTPS · OAC SigV4", **SINCRONA) >> n18
    n05 >> Edge(label="E04 HTTPS TLS 1.3 + cabecera secreta", **SINCRONA) >> n10
    n10 >> Edge(label="E05 invocación IAM", **SINCRONA) >> n11
    n11 >> Edge(label="E05 TLS 1.3 + AUTH", **SINCRONA) >> n42
    n10 >> Edge(label="E06 VPC Link V2 · privado", **SINCRONA) >> n24
    n24 >> Edge(label="E07 HTTPS TLS 1.3 · SG solo ALB", **SINCRONA) >> conector("Ver 04a2\nECS Fargate (14 servicios)")
