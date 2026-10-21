# Modulo base: VPC en 3 AZ con tres capas de subred (publica / aplicacion / datos),
# NAT por AZ, VPC Flow Logs y cluster ECS (Fargate) con Container Insights.
locals {
  tags = merge(var.etiquetas, { "proyecto" = var.nombre, "gestionado-por" = "terraform" })
  # /20 por subred: publicas 0-2, aplicacion 3-5, datos 6-8
  publicas   = { for i, az in var.zonas : az => cidrsubnet(var.cidr_vpc, 4, i) }
  aplicacion = { for i, az in var.zonas : az => cidrsubnet(var.cidr_vpc, 4, i + 3) }
  datos      = { for i, az in var.zonas : az => cidrsubnet(var.cidr_vpc, 4, i + 6) }
  zonas_nat  = var.nat_por_zona ? var.zonas : [var.zonas[0]]
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_vpc
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.tags, { Name = "${var.nombre}-vpc" })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.tags, { Name = "${var.nombre}-igw" })
}

resource "aws_subnet" "publica" {
  for_each          = local.publicas
  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value
  tags              = merge(local.tags, { Name = "${var.nombre}-pub-${each.key}", capa = "publica" })
}

resource "aws_subnet" "aplicacion" {
  for_each          = local.aplicacion
  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value
  tags              = merge(local.tags, { Name = "${var.nombre}-app-${each.key}", capa = "aplicacion" })
}

resource "aws_subnet" "datos" {
  for_each          = local.datos
  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value
  tags              = merge(local.tags, { Name = "${var.nombre}-datos-${each.key}", capa = "datos" })
}

resource "aws_eip" "nat" {
  for_each = toset(local.zonas_nat)
  domain   = "vpc"
  tags     = merge(local.tags, { Name = "${var.nombre}-nat-${each.key}" })
}

resource "aws_nat_gateway" "this" {
  for_each      = toset(local.zonas_nat)
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.publica[each.key].id
  tags          = merge(local.tags, { Name = "${var.nombre}-nat-${each.key}" })
  depends_on    = [aws_internet_gateway.this]
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = merge(local.tags, { Name = "${var.nombre}-rt-pub" })
}

resource "aws_route_table_association" "publica" {
  for_each       = aws_subnet.publica
  subnet_id      = each.value.id
  route_table_id = aws_route_table.publica.id
}

# Una tabla por AZ para que la caida de una AZ no afecte la salida de las otras
resource "aws_route_table" "aplicacion" {
  for_each = toset(var.zonas)
  vpc_id   = aws_vpc.this.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this[var.nat_por_zona ? each.key : var.zonas[0]].id
  }
  tags = merge(local.tags, { Name = "${var.nombre}-rt-app-${each.key}" })
}

resource "aws_route_table_association" "aplicacion" {
  for_each       = aws_subnet.aplicacion
  subnet_id      = each.value.id
  route_table_id = aws_route_table.aplicacion[each.key].id
}

# Capa de datos sin salida a Internet
resource "aws_route_table" "datos" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.tags, { Name = "${var.nombre}-rt-datos" })
}

resource "aws_route_table_association" "datos" {
  for_each       = aws_subnet.datos
  subnet_id      = each.value.id
  route_table_id = aws_route_table.datos.id
}

# Trazabilidad de red (requisito de monitoreo/seguridad)
resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/vpc/${var.nombre}/flow-logs"
  retention_in_days = 365
  tags              = local.tags
}

data "aws_iam_policy_document" "flow_logs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow_logs" {
  name               = "${var.nombre}-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume.json
  tags               = local.tags
}

data "aws_iam_policy_document" "flow_logs" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.flow_logs.arn}:*"]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  name   = "escritura-flow-logs"
  role   = aws_iam_role.flow_logs.id
  policy = data.aws_iam_policy_document.flow_logs.json
}

resource "aws_flow_log" "this" {
  vpc_id          = aws_vpc.this.id
  traffic_type    = "ALL"
  log_destination = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn    = aws_iam_role.flow_logs.arn
  tags            = local.tags
}

resource "aws_ecs_cluster" "this" {
  name = "${var.nombre}-ecs"
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
  tags = local.tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
  # Base on-demand para servicios transaccionales; Spot solo para trabajos tolerantes a interrupcion
  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    base              = 1
    weight            = 1
  }
}
