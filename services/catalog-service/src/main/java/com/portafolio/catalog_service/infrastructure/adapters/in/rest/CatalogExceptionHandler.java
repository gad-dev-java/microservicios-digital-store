package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.application.ports.out.AssetStorageException;
import com.portafolio.catalog_service.domain.exceptions.InvalidProductException;
import com.portafolio.catalog_service.domain.exceptions.InvalidProductStateException;
import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Order(-1)
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
    })
    ProblemDetail badRequest(Exception exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parámetros de catálogo inválidos");
        problem.setProperty("code", "INVALID_CATALOG_QUERY");
        return problem;
    }

    @ExceptionHandler({InvalidProductException.class, MethodArgumentNotValidException.class})
    ProblemDetail invalidProduct(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Datos de producto inválidos");
        problem.setProperty("code", "INVALID_PRODUCT");
        return problem;
    }

    @ExceptionHandler({InvalidProductStateException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Estado inválido o SKU ya existente");
        problem.setProperty("code", "PRODUCT_CONFLICT");
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail fileTooLarge(MaxUploadSizeExceededException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "El archivo supera el tamaño permitido");
        problem.setProperty("code", "FILE_TOO_LARGE");
        return problem;
    }

    @ExceptionHandler(AssetStorageException.class)
    ProblemDetail storageUnavailable(AssetStorageException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "No se pudo acceder al almacén de archivos");
        problem.setProperty("code", "ASSET_STORAGE_UNAVAILABLE");
        return problem;
    }
}
