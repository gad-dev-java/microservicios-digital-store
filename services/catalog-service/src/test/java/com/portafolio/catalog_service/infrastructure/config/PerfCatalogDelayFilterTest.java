package com.portafolio.catalog_service.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PerfCatalogDelayFilterTest {
    @Test
    void publicCatalogBypassesDelayFilter() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/products");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new PerfCatalogDelayFilter(1000).doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void disabledDelayPassesInternalRequestThrough() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/internal/catalog/products/123");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new PerfCatalogDelayFilter(0).doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void internalRequestPassesThroughAfterDelay() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/internal/catalog/products/123");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new PerfCatalogDelayFilter(1).doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void interruptionStopsInternalRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/internal/catalog/products/123");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        Thread.currentThread().interrupt();
        try {
            new PerfCatalogDelayFilter(1).doFilter(request, response, chain);

            assertEquals(503, response.getStatus());
            assertNull(chain.getRequest());
        } finally {
            Thread.interrupted();
        }
    }
}
