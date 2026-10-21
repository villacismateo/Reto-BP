package ec.bp.reto.poc.dominio.auditoria;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Accion auditable. ocurridoEn se trunca a microsegundos para que el hash sea reproducible tras persistir en PostgreSQL. */
public record EventoAuditoria(Instant ocurridoEn, String actor, String accion, String recurso, String detalle) {
    public EventoAuditoria {
        ocurridoEn = ocurridoEn.truncatedTo(ChronoUnit.MICROS);
        actor = actor == null ? "anonimo" : actor;
        detalle = detalle == null ? "" : detalle;
    }

    String canonico() {
        return String.join("|", ocurridoEn.toString(), actor, accion, recurso, detalle);
    }
}
