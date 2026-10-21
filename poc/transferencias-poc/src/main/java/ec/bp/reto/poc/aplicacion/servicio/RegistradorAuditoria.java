package ec.bp.reto.poc.aplicacion.servicio;

import ec.bp.reto.poc.aplicacion.puerto.salida.AuditoriaRepositorio;
import ec.bp.reto.poc.dominio.auditoria.CadenaAuditoria;
import ec.bp.reto.poc.dominio.auditoria.EventoAuditoria;

/** Anexa un eslabon a la cadena. Debe invocarse dentro de una transaccion. */
public class RegistradorAuditoria {
    private final AuditoriaRepositorio repositorio;

    public RegistradorAuditoria(AuditoriaRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    public void registrar(EventoAuditoria evento) {
        repositorio.bloquearCadena();
        String ultimo = repositorio.ultimoHash().orElse(CadenaAuditoria.GENESIS);
        repositorio.anexar(CadenaAuditoria.encadenar(ultimo, evento));
    }
}
