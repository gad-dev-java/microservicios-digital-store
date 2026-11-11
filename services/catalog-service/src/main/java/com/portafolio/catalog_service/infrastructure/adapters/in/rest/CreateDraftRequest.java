package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateDraftRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        String sku,

        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,

        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
        BigDecimal price) {}
