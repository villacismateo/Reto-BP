package ec.bp.reto.poc.dominio.auditoria;

/** Resultado de recorrer la cadena de auditoria. */
public record ResultadoVerificacion(boolean integra, long registrosVerificados, Long primerRegistroAlterado, String motivo) {
    public static ResultadoVerificacion ok(long total) {
        return new ResultadoVerificacion(true, total, null, null);
    }

    public static ResultadoVerificacion rota(long verificados, Long id, String motivo) {
        return new ResultadoVerificacion(false, verificados, id, motivo);
    }
}
