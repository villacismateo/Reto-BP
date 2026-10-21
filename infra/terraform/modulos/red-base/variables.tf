variable "nombre" {
  description = "Prefijo de nombres (p. ej. bp-banca-prod)"
  type        = string
}

variable "cidr_vpc" {
  description = "CIDR de la VPC"
  type        = string
  default     = "10.40.0.0/16"
}

variable "zonas" {
  description = "Tres zonas de disponibilidad (HA multi-AZ)"
  type        = list(string)
  validation {
    condition     = length(var.zonas) == 3
    error_message = "El diseno exige exactamente 3 AZ."
  }
}

variable "nat_por_zona" {
  description = "true = un NAT Gateway por AZ (HA, produccion); false = uno solo (ahorro en no-produccion)"
  type        = bool
  default     = true
}

variable "etiquetas" {
  description = "Etiquetas comunes (costos y gobierno)"
  type        = map(string)
  default     = {}
}
