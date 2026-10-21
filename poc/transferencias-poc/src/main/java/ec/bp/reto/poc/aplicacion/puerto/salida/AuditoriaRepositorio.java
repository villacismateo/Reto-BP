package ec.bp.reto.poc.aplicacion.puerto.salida;

import ec.bp.reto.poc.dominio.auditoria.RegistroAuditoria;

import java.util.List;
import java.util.Optional;

/** Almacen append-only de la cadena de auditoria. */
public interface AuditoriaRepositorio {
    /** Serializa los escritores de la cadena dentro de la transaccion actual. */
    void bloquearCadena();

    Optional<String> ultimoHash();

    void anexar(RegistroAuditoria registro);

    List<RegistroAuditoria> listarEnOrden();
}
