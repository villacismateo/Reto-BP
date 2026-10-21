package ec.bp.reto.poc.adaptadores.entrada.programado;

import ec.bp.reto.poc.aplicacion.puerto.entrada.PublicarOutboxUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Adaptador de entrada temporal: dispara el relay del outbox cada N ms. */
@Component
@ConditionalOnProperty(name = "poc.outbox.relay-habilitado", havingValue = "true", matchIfMissing = true)
public class OutboxRelayProgramado {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayProgramado.class);
    private final PublicarOutboxUseCase publicar;
    private final int lote;

    public OutboxRelayProgramado(PublicarOutboxUseCase publicar, @Value("${poc.outbox.lote:50}") int lote) {
        this.publicar = publicar;
        this.lote = lote;
    }

    @Scheduled(fixedDelayString = "${poc.outbox.intervalo-ms:1000}")
    public void ejecutar() {
        try {
            int n = publicar.publicarPendientes(lote);
            if (n > 0) {
                log.info("Outbox relay: {} evento(s) publicados", n);
            }
        } catch (RuntimeException e) {
            // El broker o la BD fallaron: los mensajes siguen pendientes y se reintentan en la siguiente pasada.
            log.warn("Outbox relay fallo, se reintentara: {}", e.getMessage());
        }
    }
}
