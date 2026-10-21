package ec.bp.reto.poc.aplicacion.servicio;

import ec.bp.reto.poc.aplicacion.puerto.entrada.VerificarAuditoriaUseCase;
import ec.bp.reto.poc.aplicacion.puerto.salida.AuditoriaRepositorio;
import ec.bp.reto.poc.dominio.auditoria.CadenaAuditoria;
import ec.bp.reto.poc.dominio.auditoria.ResultadoVerificacion;

/** Recorre la cadena completa (en produccion: por particion diaria y con checkpoints firmados). */
public class VerificarAuditoriaService implements VerificarAuditoriaUseCase {
    private final AuditoriaRepositorio repositorio;

    public VerificarAuditoriaService(AuditoriaRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public ResultadoVerificacion verificar() {
        return CadenaAuditoria.verificar(repositorio.listarEnOrden());
    }
}
