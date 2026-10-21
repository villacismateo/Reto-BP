"""Lámina 04a2 · Cómputo y datos (ECS Fargate por boundary, datos y endpoints).

Nodos: N25-N38 (14 servicios), N39/N40 agregados en un nodo Aurora, N12 y N42-DATOS en
Dynamo/Valkey, N43 endpoints. Ver tabla Exx en README.md.
    uv run --python 3.12 --with diagrams python 04a2-computo-y-datos.py
"""
from diagrams import Edge
from diagrams.aws.compute import ElasticContainerServiceService
from diagrams.aws.database import Aurora, Dynamodb, ElasticacheForRedis
from diagrams.aws.network import Privatelink
from estilo import ASINCRONA, SINCRONA, diagrama, grupo, leyenda

NOMBRE = "04a2-computo-y-datos"
TITULO = ("Infraestructura AWS 3/5 · cómputo y datos · ECS Fargate ARM64 (cluster bp-prod-use1)\n"
          "Service Connect bp.internal · sidecar ADOT · servicios agrupados por boundary")

with diagrama(NOMBRE, TITULO, "LR"):
    leyenda()

    with grupo("Canales · BFF (N25-N26)", "prod"):
        bff_web = ElasticContainerServiceService("N25 bff-web")
        bff_movil = ElasticContainerServiceService("N26 bff-movil")

    with grupo("Dominio (N27, N28, N29, N30, N31, N35)", "prod"):
        svc_disp = ElasticContainerServiceService("N27 svc-dispositivos")
        svc_cli = ElasticContainerServiceService("N28 svc-clientes")
        svc_cta = ElasticContainerServiceService("N29 svc-cuentas")
        svc_proy = ElasticContainerServiceService("N30 svc-cuentas-proyector")
        svc_trf = ElasticContainerServiceService("N31 svc-transferencias")
        svc_onb = ElasticContainerServiceService("N35 svc-onboarding")

    with grupo("Integración · ACL (N32, N36, N37, N38)", "prod"):
        wrk = ElasticContainerServiceService("N32 svc-transferencias-worker")
        acl_core = ElasticContainerServiceService("N36 acl-core")
        acl_comp = ElasticContainerServiceService("N37 acl-complementario")
        acl_ibk = ElasticContainerServiceService("N38 acl-interbancario")

    with grupo("Plataforma · soporte (N33, N34)", "prod"):
        notif = ElasticContainerServiceService("N33 svc-notificaciones")
        audit = ElasticContainerServiceService("N34 svc-auditoria")

    with grupo("Datos y endpoints · subredes datos y endpoints", "prod"):
        aurora = Aurora("N39-N40 Aurora PostgreSQL\ntransferencias · plataforma")
        dyn = Dynamodb("N12 DynamoDB\nmovimientos · dispositivos")
        valkey = ElasticacheForRedis("N41 ElastiCache Valkey datos")
        endp = Privatelink("N43 Interface + gateway\nendpoints · PrivateLink")

    # Entrada desde el ALB (E07) y canales
    bff_web >> Edge(label="E08 HTTP/2 + TLS 1.3 SC\n+ JWT propagado", **SINCRONA) >> svc_cli
    bff_web >> Edge(label="E08 HTTP/2 + TLS 1.3 SC", **SINCRONA) >> svc_disp
    bff_web >> Edge(label="E08 HTTP/2 + TLS 1.3 SC", **SINCRONA) >> svc_cta
    bff_web >> Edge(label="E08 HTTP/2 + TLS 1.3 SC", **SINCRONA) >> svc_trf
    bff_movil >> Edge(label="E08 HTTP/2 + TLS 1.3 SC", **SINCRONA) >> svc_onb
    bff_movil >> Edge(label="E08 HTTP/2 + TLS 1.3 SC", **SINCRONA) >> svc_trf

    # Dominio -> ACL (E09)
    svc_cli >> Edge(label="E09 TLS 1.3 SC · token servicio", **SINCRONA) >> acl_comp
    svc_cta >> Edge(label="E09 TLS 1.3 SC · token servicio", **SINCRONA) >> acl_core
    svc_trf >> Edge(label="E09 TLS 1.3 SC · token servicio", **SINCRONA) >> acl_core
    wrk >> Edge(label="E09 TLS 1.3 SC · token servicio", **SINCRONA) >> acl_ibk

    # Eventos (asíncronos)
    svc_proy >> Edge(label="E21 escritura CQRS", **SINCRONA) >> dyn

    # Datos (E18, E19)
    svc_trf >> Edge(label="E18 PostgreSQL TLS + IAM", **SINCRONA) >> aurora
    svc_onb >> Edge(label="E18 PostgreSQL TLS + IAM", **SINCRONA) >> aurora
    svc_cli >> Edge(label="E19 TLS + RBAC", **SINCRONA) >> valkey
    svc_cta >> Edge(label="E19 HTTPS gateway endpoint", **SINCRONA) >> dyn

    # Endpoints (E17)
    svc_disp >> Edge(label="E17 PrivateLink /\ngateway endpoints", **SINCRONA) >> endp
    notif >> Edge(label="E16 HTTPS endpoint privado", **SINCRONA) >> endp
