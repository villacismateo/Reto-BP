package ec.bp.reto.poc.adaptadores.entrada.rest;

import ec.bp.reto.poc.aplicacion.puerto.entrada.ConsultarMovimientosUseCase;
import ec.bp.reto.poc.dominio.movimientos.ConsultaMovimientos;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cuentas")
public class MovimientosController {
    private final ConsultarMovimientosUseCase consultar;

    public MovimientosController(ConsultarMovimientosUseCase consultar) {
        this.consultar = consultar;
    }

    @GetMapping("/{cuenta}/movimientos")
    public ResponseEntity<ConsultaMovimientos> movimientos(@PathVariable String cuenta,
                                                           @RequestHeader(value = "X-Usuario", defaultValue = "anonimo") String usuario) {
        ConsultaMovimientos r = consultar.consultar(new NumeroCuenta(cuenta), usuario);
        return ResponseEntity.ok()
                .header("X-Origen-Datos", r.origen().name())
                .header("X-Respuesta-Degradada", String.valueOf(r.degradado()))
                .body(r);
    }
}
