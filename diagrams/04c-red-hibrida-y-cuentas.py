"""Lámina 04c · Red híbrida y cuentas de plataforma.

Nodos: N46 TGW (4 tablas), N47 inspección (appliance mode), N48 DXGW, N49/N50 DX, N51 VPN por DX,
N52b VPN por Internet, N81a/b CGW (uno agregado), N82 Core, N83 complementario, N86 IdP,
N84 conector BCE, N68 Route 53 Resolver, SaaS N87-N91 (agregado), cuentas N52-N67 (agregado).
    uv run --python 3.12 --with diagrams python 04c-red-hibrida-y-cuentas.py
"""
from diagrams import Edge
from diagrams.aws.general import GenericDatabase, InternetAlt2, TraditionalServer, SamlToken
from diagrams.aws.management import Cloudtrail, Cloudwatch, Organizations
from diagrams.aws.network import (DirectConnect, NetworkFirewall, Route53, SiteToSiteVpn,
                                  TransitGateway, VpnConnection, VPCCustomerGateway)
from diagrams.aws.storage import Backup
from diagrams.generic.network import Router
from estilo import ASINCRONA, SINCRONA, diagrama, grupo, leyenda

NOMBRE = "04c-red-hibrida-y-cuentas"
TITULO = ("Infraestructura AWS 4/5 · red híbrida y cuentas de plataforma\n"
          "TGW (prod-rt · noprod-rt · onprem-rt · insp-rt) · inspección · DX · CPD de BP")

with diagrama(NOMBRE, TITULO, "LR"):
    leyenda()

    with grupo("Cuenta network · us-east-1", "red"):
        tgw = TransitGateway("N46 Transit Gateway\nprod-rt · noprod-rt · onprem-rt · insp-rt")
        nfw = NetworkFirewall("N47 VPC inspección\nNetwork Firewall · NAT · appliance mode")
        r53 = Route53("N68 Route 53 Resolver\nDNS híbrido")
        dxgw = DirectConnect("N48 Direct Connect Gateway\nglobal · TGW use1 + use2")
        dx1 = DirectConnect("N49 DX-1 · 1 Gbps\nubicación A · BFD")
        dx2 = DirectConnect("N50 DX-2 · 1 Gbps\nubicación B")
        vpnpip = VpnConnection("N51 2 × private IP VPN\nIPsec sobre DX")
        vpnnet = SiteToSiteVpn("N52b VPN por Internet\nrespaldo · menor preferencia")

    with grupo("CPD de BP · on-premise", "onprem"):
        cgw = VPCCustomerGateway("N81a/N81b CGW ×2\nBGP · IPsec · MSS 1360-1379")
        core = GenericDatabase("N82 Core bancario\n+ réplica CDC")
        comp = TraditionalServer("N83 Sistema complementario")
        idp = SamlToken("N86 IdP OAuth/OIDC\n(si on-prem)")
        bce = Router("N84 Conector pagos BCE\nRPI · SPI ISO 20022")

    with grupo("SaaS externos · egress lista blanca", "externo"):
        saas = InternetAlt2("N87-N91 IDV · Reg. Civil\nSMS/correo · FCM/APNs · App Attest")

    with grupo("Cuentas de plataforma · us-east-1 + réplica us-east-2", "respaldo"):
        logs = Organizations("N52-N56 log-archive\nauditoría WORM · Merkle")
        sec = Cloudwatch("N57-N58 security-tooling\nGuardDuty · Security Hub → SIEM")
        obs = Cloudwatch("N59-N65 observability\nCloudWatch · X-Ray · Grafana")
        bak = Backup("N67 backup-vault\nbóveda air-gapped")

    # Ruta prioritaria: TGW -> DXGW -> DX -> CGW -> CPD (E10)
    tgw >> Edge(label="E10 IPsec sobre DX\n(MTU 1446 · MACsec si 10G)", **SINCRONA) >> vpnpip
    vpnpip >> Edge(label="E10 IPsec sobre DX", **SINCRONA) >> dxgw
    dxgw >> Edge(label="transit VIF · BFD <1 s", **SINCRONA) >> dx1
    dxgw >> Edge(label="transit VIF", **SINCRONA) >> dx2
    dx1 >> Edge(label="E10 mTLS · TLS 1.3", **SINCRONA) >> cgw
    dx2 >> Edge(label="E10 mTLS · TLS 1.3", **SINCRONA) >> cgw
    cgw >> Edge(label="E10 mTLS · Core", **SINCRONA) >> core
    cgw >> Edge(label="E10 mTLS · complementario", **SINCRONA) >> comp
    cgw >> Edge(label="E10 mTLS · IdP", **SINCRONA) >> idp
    cgw >> Edge(label="E10 mTLS · pagos BCE", **SINCRONA) >> bce
    vpnnet >> Edge(label="E11 IPsec por Internet", **SINCRONA) >> cgw
    # E11: etiqueta en el extremo de la flecha (headlabel), por encima y a la izquierda del icono
    tgw >> Edge(headlabel='<<TABLE BORDER="0" CELLBORDER="0" CELLPADDING="2" BGCOLOR="white">'
                          '<TR><TD><FONT POINT-SIZE="10">E11 respaldo</FONT></TD></TR></TABLE>>',
                labeldistance="3.2", labelangle="-115", **SINCRONA) >> vpnnet
    tgw >> Edge(label="E28 egress TGW → inspección", **SINCRONA) >> nfw
    nfw >> Edge(xlabel="E28 HTTPS lista blanca", **SINCRONA) >> saas
    r53 >> Edge(label="E30 DNS híbrido (reenvío condicional\npor TGW/DX)", **SINCRONA) >> cgw
