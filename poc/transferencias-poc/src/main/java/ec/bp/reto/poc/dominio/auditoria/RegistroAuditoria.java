package ec.bp.reto.poc.dominio.auditoria;

/** Eslabon de la cadena: id asignado por la BD (null antes de persistir). */
public record RegistroAuditoria(Long id, EventoAuditoria evento, String hashAnterior, String hash) {
}
