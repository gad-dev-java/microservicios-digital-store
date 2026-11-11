package com.portafolio.catalog_service.infrastructure.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataProductRepository extends JpaRepository<ProductEntity, UUID> {
    @Query(value = """
                    SELECT id, sku, title, description, price, currency, status
                    FROM products
                    WHERE status = 'PUBLISHED'
                      AND lower(title) LIKE :pattern ESCAPE '!'
                    ORDER BY title ASC, id ASC
                    """, countQuery = """
                    SELECT count(*)
                    FROM products
                    WHERE status = 'PUBLISHED'
                      AND lower(title) LIKE :pattern ESCAPE '!'
                    """, nativeQuery = true)
    Page<ProductEntity> searchPublished(@Param("pattern") String pattern, Pageable pageable);
}
