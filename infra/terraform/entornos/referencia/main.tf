# Entorno de referencia (solo `terraform validate`; no se aplica en este reto).
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }
  # backend "s3" { ... }  # estado remoto cifrado + bloqueo; se omite a proposito
}

provider "aws" {
  region = "us-east-1"
}

module "red_base" {
  source       = "../../modulos/red-base"
  nombre       = "bp-banca-ref"
  zonas        = ["us-east-1a", "us-east-1b", "us-east-1c"]
  nat_por_zona = true
  etiquetas = {
    entorno       = "referencia"
    centro-costos = "banca-digital"
  }
}

output "ecs_cluster_arn" {
  value = module.red_base.ecs_cluster_arn
}
