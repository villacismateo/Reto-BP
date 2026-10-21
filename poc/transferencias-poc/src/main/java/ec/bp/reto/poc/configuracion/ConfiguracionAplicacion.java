package ec.bp.reto.poc.configuracion;

import ec.bp.reto.poc.aplicacion.puerto.entrada.ConsultarMovimientosUseCase;
import ec.bp.reto.poc.aplicacion.puerto.entrada.PublicarOutboxUseCase;
import ec.bp.reto.poc.aplicacion.puerto.entrada.RegistrarTransferenciaUseCase;
import ec.bp.reto.poc.aplicacion.puerto.entrada.VerificarAuditoriaUseCase;
import ec.bp.reto.poc.aplicacion.puerto.salida.AuditoriaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.CacheMovimientos;
import ec.bp.reto.poc.aplicacion.puerto.salida.CoreBancario;
import ec.bp.reto.poc.aplicacion.puerto.salida.IdempotenciaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.OutboxRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.PublicadorEventos;
import ec.bp.reto.poc.aplicacion.puerto.salida.TransferenciaRepositorio;
import ec.bp.reto.poc.aplicacion.puerto.salida.UnidadDeTrabajo;
import ec.bp.reto.poc.aplicacion.servicio.ConsultarMovimientosService;
import ec.bp.reto.poc.aplicacion.servicio.PublicarOutboxService;
import ec.bp.reto.poc.aplicacion.servicio.RegistradorAuditoria;
import ec.bp.reto.poc.aplicacion.servicio.RegistrarTransferenciaService;
import ec.bp.reto.poc.aplicacion.servicio.VerificarAuditoriaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Composition root: el unico lugar que conoce aplicacion + adaptadores. El dominio y la aplicacion no usan Spring. */
@Configuration
public class ConfiguracionAplicacion {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    RegistradorAuditoria registradorAuditoria(AuditoriaRepositorio repositorio) {
        return new RegistradorAuditoria(repositorio);
    }

    @Bean
    RegistrarTransferenciaUseCase registrarTransferencia(UnidadDeTrabajo uow, IdempotenciaRepositorio idem,
                                                         TransferenciaRepositorio repo, OutboxRepositorio outbox,
                                                         RegistradorAuditoria auditoria, Clock reloj) {
        return new RegistrarTransferenciaService(uow, idem, repo, outbox, auditoria, reloj);
    }

    @Bean
    ConsultarMovimientosUseCase consultarMovimientos(CacheMovimientos cache, CoreBancario core, UnidadDeTrabajo uow,
                                                     RegistradorAuditoria auditoria, Clock reloj) {
        return new ConsultarMovimientosService(cache, core, uow, auditoria, reloj);
    }

    @Bean
    VerificarAuditoriaUseCase verificarAuditoria(AuditoriaRepositorio repositorio) {
        return new VerificarAuditoriaService(repositorio);
    }

    @Bean
    PublicarOutboxUseCase publicarOutbox(UnidadDeTrabajo uow, OutboxRepositorio outbox, PublicadorEventos publicador) {
        return new PublicarOutboxService(uow, outbox, publicador);
    }
}
