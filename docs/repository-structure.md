# Estructura del repositorio

Este es el árbol **objetivo** del monorepositorio. Hoy solo existen `AGENTS.md`, `script.sql` y los documentos en `docs/`; las carpetas de aplicación se crearán al implementar cada servicio.

```text
D:/portafolio/
├── pom.xml                         # Maven padre: versiones, plugins y seis módulos
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper
├── compose.yaml                    # Stack local y redes internas
├── .env.example                    # Nombres de variables, sin credenciales
├── .gitignore
├── README.md                       # Instalación, demo, cuentas de prueba y arranque
├── AGENTS.md
├── script.sql                      # Referencia SQL temporal; se retira al crear las migraciones Flyway
├── catalog-service/                # Productos, archivos y caché Redis
├── order-service/                  # Pedidos, Mercado Pago, derechos, reconciliación y outbox
├── notification-service/           # Kafka, inbox y correo SMTP
├── gateway-service/                # Rutas públicas, JWT y bloqueo de rutas internas
├── config-server/                  # Config Server con backend native
├── discovery-server/               # Eureka Server
├── web/                            # React + Vite; proyecto npm independiente
│   ├── src/app/                    # Router, Keycloak y composición de páginas
│   ├── src/features/               # catalog, checkout, orders, library, admin
│   ├── src/shared/                 # Componentes y cliente HTTP reutilizables
│   └── public/                     # Recursos estáticos pequeños
├── config-repo/                    # application.yml y <service-name>.yml sin secretos
├── infra/
│   ├── postgres/init/              # Solo CREATE DATABASE para el primer arranque
│   ├── keycloak/                   # Realm: roles, clientes (incluida la cuenta de servicio) y flujos de cuenta
│   ├── kafka/                      # Aprovisionamiento de orders.events.v1 y orders.events.v1.DLT
│   └── assets/demo/                # Archivos digitales pequeños de muestra
├── docs/
│   ├── architecture.md
│   ├── repository-structure.md
│   ├── user-stories.md              # Backlog del MVP con criterios de aceptación
│   ├── adr/                        # ADR 001 de descargas y futuras decisiones
│   └── contracts/                  # OpenAPI y esquema JSON de eventos Kafka
└── .github/workflows/
    ├── ci.yml                      # Maven verify, pruebas web e imágenes
    └── deploy-local.yml            # Despliegue manual con Compose
```

## Convención de los módulos Java

Los seis módulos del `pom.xml` padre son aplicaciones Spring Boot independientes; `web/` queda fuera del reactor Maven. Cada módulo Java tendrá `pom.xml`, `src/main/java`, `src/main/resources/application.yml` y `src/test/java`. El paquete base de los servicios de negocio será `com.tuportafolio.<servicio>`; por ejemplo, `com.tuportafolio.orders` (sustituir `tuportafolio` por el dominio o usuario real antes de crear paquetes). La clase `*Application` estará en ese paquete raíz para incluir sus subpaquetes en el escaneo de Spring. [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [estructura de Spring Boot](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html).

Catalog, Order y Notification Service usan **arquitectura hexagonal + DDD pragmático**. Cada servicio de negocio es un contexto delimitado: Catálogo, Pedidos o Notificaciones. Dentro de cada módulo se usan exactamente las carpetas superiores `domain/`, `application/` e `infrastructure/`, siguiendo la plantilla acordada. `domain` no importa Spring/JPA; `application` no importa JPA ni clases de `infrastructure` (puede usar `@Service` y `@Transactional` de Spring); `infrastructure/adapters` implementa los puertos. Crear un puerto solo cuando existe una dependencia externa, no para cada clase. ArchUnit se añade como dependencia desde HT-01 y verifica estas reglas cuando cada servicio tenga clases de dominio y aplicación. [DDD Reference](https://www.domainlanguage.com/ddd/reference/), [puertos y adaptadores](https://alistair.cockburn.us/hexagonal-architecture).

## Carpetas internas de los servicios

`order-service` sigue la plantilla propuesta; la ruta bajo `java/` equivale al paquete Java:

```text
order-service/
├── pom.xml
├── src/main/java/com/tuportafolio/orders/
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
│   ├── application.yml               # Solo bootstrap y valores locales mínimos
│   └── db/migration/V1__create_orders.sql
└── src/test/java/com/tuportafolio/orders/
    ├── architecture/                  # ArchUnit: reglas de dependencia
    ├── domain/                        # Reglas y transiciones de estado (*Test)
    ├── application/                   # Casos de uso con puertos simulados
    └── infrastructure/                # Integración real con Testcontainers y WireMock (*IT)
```

`catalog-service` y `notification-service` repiten las mismas tres carpetas superiores, con adaptadores propios:

```text
catalog-service/src/main/java/com/tuportafolio/catalog/
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

notification-service/src/main/java/com/tuportafolio/notifications/
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

Cada servicio conserva sus propias migraciones y tests. `gateway-service`, `config-server` y `discovery-server` tienen solo su clase `*Application`, `config/`, `src/main/resources/application.yml` y tests de configuración; no se crean carpetas de dominio vacías. En `web/src/features/<función>/` colocar `pages/`, `components/` y `api/` cuando se necesiten; `web/src/app/` contiene router y autenticación, y `web/src/shared/` solo componentes y utilidades realmente reutilizados.

`Order` gobierna sus líneas, intentos de pago y cambios de estado. `Entitlement` es un modelo de dominio sencillo y la fuente para autorizar descargas; se persiste mediante `EntitlementRepositoryPort` y la regla de concesión vive en el caso de uso. Los nombres `Product`, `Order`, `PaymentAttempt`, `Entitlement` y `EmailDelivery` forman el lenguaje compartido entre documentación, código y tests, pero cada contexto tiene su propio modelo y sus propios objetos de valor (por ejemplo, `Money`).

Las entidades JPA y los DTO HTTP/Kafka se transforman dentro de los adaptadores; nunca se comparten como clases Java entre servicios. Los contratos HTTP y de eventos se versionan en `docs/contracts/`. `OrderPaid` vive en `domain/events/`; el adaptador de salida lo transforma en el mensaje `orders.events.v1`. La transacción de persistencia se abre después de obtener datos externos, y las llamadas a Mercado Pago quedan fuera de ella. No crear un módulo Java `common` para compartir entidades o repositorios.

## Datos y configuración

Cada servicio de negocio es dueño de sus migraciones Flyway en `src/main/resources/db/migration/V1__*.sql`: Catalog usa `catalog_db`, Order usa `orders_db` y Notification usa `notifications_db`. Keycloak administra `keycloak_db`. `infra/postgres/init/` crea únicamente las cuatro bases, y solo se ejecuta cuando el volumen de PostgreSQL está vacío. Hibernate valida el esquema (`ddl-auto=validate`); las columnas de moneda y hash son `VARCHAR` y las `JSONB` se mapean con `@JdbcTypeCode(SqlTypes.JSON)`.

El `script.sql` actual refleja los estados y restricciones de [architecture.md](architecture.md), pero es una referencia temporal: incluye `CREATE DATABASE` y `\connect`, que no son migraciones Flyway. Al crear las migraciones V1, trasladar cada bloque a su servicio y después eliminar `script.sql`, para que Flyway sea la única fuente de verdad del esquema. Mientras tanto **no** se monta junto con Flyway ni se ejecuta contra las mismas tablas.

Config Server y Eureka tienen configuración mínima local para iniciar sin depender uno del otro. `config-repo/` guarda rutas y ajustes compartidos con el backend `native`; contraseñas, tokens y secretos (Access Token de prueba de la aplicación de Mercado Pago, secreto del webhook, secreto del cliente de servicio de Keycloak) llegan por variables de entorno de Compose. Los archivos subidos viven en un volumen local ignorado por Git. [Backend native de Config Server](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html).

## Pruebas y entrega local

Los tests Java se ubican junto a su servicio (`*Test`, `*IT`); las pruebas de integración usan Testcontainers (PostgreSQL, Kafka, Redis) y WireMock para Mercado Pago. Cada módulo de negocio incluye ArchUnit. Los tests de interfaz quedan en `web/src/**/*.test.tsx`. Los escenarios obligatorios de pagos, webhook, mensajería, descargas y purgas están listados en [architecture.md](architecture.md). `ci.yml` ejecuta `mvn verify`, pruebas y build de `web/`, y construye imágenes en el runner local para ramas confiables. `deploy-local.yml` se dispara manualmente en el mismo runner y levanta `compose.yaml`; los cambios de pull requests externos no se ejecutan allí. Documentar en `README.md` cómo importar Keycloak, iniciar Mailpit, obtener las credenciales de prueba de la aplicación de Mercado Pago, realizar una compra con tarjeta de prueba y abrir el túnel HTTPS temporal para webhooks.
