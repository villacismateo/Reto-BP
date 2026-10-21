package ec.bp.reto.poc.adaptadores.entrada.rest;

import ec.bp.reto.poc.aplicacion.ClaveEnCursoException;
import ec.bp.reto.poc.aplicacion.ClaveReutilizadaException;
import ec.bp.reto.poc.aplicacion.ServicioNoDisponibleException;
import ec.bp.reto.poc.dominio.ReglaNegocioException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce excepciones de dominio/aplicacion a RFC 9457 (Problem Details). */
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(ReglaNegocioException.class)
    ProblemDetail reglaNegocio(ReglaNegocioException e) {
        return problema(HttpStatus.BAD_REQUEST, "Solicitud invalida", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail headerFaltante(MissingRequestHeaderException e) {
        return problema(HttpStatus.BAD_REQUEST, "Header obligatorio", "Falta el header " + e.getHeaderName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpoIlegible(HttpMessageNotReadableException e) {
        return problema(HttpStatus.BAD_REQUEST, "Cuerpo invalido", "JSON mal formado");
    }

    /** ADR-009: la misma clave tiene una solicitud aun en curso. */
    @ExceptionHandler(ClaveEnCursoException.class)
    ProblemDetail claveEnCurso(ClaveEnCursoException e) {
        return problema(HttpStatus.CONFLICT, "Solicitud en curso", e.getMessage());
    }

    /** ADR-009: misma clave con otro cuerpo o en otro recurso (reutilizacion indebida). */
    @ExceptionHandler(ClaveReutilizadaException.class)
    ProblemDetail claveReutilizada(ClaveReutilizadaException e) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key reutilizada", e.getMessage());
    }

    @ExceptionHandler(ServicioNoDisponibleException.class)
    ProblemDetail noDisponible(ServicioNoDisponibleException e) {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", e.getMessage());
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, detalle);
        p.setTitle(titulo);
        return p;
    }
}
