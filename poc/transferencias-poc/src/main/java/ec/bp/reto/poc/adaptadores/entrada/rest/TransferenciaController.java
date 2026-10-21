package ec.bp.reto.poc.adaptadores.entrada.rest;

import ec.bp.reto.poc.aplicacion.puerto.entrada.RegistrarTransferenciaUseCase;
import ec.bp.reto.poc.dominio.idempotencia.ClaveIdempotencia;
import ec.bp.reto.poc.dominio.transferencia.Monto;
import ec.bp.reto.poc.dominio.transferencia.NumeroCuenta;
import ec.bp.reto.poc.dominio.transferencia.SolicitudTransferencia;
import ec.bp.reto.poc.dominio.transferencia.TipoTransferencia;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Adaptador de entrada REST. X-Usuario simula el "sub" del access token que en el diseno objetivo
 * valida el API Gateway (OAuth 2.0 / OIDC); en la PoC no hay autenticacion.
 */
@RestController
@RequestMapping("/api/v1/transferencias")
public class TransferenciaController {
    private final RegistrarTransferenciaUseCase registrar;

    public TransferenciaController(RegistrarTransferenciaUseCase registrar) {
        this.registrar = registrar;
    }

    @PostMapping("/propias")
    public ResponseEntity<TransferenciaResponse> propia(@RequestHeader("Idempotency-Key") String clave,
                                                        @RequestHeader(value = "X-Usuario", defaultValue = "anonimo") String usuario,
                                                        @RequestBody TransferenciaRequest cuerpo) {
        return ejecutar(TipoTransferencia.PROPIA, clave, usuario, cuerpo);
    }

    @PostMapping("/interbancarias")
    public ResponseEntity<TransferenciaResponse> interbancaria(@RequestHeader("Idempotency-Key") String clave,
                                                               @RequestHeader(value = "X-Usuario", defaultValue = "anonimo") String usuario,
                                                               @RequestBody TransferenciaRequest cuerpo) {
        return ejecutar(TipoTransferencia.INTERBANCARIA, clave, usuario, cuerpo);
    }

    private ResponseEntity<TransferenciaResponse> ejecutar(TipoTransferencia tipo, String clave, String usuario,
                                                           TransferenciaRequest c) {
        var solicitud = new SolicitudTransferencia(tipo, new NumeroCuenta(c.cuentaOrigen()),
                new NumeroCuenta(c.cuentaDestino()), c.bancoDestino(),
                new Monto(c.monto(), c.moneda() == null ? "USD" : c.moneda()), c.referencia());
        var resultado = registrar.registrar(new ClaveIdempotencia(clave), solicitud, usuario);
        var cuerpo = TransferenciaResponse.de(resultado.transferencia());
        if (resultado.repetida()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(cuerpo);
        }
        return ResponseEntity.created(URI.create("/api/v1/transferencias/" + cuerpo.id())).body(cuerpo);
    }
}
