# Historias de usuario y tareas de implementación

Este backlog define el **MVP local** de la tienda de productos digitales. Seguir las etapas en orden; dentro de cada etapa, las historias indican sus dependencias. La arquitectura y las reglas de datos se detallan en [architecture.md](architecture.md), y las carpetas objetivo en [repository-structure.md](repository-structure.md). Los pagos usan exclusivamente las credenciales de prueba de Mercado Pago.

**Terminado** significa que el comportamiento funciona desde la web o la API, tiene pruebas para sus reglas y fallos relevantes, no contiene secretos en Git y se puede ejecutar con las instrucciones del `README.md`. Los nombres `HU` identifican funciones visibles; `HT`, trabajo técnico necesario para entregarlas.

## Etapa 1: base ejecutable

### HT-01 — Crear el monorepositorio Java y web

**Implementar:** `pom.xml` padre con Java 21 y los seis módulos indicados en la estructura; Maven Wrapper; proyecto React + Vite + TypeScript en `web/`; paquetes `domain`, `application` e `infrastructure` en los tres servicios de negocio. Configurar JUnit Jupiter 6 y añadir ArchUnit como dependencia de test en Catalog, Order y Notification.

**Aceptación:** `mvn verify` y `npm run build` terminan correctamente con el scaffolding. Las pruebas de reglas ArchUnit se incorporan en HU-03, HU-05 y HU-13, cuando cada servicio ya tenga clases que verificar.

### HT-02 — Migrar el esquema a Flyway

**Depende de:** HT-01.

**Implementar:** separar `script.sql` en migraciones V1 de Catalog, Order y Notification; crear solo las cuatro bases en `infra/postgres/init/`; dejar a Keycloak administrar sus tablas; activar `ddl-auto=validate` y retirar `script.sql` cuando Flyway sea la fuente única.

**Aceptación:** las tres aplicaciones arrancan contra PostgreSQL vacío, crean sus tablas una sola vez y vuelven a arrancar sin cambios de esquema. Se conservan `REVIEW_REQUIRED`, `DEAD`, los índices parciales y las restricciones `UNIQUE` del diseño.

### HT-03 — Levantar la infraestructura local y Spring Cloud

**Depende de:** HT-01.

**Implementar:** `compose.yaml` con PostgreSQL, Redis, Kafka, Mailpit, Keycloak, Config Server, Eureka, Gateway y los servicios; volúmenes para bases y archivos; variables en `.env.example`; configuración sin secretos en `config-repo/`; aprovisionar `orders.events.v1` y `orders.events.v1.DLT` en `infra/kafka/`.

**Aceptación:** `docker compose up --build -d` inicia el conjunto; Gateway descubre Catalog y Order con Eureka; el tópico principal retiene 7 días y el DLT 30; ningún secreto figura en los archivos versionados.

### HT-04 — Configurar CI y despliegue local

**Depende de:** HT-01 y HT-03.

**Implementar:** workflows `ci.yml` y `deploy-local.yml` para runner local de GitHub Actions. CI ejecuta `mvn verify`, pruebas y build de la web y construcción de imágenes; el despliegue manual ejecuta Compose. Documentar instalación del runner y excluir PR externos de ese equipo.

**Aceptación:** un cambio en una rama confiable ejecuta CI; el workflow manual despliega en la misma máquina; un fallo de pruebas detiene la construcción posterior.

## Etapa 2: identidad y catálogo

### HU-01 — Registrar y recuperar la cuenta

**Como** visitante, **quiero** crear una cuenta, confirmar mi correo y recuperar mi contraseña **para** comprar con seguridad.

**Depende de:** HT-03.

**Implementar:** realm de Keycloak en `infra/keycloak/` con registro, correo único, verificación obligatoria, «Olvidé mi contraseña», rol `CUSTOMER` por defecto y SMTP de Mailpit. `ADMIN` se asigna solo manualmente.

**Aceptación:** los enlaces llegan a Mailpit; una cuenta sin correo verificado no puede crear pedidos; el registro público nunca obtiene `ADMIN`.

### HU-02 — Iniciar sesión y proteger rutas

**Como** comprador o administrador, **quiero** iniciar y cerrar sesión **para** acceder solo a mis funciones.

**Depende de:** HU-01 y HT-03.

**Implementar:** Authorization Code con PKCE mediante `keycloak-js`; tokens solo en memoria y renovación antes de llamar a la API. Gateway y servicios validan JWT; rutas administrativas requieren `ADMIN`. Crear un cliente de servicio con rol dedicado para Order → Catalog y negar `/api/internal/**` en Gateway.

**Aceptación:** una ruta privada sin JWT devuelve 401; un comprador recibe 403 en administración; el endpoint interno rechaza el JWT de comprador y acepta el de servicio. Los tokens no aparecen en `localStorage`.

### HU-03 — Explorar el catálogo

**Como** visitante, **quiero** listar, buscar por título y abrir productos publicados **para** elegir qué comprar.

**Depende de:** HT-02 y HU-02.

**Implementar:** `GET /api/products` y `GET /api/products/{id}` en Catalog conforme a [api-conventions.md](api-conventions.md); usar controllers delgados, inyección por constructor, DTOs y mappers manuales en adaptadores. Crear páginas React de listado, búsqueda, detalle y carrito temporal en memoria del navegador. Mostrar precio y moneda PEN. Al crear `Product`, añadir un test ArchUnit que impida dependencias de `domain` hacia Spring/JPA y de `application` hacia `infrastructure`.

**Aceptación:** solo aparecen productos `PUBLISHED`; la búsqueda por título y la paginación siguen el contrato; un ID inexistente o no publicado devuelve 404; el carrito no crea registros en la base y se puede usar antes de iniciar sesión. El test ArchUnit de Catalog pasa con `mvn verify` y falla ante una dependencia prohibida.

### HU-04 — Administrar productos digitales

**Como** administrador, **quiero** crear borradores, cargar archivos y publicar o archivar productos **para** mantener el catálogo sin editar código.

**Depende de:** HU-02 y HT-02.

**Implementar:** API bajo `/api/admin/products` y pantallas de administración; usar DTOs de entrada con `@Valid` y errores `ProblemDetail` según [api-conventions.md](api-conventions.md). Validar SKU, título, precio positivo y archivo completo antes de publicar. Guardar el archivo en volumen local con clave UUID, tamaño, tipo y SHA-256; comprobar que la ruta queda dentro del directorio permitido.

**Aceptación:** un borrador sin archivo se guarda, pero no se publica; SKU repetido y precio inválido se rechazan; el nombre enviado por el administrador no determina la ruta de almacenamiento; archivar retira el producto del catálogo público.

### HT-05 — Añadir caché Redis al catálogo

**Depende de:** HU-03 y HU-04.

**Implementar:** cachear lecturas públicas de productos, invalidar al editar, publicar o archivar, y usar PostgreSQL cuando Redis falle. Configurar Circuit Breaker `catalogCache`.

**Aceptación:** después de una edición se ve el dato nuevo; con Redis detenido siguen funcionando listado y detalle; la consulta protegida de precio que usa Order lee la fuente vigente y no toma un precio obsoleto de la caché pública.

## Etapa 3: pedidos y pago de prueba

### HU-05 — Crear un pedido idempotente

**Como** comprador verificado, **quiero** convertir mi carrito en un pedido **para** conservar el precio que pagaré.

**Depende de:** HU-02, HU-03 y HT-02.

**Implementar:** `POST /api/orders` con `Idempotency-Key`; consultar productos publicados por `RestClient`, guardar `orders`, `order_items` e `idempotency_requests` en una transacción. Capturar título, SKU, precio y moneda; rechazar productos ya poseídos. Configurar timeouts y Circuit Breaker `catalog`. Al crear `Order`, añadir el test ArchUnit de Order con las mismas reglas de dependencia.

**Aceptación:** misma clave y mismo cuerpo devuelven el mismo pedido; misma clave y otro cuerpo producen conflicto; Catalog caído o lento produce 503 sin pedido parcial; el precio posterior del catálogo no modifica el pedido. El test ArchUnit de Order detecta dependencias prohibidas.

### HU-06 — Consultar mis pedidos

**Como** comprador, **quiero** ver el estado y detalle de mis pedidos **para** saber si puedo pagar o descargar.

**Depende de:** HU-05.

**Implementar:** `GET /api/orders/me`, `GET /api/orders/{id}` y pantallas de historial y detalle; `GET /api/admin/orders` para revisión administrativa. Filtrar por `buyer_sub` del JWT, no por un parámetro aportado por el navegador.

**Aceptación:** un comprador no puede leer pedidos de otro; se muestran importes capturados, estado de pago y motivo de revisión cuando corresponda.

### HU-07 — Abrir Checkout Pro

**Como** comprador, **quiero** abrir el checkout de prueba **para** completar el pago fuera de la tienda.

**Depende de:** HU-05.

**Implementar:** antes del adaptador, comprobar con las credenciales de prueba de la aplicación `POST /v1/orders`, `GET /v1/orders` y el valor de `live_mode`, por la contradicción documental descrita en la arquitectura. Crear `POST /api/orders/{id}/checkout`; permitir `CREATED`, `PENDING_PAYMENT` y `PAYMENT_FAILED` sin intento activo y volver a comprobar propiedad de productos. Crear `payment_attempts` en `CREATING`, llamar a `POST /v1/orders` fuera de la transacción con `external_reference = payment_attempts.id` y un UUID persistido como `X-Idempotency-Key`; guardar `provider_order_id` y `checkout_url`, pasar a `PENDING` y redirigir desde React.

**Aceptación:** dos solicitudes simultáneas no crean dos intentos activos; un timeout deja `CREATING` para reconciliación; nunca se genera otra clave para reintentar el mismo intento; no se guarda el Access Token en la web.

### HU-08 — Verificar y registrar webhooks

**Como** sistema, **quiero** autenticar las notificaciones de Mercado Pago **para** no aceptar confirmaciones falsas.

**Depende de:** HU-07.

**Implementar:** `POST /api/webhooks/mercadopago` sin JWT, pero con HMAC válido. Formar `id:…;request-id:…;ts:…;`; convertir `data.id` de la query a minúsculas solo para la firma y omitir campos ausentes. Guardar `webhook_receipts` por `provider_notification_id`; consultar el ID original en Mercado Pago.

**Aceptación:** firma inválida o falta de ID de orden no modifica pedidos; firma válida con ID alfanumérico en mayúsculas pasa; la falta de `x-request-id` usa el manifest reducido; un recibo `PROCESSED` repetido no repite efectos.

### HU-09 — Confirmar el pago y conceder acceso

**Como** comprador, **quiero** obtener mi producto después del pago aprobado **para** descargarlo.

**Depende de:** HU-08 y HT-02.

**Implementar:** consultar la orden del proveedor y verificar `external_reference`, importe, PEN, estado `processed/accredited` y `live_mode` esperado. En una transacción con bloqueo de fila de `orders`, pasar a `PAID`, crear `entitlements` con `ON CONFLICT DO NOTHING` y una fila `ORDER_PAID` en `outbox_events`. Inconsistencias pasan a `REVIEW_REQUIRED`.

**Aceptación:** dos webhooks concurrentes producen un solo `PAID`, un evento y un derecho por producto; monto o moneda distintos nunca conceden acceso; un pago tardío válido puede confirmar un pedido aún no pagado; un segundo pago del mismo pedido queda señalado `DUPLICATE_PAYMENT` sin otro correo. Si el comprador ya poseía el producto por otro pedido, el nuevo pedido pagado sigue `PAID`, no duplica el derecho y el intento queda con `review_reason = DUPLICATE_PURCHASE`.

### HU-10 — Reintentar después de un intento fallido o expirado

**Como** comprador, **quiero** volver a pagar si el intento anterior falló o expiró **para** completar mi compra.

**Depende de:** HU-07 y HU-09.

**Implementar:** traducir estados rechazados o expirados del proveedor a `REJECTED` o `EXPIRED`; actualizar el pedido a `PAYMENT_FAILED` cuando corresponda; permitir nuevo checkout cuando no haya intento activo, incluso si el pedido sigue `PENDING_PAYMENT` tras una expiración.

**Aceptación:** un intento `PENDING` o `REVIEW_REQUIRED` bloquea otro checkout; `REJECTED`, `EXPIRED` o `ERROR` permiten uno nuevo; `PAID` nunca permite otro checkout.

### HU-11 — Reconciliar pagos inciertos

**Como** sistema, **quiero** revisar intentos sin respuesta concluyente **para** recuperar su estado sin crear otra orden por error.

**Depende de:** HU-07 y HU-08.

**Implementar:** scheduler que busca por `external_reference` y ventana de fechas, sin limitarse a un único estado ni a la primera página; si no encuentra una orden `CREATING`, reintenta con la misma clave y el mismo cuerpo. Respetar `Retry-After`; tras 24 h sin resultado pasar `CREATING` a `REVIEW_REQUIRED`. Consultar intentos `PENDING` existentes hasta estado terminal o expiración.

**Aceptación:** un intento incierto recupera la orden encontrada; tras 24 h sigue bloqueando checkout y muestra «pago en revisión»; una búsqueda vacía por sí sola nunca lo pasa a `ERROR`.

### HU-12 — Resolver un intento en revisión

**Como** administrador, **quiero** consultar y cerrar intentos inciertos **para** permitir un nuevo checkout cuando haya revisado el riesgo.

**Depende de:** HU-11 y HU-06.

**Implementar:** `POST /api/admin/payment-attempts/{id}/reconcile` para consultar de nuevo al proveedor; `POST /api/admin/payment-attempts/{id}/resolve` para `ERROR` con `reason` y `acknowledgeLatePaymentRisk=true`. Registrar `resolved_by` y `resolved_at`; mostrar en la web administrativa la historia del intento.

**Aceptación:** un comprador recibe 403; falta de motivo o confirmación impide liberar el intento; una orden aprobada y consistente se procesa como pago; una discrepancia de importe, moneda o referencia sigue en revisión.

## Etapa 4: correo y descargas

### HT-06 — Publicar el outbox en Kafka

**Depende de:** HU-09 y HT-03.

**Implementar:** relay programado en Order Service que reclama filas con `FOR UPDATE SKIP LOCKED`, usa `lease_until`, publica `ORDER_PAID` en `orders.events.v1` con `key = order_id` y `eventId = outbox_events.id`, y marca `PUBLISHED` tras la confirmación. Reintentar fallos; tras el límite configurado, marcar `DEAD`.

**Aceptación:** caída después de publicar puede repetir el mismo `eventId`, pero no perderlo; dos trabajadores no reclaman simultáneamente la misma fila; una fila `DEAD` queda visible para revisión.

### HU-13 — Recibir correo de compra

**Como** comprador, **quiero** recibir un correo de confirmación **para** conservar un comprobante de mi compra de prueba.

**Depende de:** HT-06.

**Implementar:** Notification Service consume `orders.events.v1` con `ErrorHandlingDeserializer` y `AckMode.RECORD`; inserta `email_deliveries` con `ON CONFLICT DO NOTHING`; scheduler envía por SMTP a Mailpit, reintenta y marca `DEAD` al agotar intentos. Configurar DLT para mensajes irrecuperables. Al crear `EmailDelivery`, añadir el test ArchUnit de Notification con las mismas reglas de dependencia.

**Aceptación:** el comprador ve un correo por `ORDER_PAID` en Mailpit; un mensaje duplicado no crea otra entrega ni va al DLT; un mensaje ilegible llega al DLT; una falla SMTP se reintenta. El test ArchUnit de Notification detecta dependencias prohibidas. Documentar que una caída tras enviar puede repetir el correo.

### HU-14 — Ver biblioteca y descargar archivos

**Como** comprador, **quiero** ver y descargar los productos adquiridos **para** usarlos después del pago.

**Depende de:** HU-04, HU-09 y HU-02.

**Implementar:** `GET /api/library` y `GET /api/library/{productId}/download`; Order valida `buyer_sub` y `entitlements`, pide el archivo al endpoint interno de Catalog con credencial de servicio y lo transmite en streaming por Gateway. Sanear `Content-Disposition`, limitar tamaño y concurrencia.

**Aceptación:** un comprador sin derecho no descarga; otro comprador no usa la biblioteca ajena; el archivo grande se entrega con heap limitado; `/api/internal/**` no es accesible por Gateway.

## Etapa 5: operación y cierre del MVP

### HT-07 — Purgar datos temporales

**Depende de:** HT-02, HT-06 y HU-13.

**Implementar:** schedulers por lotes y transacciones independientes: `idempotency_requests` vencidos a 30 días, `webhook_receipts` procesados a 30, outbox publicado a 30 y correos `SENT` a 60. Usar `purge_expired_idempotency_requests(batchSize)` sin transacción envolvente de todo el bucle. Limitar republicación manual del outbox a 30 días.

**Aceptación:** las purgas no borran filas pendientes, fallidas, `DEAD` o en revisión; la ventana de deduplicación de 60 días supera la retención Kafka de 7 y la republicación de 30.

### HT-08 — Añadir trazabilidad y salud

**Depende de:** HT-03 y HT-06.

**Implementar:** seguir [observability.md](observability.md): Micrometer Tracing con W3C Trace Context en HTTP y Kafka; conservar `traceparent` en outbox y correo; logs con `traceId`; endpoints Actuator de salud accesibles solo internamente. Configurar timeouts de `RestClient` y umbral de llamadas lentas del Circuit Breaker.

**Aceptación:** la llamada HTTP conserva `traceId` entre Gateway y los servicios; el webhook inicia otra traza y la conserva por outbox, Kafka y Notification Service, correlacionada con el pedido mediante `orderId`; Catalog lento devuelve 503 sin bloquear indefinidamente Order Service; Gateway no enruta el Actuator de otros servicios.

### HT-09 — Documentar y ejecutar la demo completa

**Depende de:** todas las historias anteriores.

**Implementar:** `README.md` con requisitos, variables de entorno, arranque, importación de Keycloak, datos de ejemplo, credenciales de prueba de la aplicación de Mercado Pago, tarjeta de prueba, túnel HTTPS, URLs y comandos de pruebas. Añadir contratos HTTP y evento `ORDER_PAID` versionado en `docs/contracts/`.

**Aceptación:** desde una instalación limpia se registra un usuario, confirma correo, compra en el entorno de prueba, recibe el webhook y el correo, ve `PAID` y descarga el archivo; `mvn verify` y `npm run build` pasan en el runner local.

## Fuera del MVP

Pagos reales, reembolsos automáticos, cancelación de pedidos, inventario físico, envíos, cupones, reseñas, favoritos, acceso con Google y despliegue en la nube. Los pedidos `CREATED` abandonados permanecen; una compra repetida ya pagada queda para revisión manual sin reembolso automático.
