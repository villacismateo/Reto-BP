package ec.bp.reto.poc.dominio.auditoria;

import ec.bp.reto.poc.dominio.Hashing;

import java.util.List;
import java.util.Objects;

/**
 * Cadena de hashes (hash chain): hash_n = SHA-256(hash_{n-1} | contenido_n).
 * Alterar, borrar o reordenar cualquier registro rompe la verificacion desde ese punto (tamper-evidence).
 * En produccion se complementa con almacenamiento WORM (S3 Object Lock) y anclaje periodico del ultimo hash.
 */
public final class CadenaAuditoria {
    public static final String GENESIS = "0".repeat(64);

    private CadenaAuditoria() {
    }

    public static String calcularHash(String hashAnterior, EventoAuditoria evento) {
        return Hashing.sha256(hashAnterior + "|" + evento.canonico());
    }

    public static RegistroAuditoria encadenar(String ultimoHash, EventoAuditoria evento) {
        String anterior = ultimoHash == null ? GENESIS : ultimoHash;
        return new RegistroAuditoria(null, evento, anterior, calcularHash(anterior, evento));
    }

    /** Verifica registros ordenados por id ascendente desde el genesis. */
    public static ResultadoVerificacion verificar(List<RegistroAuditoria> registros) {
        String esperadoAnterior = GENESIS;
        long n = 0;
        for (RegistroAuditoria r : registros) {
            if (!Objects.equals(r.hashAnterior(), esperadoAnterior)) {
                return ResultadoVerificacion.rota(n, r.id(), "hash_anterior no coincide (registro eliminado, insertado o reordenado)");
            }
            if (!Objects.equals(r.hash(), calcularHash(r.hashAnterior(), r.evento()))) {
                return ResultadoVerificacion.rota(n, r.id(), "contenido alterado: el hash recalculado no coincide");
            }
            esperadoAnterior = r.hash();
            n++;
        }
        return ResultadoVerificacion.ok(n);
    }
}
