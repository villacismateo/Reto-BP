package ec.bp.reto.poc.aplicacion.puerto.salida;

import ec.bp.reto.poc.dominio.transferencia.TransferenciaRegistrada;

import java.util.List;
import java.util.UUID;

/** Transactional Outbox: el evento se guarda en la MISMA transaccion que el cambio de estado. */
public interface OutboxRepositorio {
    void agregar(TransferenciaRegistrada evento);

    /** Toma pendientes bloqueandolos (FOR UPDATE SKIP LOCKED) para permitir varias replicas del relay. */
    List<MensajeOutbox> tomarPendientes(int limite);

    void marcarPublicado(UUID id);
}
