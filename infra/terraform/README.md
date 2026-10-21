# Terraform de referencia

Contiene un módulo base (`modulos/red-base`) y un entorno de ejemplo (`entornos/referencia`). **Solo se valida**: no se ejecuta `plan` ni `apply` y no se usan credenciales.

`red-base` crea lo siguiente:
- Una VPC en **3 AZ** con tres capas de subred: pública (ALB/NAT), aplicación (ECS Fargate) y datos (Aurora/ElastiCache, sin salida a Internet).
- Un NAT Gateway por AZ (`nat_por_zona=true`, para HA) o uno solo para entornos que no son productivos (ahorro de costos).
- Una tabla de rutas por AZ, para que la caída de una AZ no afecte la salida de las demás.
- VPC Flow Logs hacia CloudWatch, con retención de 365 días.
- Un cluster ECS con Container Insights, con base on-demand en FARGATE y FARGATE_SPOT opcional.

Validación:

```bash
cd infra/terraform/entornos/referencia
terraform init -backend=false
terraform fmt -check -recursive ../..
terraform validate
```

No incluye ALB, WAF, API Gateway, Aurora, ElastiCache, KMS ni IAM de tareas (los define el documento de arquitectura) ni estado remoto.
