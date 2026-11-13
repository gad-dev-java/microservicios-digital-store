# Convenciones de la API HTTP

Estas reglas definen el contrato público inicial de HU-03 y sirven de base para las siguientes historias. La entrada pública pasa por Gateway; cada servicio conserva sus rutas y DTO propios. Los adaptadores REST validan y transforman datos, los casos de uso aplican reglas y el dominio no depende de HTTP, `Pageable` ni entidades JPA. El contrato OpenAPI de la demo está en [`docs/contracts/http-api-v1.yaml`](contracts/http-api-v1.yaml).

## Controllers, DTO y mappers

- Inyectar dependencias por constructor. Los controllers reciben y validan parámetros, invocan un caso de uso y convierten su resultado a un DTO; no consultan JPA ni deciden reglas de negocio. En HU-04, usar DTOs de entrada con Bean Validation (`@Valid`) y DTOs de salida; no devolver entidades JPA.
- Empezar con mappers manuales en `infrastructure/adapters/in/rest/` para HTTP y en `infrastructure/adapters/out/persistence/` para JPA. Cada mapper copia campos entre dos representaciones, sin consultas, llamadas remotas ni reglas de negocio. Evaluar MapStruct solo si las conversiones repetidas lo justifican.
- El controller traduce `q`, `page` y `size` a un criterio propio de aplicación; el adaptador JPA puede usar `Pageable`, pero no lo devuelve al dominio ni lo exige en un puerto. Aplicación y dominio trabajan con tipos propios.

## Rutas y representación

- Mantener las rutas ya acordadas bajo `/api/`; no introducir `/v1` solo para HU-03. Usar nombres plurales y JSON en `camelCase`. Los identificadores son UUID; los instantes que se expongan se expresan en ISO 8601 con zona UTC.
- En HU-03, `GET /api/products` y `GET /api/products/{id}` son públicos y de solo lectura. La respuesta incluye solo productos `PUBLISHED`; un borrador, un archivado o un ID inexistente producen el mismo `404` en el detalle. No exponer `storageKey`, rutas, checksum ni metadatos del archivo.
- El precio se devuelve como número decimal y la moneda como `"PEN"`. En Java se usa `BigDecimal`; el frontend no calcula el importe definitivo del pedido. Catalog es dueño del precio y Order captura una instantánea al crear el pedido.
- DTO de lista: `id`, `title`, `price`, `currency`. DTO de detalle: los mismos campos más `description`. Los campos adicionales se justifican en el contrato antes de implementarlos.

## Búsqueda y paginación de HU-03

`GET /api/products?q=java&page=0&size=20` filtra por título. `q` es opcional: se recortan espacios, una cadena vacía equivale a no filtrar y el máximo es 100 caracteres. La comparación ignora mayúsculas y trata `%` y `_` como texto literal. `page` empieza en 0 (por defecto 0); `size` va de 1 a 50 (por defecto 20). El orden fijo es `title ASC, id ASC`, para que páginas sucesivas sean deterministas. No se expone un parámetro de ordenación en el MVP.

```json
{
  "items": [{ "id": "f6e1df28-d585-4bfa-a251-8f49a14ff60e", "title": "Guía Java", "price": 39.90, "currency": "PEN" }],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

Una búsqueda sin resultados devuelve `200` con `items: []` y `totalItems: 0`; parámetros inválidos devuelven `400`. El acceso a PostgreSQL usa consultas parametrizadas. Redis puede acelerar las lecturas, pero no cambia este contrato.

## Estados y errores

Responder `200` en lecturas correctas, `400` para parámetros inválidos y `404` para productos no visibles. Las rutas privadas usan `401` sin autenticación, `403` sin permiso y `503` cuando una dependencia necesaria no esté disponible. Para los endpoints nuevos, adoptar errores `application/problem+json` conforme a RFC 9457, con extensión estable `code` (por ejemplo, `PRODUCT_NOT_FOUND`); HT-08 añadió `traceId` cuando existe una traza. El detalle no incluye SQL, rutas internas ni datos sensibles.

Cada servicio MVC mapea sus errores de negocio en un `@ControllerAdvice` único; centralizar la creación del `ProblemDetail` para compartir el formato con los errores de Spring MVC (`404/405/415`) y los `401/403` de Spring Security, que no pasan por ese advice. Gateway WebFlux necesita su propio tratamiento reactivo. Probar cada ruta de error antes de declarar uniforme el contrato. [Problem Details en Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).

## Pruebas de contrato

Comprobar listado solo `PUBLISHED`, búsqueda sin distinguir mayúsculas ni interpretar comodines, paginación y orden estable, parámetros inválidos, detalle visible y `404` para ID inexistente o no publicado. Verificar que Gateway enruta tanto `/api/products` como `/api/products/{id}`. Probar el comportamiento HTTP y la consulta real de PostgreSQL; añadir ArchUnit para las dependencias `domain`/`application` definidas en [repository-structure.md](repository-structure.md).
