package ec.bp.reto.poc.dominio.transferencia;

import ec.bp.reto.poc.dominio.ReglaNegocioException;

/** Objeto de valor: numero de cuenta (10 digitos en esta PoC). */
public record NumeroCuenta(String valor) {
    public NumeroCuenta {
        if (valor == null || !valor.matches("\\d{10}")) {
            throw new ReglaNegocioException("Numero de cuenta invalido: debe tener 10 digitos");
        }
    }

    /** Enmascarado para auditoria (minimizacion de datos personales, LOPDP). */
    public String enmascarado() {
        return "******" + valor.substring(6);
    }
}
