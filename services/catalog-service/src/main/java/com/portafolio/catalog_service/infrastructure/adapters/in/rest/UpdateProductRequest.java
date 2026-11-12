package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateProductRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,

        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
        BigDecimal price) {}
