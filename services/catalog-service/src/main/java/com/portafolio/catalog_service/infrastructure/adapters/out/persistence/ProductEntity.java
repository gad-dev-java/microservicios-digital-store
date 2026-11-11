package com.portafolio.catalog_service.infrastructure.adapters.out.persistence;

import com.portafolio.catalog_service.domain.models.ProductStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor
public class ProductEntity {
    @Id
    private UUID id;

    private String sku;
    protected String title;
    private String description;
    private BigDecimal price;
    private String currency;

    @Enumerated(EnumType.STRING)
    private ProductStatus status;
}
