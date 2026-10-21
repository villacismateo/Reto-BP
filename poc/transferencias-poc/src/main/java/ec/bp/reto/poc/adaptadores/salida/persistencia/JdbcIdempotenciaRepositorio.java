package ec.bp.reto.poc.adaptadores.salida.persistencia;

import ec.bp.reto.poc.aplicacion.puerto.salida.IdempotenciaRepositorio;
import ec.bp.reto.poc.dominio.idempotencia.RegistroIdempotencia;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotenciaRepositorio implements IdempotenciaRepositorio {
    private final JdbcClient jdbc;

    public JdbcIdempotenciaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegistroIdempotencia> buscar(String clave) {
        return jdbc.sql("SELECT clave, recurso, huella_solicitud, transferencia_id FROM idempotencia WHERE clave = :clave")
                .param("clave", clave)
                .query((rs, n) -> new RegistroIdempotencia(rs.getString("clave"), rs.getString("recurso"),
                        rs.getString("huella_solicitud"), rs.getObject("transferencia_id", UUID.class)))
                .optional();
    }

    @Override
    public boolean reservar(RegistroIdempotencia r) {
        // ON CONFLICT DO NOTHING: si otra transaccion concurrente inserto la misma clave, espera su commit y no inserta.
        return jdbc.sql("""
                        INSERT INTO idempotencia (clave, recurso, huella_solicitud, transferencia_id)
                        VALUES (:clave, :recurso, :huella, :transferenciaId)
                        ON CONFLICT (clave) DO NOTHING
                        """)
                .param("clave", r.clave()).param("recurso", r.recurso())
                .param("huella", r.huellaSolicitud()).param("transferenciaId", r.transferenciaId())
                .update() == 1;
    }
}
