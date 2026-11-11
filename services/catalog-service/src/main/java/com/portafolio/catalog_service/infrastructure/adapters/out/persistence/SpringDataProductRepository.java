package com.portafolio.catalog_service.infrastructure.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataProductRepository extends JpaRepository<ProductEntity, UUID> {
    @Query(value = """
        SELECT p.*
        FROM products p
        WHERE p.status = 'PUBLISHED'
          AND lower(p.title) LIKE :pattern ESCAPE '!'
        ORDER BY p.title ASC, p.id ASC
        """, countQuery = """
        SELECT count(*)
        FROM products p
        WHERE p.status = 'PUBLISHED'
          AND lower(p.title) LIKE :pattern ESCAPE '!'
        """, nativeQuery = true)
    Page<ProductEntity> searchPublished(@Param("pattern") String pattern, Pageable pageable);
}
