# Estructura del repositorio

Este árbol distingue la estructura ya creada de las carpetas previstas para las próximas historias. Los seis módulos Maven, la web, la infraestructura local y las migraciones V1 ya existen.

```text
D:/portafolio/
├── pom.xml                         # Maven padre: versiones, plugins y seis módulos
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper
├── compose.yaml                    # Stack local y redes internas
├── .env.example                    # Nombres de variables, sin credenciales
├── .gitignore
├── README.md                       # CI y despliegue local; la guía de demo sigue pendiente
├── AGENTS.md
├── script.sql                      # Referencia SQL; las migraciones V1 son la fuente ejecutable
├── services/
│   ├── catalog-service/            # Seguridad y migración V1; productos pendientes
│   ├── order-service/              # Seguridad y migración V1; pedidos y pagos pendientes
│   ├── notification-service/       # Migración V1; consumo Kafka y correo pendientes
│   ├── gateway-service/            # Rutas, JWT y bloqueo de rutas internas
│   ├── config-server/              # Config Server con backend native
│   └── discovery-server/           # Eureka Server
├── web/                            # React + Vite; proyecto npm independiente
│   ├── src/app/                    # Keycloak y fetch autenticado; router futuro
│   ├── src/features/               # Previsto: catalog, checkout, orders, library, admin
│   ├── src/shared/                 # Previsto: componentes y utilidades reutilizables
│   └── public/                     # Recursos estáticos pequeños
├── config-repo/                    # application.yaml y gateway-service.yaml sin secretos
├── infra/
│   ├── postgres/init/              # Solo CREATE DATABASE para el primer arranque
│   ├── keycloak/                   # Realm: roles, clientes (incluida la cuenta de servicio) y flujos de cuenta
│   ├── kafka/                      # Aprovisionamiento de orders.events.v1 y orders.events.v1.DLT
│   └── assets/demo/                # Previsto: archivos digitales de muestra
├── docs/
│   ├── architecture.md
│   ├── api-conventions.md            # Contrato HTTP inicial de HU-03 y reglas compartidas
│   ├── observability.md              # Logging y trazabilidad previstos para HT-08
│   ├── repository-structure.md
│   ├── user-stories.md              # Backlog del MVP con criterios de aceptación
│   ├── adr/                        # ADR 001 de descargas y futuras decisiones
│   └── contracts/                  # Previsto: OpenAPI y esquema JSON de eventos Kafka
└── .github/workflows/
    ├── ci.yml                      # Maven verify, lint/build web e imágenes
    └── deploy-local.yml            # Despliegue manual con Compose
```

## Convención de los módulos Java

Los seis módulos de `services/` son aplicaciones Spring Boot independientes; `web/` queda fuera del reactor Maven. Cada módulo tiene `pom.xml`, `src/main/java`, `src/main/resources/application.yaml` y `src/test/java`. Los paquetes base actuales son `com.portafolio.catalog_service`, `com.portafolio.order_service` y `com.portafolio.notification_service`; los tres servicios de soporte siguen el mismo patrón de nombre. La clase `*Application` está en el paquete raíz para incluir sus subpaquetes en el escaneo de Spring. [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [estructura de Spring Boot](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html).

Catalog, Order y Notification Service usan **arquitectura hexagonal + DDD pragmático**. Cada servicio de negocio es un contexto delimitado: Catálogo, Pedidos o Notificaciones. Dentro de cada módulo se usan exactamente las carpetas superiores `domain/`, `application/` e `infrastructure/`, siguiendo la plantilla acordada. `domain` no importa Spring/JPA; `application` no importa JPA ni clases de `infrastructure` (puede usar `@Service` y `@Transactional` de Spring); `infrastructure/adapters` implementa los puertos. Crear un puerto solo cuando existe una dependencia externa, no para cada clase. ArchUnit se añade como dependencia desde HT-01 y verifica estas reglas cuando cada servicio tenga clases de dominio y aplicación. [DDD Reference](https://www.domainlanguage.com/ddd/reference/), [puertos y adaptadores](https://alistair.cockburn.us/hexagonal-architecture).

## Carpetas internas de los servicios

`services/order-service` usará la siguiente plantilla al implementar sus casos de uso; la ruta bajo `java/` equivale al paquete Java. Actualmente existen la clase `*Application`, `infrastructure/config/`, la migración V1 y los tests de seguridad:

```text
services/order-service/
├── pom.xml
├── src/main/java/com/portafolio/order_service/
│   ├── OrderServiceApplication.java
│   ├── domain/
│   │   ├── model/                    # Order, OrderItem, PaymentAttempt, Entitlement, Money
│   │   ├── events/                   # OrderPaid: evento de dominio
│   │   └── exceptions/               # InvalidOrderStateException
│   ├── application/
│   │   ├── ports/
│   │   │   ├── in/                   # CreateOrderUseCase, CheckoutOrderUseCase,
│   │   │   │                         # ConfirmPaymentUseCase, ReconcilePaymentUseCase, DownloadProductUseCase
│   │   │   └── out/                  # OrderRepositoryPort, EntitlementRepositoryPort,
│   │   │                             # PaymentGatewayPort, CatalogPort, OutboxPort
│   │   └── service/                  # Casos de uso; la confirmación del pago separa la consulta
│   │                                 # al proveedor (sin transacción) de su aplicación (transaccional)
│   └── infrastructure/
│       ├── adapters/
│       │   ├── in/
│       │   │   ├── rest/            # OrderController, LibraryController, AdminPaymentController, DTO
│       │   │   ├── webhook/         # MercadoPagoWebhookController y verificación de firma
│       │   │   └── scheduler/       # OutboxRelayScheduler, PaymentReconciliationScheduler, RetentionScheduler
│       │   └── out/
│       │       ├── persistence/     # JPA, entitlements, outbox, mapeadores
│       │       ├── catalog/         # HTTP de productos y streaming, con token de cuenta de servicio
│       │       ├── mercadopago/     # Orders API: crear, buscar y consultar; Resilience4j
│       │       └── kafka/           # Publica con key = order_id
│       └── config/                  # Seguridad, clientes HTTP (timeouts) y beans
├── src/main/resources/
│   ├── application.yaml              # Bootstrap y valores locales mínimos
│   └── db/migration/V1__create_order.sql
└── src/test/java/com/portafolio/order_service/
    ├── architecture/                  # ArchUnit: reglas de dependencia
    ├── domain/                        # Reglas y transiciones de estado (*Test)
    ├── application/                   # Casos de uso con puertos simulados
    └── infrastructure/                # Integración real con Testcontainers y WireMock (*IT)
```

`catalog-service` y `notification-service` repiten las mismas tres carpetas superiores, con adaptadores propios:

```text
services/catalog-service/src/main/java/com/portafolio/catalog_service/
├── domain/model/                     # Product (raíz), ProductStatus, Money
├── domain/exceptions/
├── application/ports/in/             # ListProducts, ManageProduct, StreamAsset
├── application/ports/out/            # ProductRepository, Cache, AssetStorage
├── application/service/
├── infrastructure/adapters/in/rest/  # API pública y administración
├── infrastructure/adapters/in/internal/ # Archivo para Order Service (valida JWT de cuenta de servicio)
├── infrastructure/adapters/out/persistence/ # PostgreSQL y mapeadores
├── infrastructure/adapters/out/redis/
├── infrastructure/adapters/out/files/  # Claves UUID y control de ruta dentro del directorio permitido
└── infrastructure/config/

services/notification-service/src/main/java/com/portafolio/notification_service/
├── domain/model/                     # EmailDelivery (raíz), DeliveryStatus
├── domain/exceptions/
├── application/ports/in/             # HandleOrderPaidUseCase
├── application/ports/out/            # DeliveryRepositoryPort, MailSenderPort
├── application/service/
├── infrastructure/adapters/in/kafka/ # Consume orders.events.v1; ErrorHandlingDeserializer y DLT
├── infrastructure/adapters/in/scheduler/ # Envío y reintentos de correo
├── infrastructure/adapters/out/persistence/ # Inbox y estados
├── infrastructure/adapters/out/smtp/
└── infrastructure/config/
```

Cada servicio conserva sus migraciones y tests. Gateway ya tiene `config/` para seguridad y conversión de roles; Config Server y Discovery Server mantienen configuración mínima. No se crean carpetas de dominio vacías en estos servicios de soporte. En `web/src/features/<función>/` colocar `pages/`, `components/` y `api/` cuando se necesiten; `web/src/app/` contiene la autenticación y el cliente HTTP, mientras que el router y `web/src/shared/` siguen pendientes.

`Order` gobierna sus líneas, intentos de pago y cambios de estado. `Entitlement` es un modelo de dominio sencillo y la fuente para autorizar descargas; se persiste mediante `EntitlementRepositoryPort` y la regla de concesión vive en el caso de uso. Los nombres `Product`, `Order`, `PaymentAttempt`, `Entitlement` y `EmailDelivery` forman el lenguaje compartido entre documentación, código y tests, pero cada contexto tiene su propio modelo y sus propios objetos de valor (por ejemplo, `Money`).

Las entidades JPA y los DTO HTTP/Kafka se transforman dentro de los adaptadores; nunca se comparten como clases Java entre servicios. Al definir los contratos HTTP y de eventos, versionarlos en `docs/contracts/`. `OrderPaid` vivirá en `domain/events/`; el adaptador de salida lo transformará en el mensaje `orders.events.v1`. La transacción de persistencia se abre después de obtener datos externos, y las llamadas a Mercado Pago quedan fuera de ella. No crear un módulo Java `common` para compartir entidades o repositorios.

## Datos y configuración

Cada servicio de negocio es dueño de sus migraciones Flyway en `src/main/resources/db/migration/V1__*.sql`: Catalog usa `catalog_db`, Order usa `orders_db` y Notification usa `notifications_db`. Keycloak administra `keycloak_db`. `infra/postgres/init/` crea únicamente las cuatro bases, y solo se ejecuta cuando el volumen de PostgreSQL está vacío. Hibernate valida el esquema (`ddl-auto=validate`); las columnas de moneda y hash son `VARCHAR` y las `JSONB` se mapean con `@JdbcTypeCode(SqlTypes.JSON)`.

`script.sql` conserva el diseño original como referencia, pero las migraciones V1 ya están en los tres módulos de negocio y son la fuente ejecutable del esquema. El script incluye `CREATE DATABASE` y `\connect`, que no pertenecen a Flyway. Comparar cualquier cambio futuro con las migraciones y retirar `script.sql` cuando deje de aportar valor; **no** ejecutarlo contra las mismas tablas.

Config Server y Eureka tienen configuración mínima local para iniciar sin depender uno del otro. `config-repo/` guarda rutas y ajustes compartidos con el backend `native`; contraseñas, tokens y secretos (Access Token de prueba de la aplicación de Mercado Pago, secreto del webhook, secreto del cliente de servicio de Keycloak) llegan por variables de entorno de Compose. Los archivos subidos viven en un volumen local ignorado por Git. [Backend native de Config Server](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html).

## Pruebas y entrega local

Los tests Java se ubican junto a su servicio (`*Test`, `*IT`). Ya existen pruebas de seguridad para Gateway, Catalog y Order; ArchUnit está declarado y sus reglas se añadirán al implementar el dominio. Las pruebas futuras de integración usarán Testcontainers (PostgreSQL, Kafka, Redis) y WireMock para Mercado Pago; las pruebas web aún están pendientes. Los escenarios obligatorios están en [architecture.md](architecture.md). `ci.yml` ejecuta `mvn verify`, `npm run lint`, `npm run build` y construye imágenes en el runner Linux/WSL para ramas confiables. `deploy-local.yml` se dispara manualmente en el mismo runner. Completar `README.md` con la demo de Mercado Pago, Mailpit y el túnel HTTPS cuando esas funciones existan.
