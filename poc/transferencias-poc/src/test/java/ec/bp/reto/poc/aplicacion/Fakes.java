package ec.bp.reto.poc.aplicacion;

import ec.bp.reto.poc.aplicacion.puerto.salida.AuditoriaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.CacheMovimientos;
import ec.bp.reto.poc.aplicacion.puerto.salida.CoreBancario;
import ec.bp.reto.poc.aplicacion.puerto.salida.IdempotenciaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.MensajeOutbox;
import ec.bp.reto.poc.aplicacion.puerto.salida.OutboxRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.TransferenciaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.UnidadDeTrabajo;
import ec.bp.reto.poc.dominio.auditoria.RegistroAuditoria;
import ec.bp.reto.poc.dominio.idempotencia.RegistroIdempotencia;
import ec.bp.reto.poc.dominio.movimientos.Movimiento;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import ec.bp.reto.poc.dominio.transferencia.Transferencia;
import ec.bp.reto.poc.dominio.transferencia.TransferenciaRegistrada;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Dobles en memoria de los puertos de salida: prueban la aplicacion sin Spring, BD ni red. */
final class Fakes {
    private Fakes() {
    }

    static final UnidadDeTrabajo SIN_TX = new UnidadDeTrabajo() {
        @Override
        public <T> T enTransaccion(Supplier<T> trabajo) {
            return trabajo.get();
        }
    };

    static class Idempotencia implements IdempotenciaRepositorio {
        final Map<String, RegistroIdempotencia> datos = new HashMap<>();

        public Optional<RegistroIdempotencia> buscar(String clave) {
            return Optional.ofNullable(datos.get(clave));
        }

        public boolean reservar(RegistroIdempotencia r) {
            return datos.putIfAbsent(r.clave(), r) == null;
        }
    }

    static class Transferencias implements TransferenciaRepositorio {
        final Map<UUID, Transferencia> datos = new HashMap<>();

        public void guardar(Transferencia t) {
            datos.put(t.id(), t);
        }

        public Optional<Transferencia> buscarPorId(UUID id) {
            return Optional.ofNullable(datos.get(id));
        }
    }

    static class Outbox implements OutboxRepositorio {
        final List<TransferenciaRegistrada> eventos = new ArrayList<>();

        public void agregar(TransferenciaRegistrada e) {
            eventos.add(e);
        }

        public List<MensajeOutbox> tomarPendientes(int limite) {
            return List.of();
        }

        public void marcarPublicado(UUID id) {
        }
    }

    static class Auditoria implements AuditoriaRepositorio {
        final List<RegistroAuditoria> registros = new ArrayList<>();

        public void bloquearCadena() {
        }

        public Optional<String> ultimoHash() {
            return registros.isEmpty() ? Optional.empty() : Optional.of(registros.getLast().hash());
        }

        public void anexar(RegistroAuditoria r) {
            registros.add(new RegistroAuditoria((long) registros.size() + 1, r.evento(), r.hashAnterior(), r.hash()));
        }

        public List<RegistroAuditoria> listarEnOrden() {
            return registros;
        }
    }

    static class Cache implements CacheMovimientos {
        final Map<String, List<Movimiento>> vigente = new HashMap<>();
        final Map<String, List<Movimiento>> respaldo = new HashMap<>();

        public Optional<List<Movimiento>> vigente(NumeroCuenta c) {
            return Optional.ofNullable(vigente.get(c.valor()));
        }

        public Optional<List<Movimiento>> respaldo(NumeroCuenta c) {
            return Optional.ofNullable(respaldo.get(c.valor()));
        }

        public void guardar(NumeroCuenta c, List<Movimiento> m) {
            vigente.put(c.valor(), m);
            respaldo.put(c.valor(), m);
        }
    }

    static class Core implements CoreBancario {
        boolean caido;
        int llamadas;
        List<Movimiento> movimientos = List.of();

        public List<Movimiento> movimientos(NumeroCuenta cuenta) {
            llamadas++;
            if (caido) {
                throw new CoreNoDisponibleException("simulado", null);
            }
            return movimientos;
        }
    }
}
