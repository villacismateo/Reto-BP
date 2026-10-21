package ec.bp.reto.poc.adaptadores.salida.persistencia;

import ec.bp.reto.poc.aplicacion.puerto.salida.TransferenciaRepositorio;
import ec.bp.reto.poc.dominio.transferencia.EstadoTransferencia;
import ec.bp.reto.poc.dominio.transferencia.Monto;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import ec.bp.reto.poc.dominio.transferencia.SolicitudTransferencia;
import ec.bp.reto.poc.dominio.transferencia.TipoTransferencia;
import ec.bp.reto.poc.dominio.transferencia.Transferencia;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransferenciaRepositorio implements TransferenciaRepositorio {
    private final JdbcClient jdbc;

    public JdbcTransferenciaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(Transferencia t) {
        var s = t.solicitud();
        jdbc.sql("""
                        INSERT INTO transferencia (id, tipo, cuenta_origen, cuenta_destino, banco_destino, monto, moneda,
                                                   referencia, estado, solicitante, creada_en)
                        VALUES (:id, :tipo, :origen, :destino, :banco, :monto, :moneda, :referencia, :estado, :solicitante, :creadaEn)
                        """)
                .param("id", t.id()).param("tipo", s.tipo().name())
                .param("origen", s.cuentaOrigen().valor()).param("destino", s.cuentaDestino().valor())
                .param("banco", s.bancoDestino()).param("monto", s.monto().valor()).param("moneda", s.monto().moneda())
                .param("referencia", s.referencia()).param("estado", t.estado().name())
                .param("solicitante", t.solicitante()).param("creadaEn", Timestamp.from(t.creadaEn()))
                .update();
    }

    @Override
    public Optional<Transferencia> buscarPorId(UUID id) {
        return jdbc.sql("SELECT * FROM transferencia WHERE id = :id").param("id", id)
                .query((rs, n) -> new Transferencia(
                        rs.getObject("id", UUID.class),
                        new SolicitudTransferencia(TipoTransferencia.valueOf(rs.getString("tipo")),
                                new NumeroCuenta(rs.getString("cuenta_origen")),
                                new NumeroCuenta(rs.getString("cuenta_destino")),
                                rs.getString("banco_destino"),
                                new Monto(rs.getBigDecimal("monto"), rs.getString("moneda")),
                                rs.getString("referencia")),
                        EstadoTransferencia.valueOf(rs.getString("estado")),
                        rs.getString("solicitante"),
                        rs.getTimestamp("creada_en").toInstant()))
                .optional();
    }
}
