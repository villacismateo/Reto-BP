"""Lámina 04 · Vista general de infraestructura AWS (para el lector).

Agregados a partir de docs/03 §7.1-§7.3. Detalle en 04a, 04a2, 04c y 04b.
Conteo: 14 servicios desplegables = 2 BFF + 9 svc-* + 3 acl-* (docs/04-modelo-c4.md).
    uv run --python 3.12 --with diagrams python 04-infra-vista-general.py
"""
from diagrams.aws.database import Dynamodb, ElasticacheForRedis, Aurora
from diagrams.aws.compute import ElasticContainerService
from diagrams.aws.integration import Eventbridge
from diagrams.aws.network import (APIGateway, CloudFront, ElbApplicationLoadBalancer,
                                  DirectConnect, Route53, TransitGateway)

from diagrams.aws.storage import SimpleStorageServiceS3
from diagrams.aws.management import Organizations
from diagrams.aws.general import GenericDatabase, InternetAlt2, TraditionalServer, Users
from diagrams.generic.network import Router
from diagrams.onprem.identity import Dex
from estilo import ASINCRONA, DR, SINCRONA, Caja, diagrama, grupo, leyenda

from diagrams import Edge

NOMBRE = "04-infra-vista-general"
TITULO = ("Infraestructura AWS · vista general\n"
          "Banca por internet BP · us-east-1 activa + us-east-2 DR (warm standby) · v1.0 · 2026-10-20")

with diagrama(NOMBRE, TITULO, "LR"):
    leyenda()

    clientes = Users("Clientes\nweb · móvil")

    with grupo("Borde global", "global"):
        borde = CloudFront("CloudFront\nSPA + API")
        dns = Route53("Route 53 + ARC\nrouting controls")

    with grupo("Región principal · us-east-1 · cuenta prod (3 AZ)", "prod"):
        api = APIGateway("API Gateway + WAF regional\nauthorizer JWT + DPoP")
        alb = ElbApplicationLoadBalancer("ALB interno\nVPC Link V2")
        ecs = ElasticContainerService("Microservicios ECS Fargate\n14 servicios ECS\n(incl. 2 BFF y 3 ACL)\n(canales · dominio · ACL · plataforma)")
        with grupo("Datos", "prod"):
            aurora = Aurora("Aurora PostgreSQL\ntransferencias · plataforma")
            dyn = Dynamodb("DynamoDB\nmovimientos · dispositivos")
            cache = ElasticacheForRedis("ElastiCache Valkey\ndatos · seguridad")
            s3 = SimpleStorageServiceS3("S3 WORM\nauditoría · KYC · SPA")
        evt = Eventbridge("EventBridge · SQS · Step Functions\nKinesis CDC · auditoría")
        nota = Caja("Nota: 3 zonas de disponibilidad (AZ)\nen la región activa; detalle en 04a2", shape="plaintext", fontsize="11")

    with grupo("Red híbrida · cuenta network", "red"):
        tgw = TransitGateway("Transit Gateway\n+ Network Firewall (inspección)")
        dx = DirectConnect("2 × Direct Connect 1 Gbps\n+ VPN de respaldo")

    with grupo("On-premise · CPD de BP", "onprem"):
        core = GenericDatabase("Core bancario\n+ réplica CDC")
        comp = TraditionalServer("Sistema complementario")
        idp = Dex("IdP OIDC de BP")
        bce = Router("Conector pagos BCE\nRPI · SPI (ISO 20022)")

    with grupo("SaaS externos (egress lista blanca)", "externo"):
        saas = InternetAlt2("IDV · Registro Civil\nSMS · FCM/APNs · App Attest")

    with grupo("Cuentas de plataforma", "respaldo"):
        plat = Organizations("log-archive · security-tooling\nobservability · backup-vault")

    with grupo("Región DR · us-east-2 (warm standby)", "dr", punteado=True):
        dr = Aurora("Réplicas: Aurora Global,\nDynamoDB, Valkey, S3 CRR\nECS tibio · conmutación ARC")

    # Flechas (protocolo corto; detalle en README.md, tabla Exx)
    clientes >> Edge(label="E01 HTTPS TLS 1.3", **SINCRONA) >> borde
    borde >> Edge(label="E04 TLS 1.3 + cabecera", **SINCRONA) >> api
    dns >> Edge(label="E02 DNS", **SINCRONA) >> borde
    api >> Edge(label="E06 VPC Link V2", **SINCRONA) >> alb
    alb >> Edge(label="E07 HTTPS TLS 1.3", **SINCRONA) >> ecs
    ecs >> Edge(label="E18/E19 TLS + IAM · RBAC", **SINCRONA) >> aurora
    ecs >> Edge(label="E19 HTTPS · TLS", **SINCRONA) >> dyn
    ecs >> Edge(label="E19 TLS + RBAC", **SINCRONA) >> cache
    ecs >> Edge(label="E21 eventos", **ASINCRONA) >> evt
    ecs >> Edge(label="E10 mTLS sobre IPsec/DX", **SINCRONA) >> tgw
    tgw >> Edge(label="E10 IPsec sobre DX", **SINCRONA) >> dx
    dx >> Edge(label="E10 mTLS · TLS 1.3", **SINCRONA) >> core
    dx >> Edge(label="E13 sistema complementario por DX", **SINCRONA) >> comp
    dx >> Edge(label="E13 IdP por DX", **SINCRONA) >> idp
    dx >> Edge(label="E12 red BCE", **SINCRONA) >> bce
    tgw >> Edge(label="E28 egress lista blanca", **SINCRONA) >> saas
    aurora >> Edge(label="D01 replicación ≈1 s", **DR) >> dr
    s3 >> Edge(label="D04 CRR · Object Lock", **DR) >> dr
    dns >> Edge(label="D08 routing controls", **DR) >> dr
    ecs >> Edge(label="E23 auditoría · E25 OTLP\nlogs y telemetría", **ASINCRONA) >> plat
