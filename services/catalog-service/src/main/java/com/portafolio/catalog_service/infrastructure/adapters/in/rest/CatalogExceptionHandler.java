package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class CatalogExceptionHandler {
    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail notFound(ProductNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setProperty("code", "PRODUCT_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler({
        InvalidCatalogQueryException.class,
        MethodArgumentTypeMismatchException.class,
        MethodArgumentNotValidException.class
    })
    ProblemDetail badRequest(Exception exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parámetros de catálogo inválidos");
        problem.setProperty("code", "INVALID_CATALOG_QUERY");
        return problem;
    }
}
