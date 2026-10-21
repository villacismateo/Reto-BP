output "vpc_id" {
  value = aws_vpc.this.id
}

output "subredes_publicas" {
  value = [for s in aws_subnet.publica : s.id]
}

output "subredes_aplicacion" {
  value = [for s in aws_subnet.aplicacion : s.id]
}

output "subredes_datos" {
  value = [for s in aws_subnet.datos : s.id]
}

output "ecs_cluster_arn" {
  value = aws_ecs_cluster.this.arn
}
