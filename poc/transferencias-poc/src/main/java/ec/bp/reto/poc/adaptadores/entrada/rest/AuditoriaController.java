package ec.bp.reto.poc.adaptadores.entrada.rest;

import ec.bp.reto.poc.aplicacion.puerto.entrada.VerificarAuditoriaUseCase;
import ec.bp.reto.poc.dominio.auditoria.ResultadoVerificacion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** En el diseno objetivo este endpoint es interno (rol auditor), no se expone al cliente final. */
@RestController
@RequestMapping("/api/v1/auditoria")
public class AuditoriaController {
    private final VerificarAuditoriaUseCase verificar;

    public AuditoriaController(VerificarAuditoriaUseCase verificar) {
        this.verificar = verificar;
    }

    @GetMapping("/verificacion")
    public ResponseEntity<ResultadoVerificacion> verificacion() {
        ResultadoVerificacion r = verificar.verificar();
        return ResponseEntity.status(r.integra() ? HttpStatus.OK : HttpStatus.CONFLICT).body(r);
    }
}
