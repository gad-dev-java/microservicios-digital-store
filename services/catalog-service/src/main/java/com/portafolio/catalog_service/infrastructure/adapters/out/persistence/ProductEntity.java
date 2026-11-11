package com.portafolio.catalog_service.infrastructure.adapters.out.persistence;

import com.portafolio.catalog_service.domain.models.Product;
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

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    public ProductEntity(Product product) {
        this.id = product.id();
        this.sku = product.sku();
        this.title = product.title();
        this.description = product.description();
        this.price = product.price();
        this.currency = product.currency();
        this.status = product.status();

        if (product.file() != null) {
            this.storageKey = product.file().key();
            this.fileName = product.file().fileName();
            this.contentType = product.file().contentType();
            this.fileSize = product.file().size();
            this.checksumSha256 = product.file().sha256();
        }
    }
}
