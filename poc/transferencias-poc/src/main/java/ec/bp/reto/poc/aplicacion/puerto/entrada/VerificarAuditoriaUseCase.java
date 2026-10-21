package ec.bp.reto.poc.aplicacion.puerto.entrada;

import ec.bp.reto.poc.dominio.auditoria.ResultadoVerificacion;

/** Puerto de entrada: verificar la integridad de la cadena de auditoria. */
public interface VerificarAuditoriaUseCase {
    ResultadoVerificacion verificar();
}
