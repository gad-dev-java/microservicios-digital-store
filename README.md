# Digital Store

Tienda local de productos digitales con React, Java 21, Spring Boot, Keycloak, PostgreSQL, Redis, Kafka y Mailpit. Catalog publica archivos; Order crea pedidos y confirma pagos de prueba de Mercado Pago; Notification envía el correo tras consumir `ORDER_PAID`. La entrada HTTP pública pasa por Gateway. La arquitectura y las historias están en [`docs/architecture.md`](docs/architecture.md) y [`docs/user-stories.md`](docs/user-stories.md); los contratos de la demo están en [`docs/contracts/`](docs/contracts/).

## Alcance del portafolio

La entrega del portafolio es la demo **local y reproducible**: Compose o Minikube, compra de prueba de extremo a extremo, CI, pruebas y observabilidad. Minikube y Argo CD permiten mostrar Kubernetes y GitOps sin una suscripción cloud. No se necesita Azure para arrancar, probar o evaluar el proyecto. [HT-17 y HT-18](docs/user-stories.md#ampliación-futura-opcional-azure) describen una posible ampliación futura en Azure; no bloquean la demo ni el CI y todavía no están implementadas.

## Requisitos y configuración inicial

- Docker Desktop con Compose e integración WSL2, Java 21, Node.js 24 y npm. Usa el Maven Wrapper de la raíz; para el webhook instala `cloudflared` en el equipo que ejecuta Docker.
- Copia `.env.example` a `.env` en la raíz. Asigna contraseñas locales y un `ORDER_SERVICE_CLIENT_SECRET` propio. Nunca subas `.env`, tokens ni el secreto de firma al repositorio.
- Crea una aplicación **Checkout Pro con Orders** en [Mercado Pago Developers](https://www.mercadopago.com.pe/developers/es/docs/checkout-pro-orders/overview), selecciona **Prueba** y copia su Access Token de prueba a `MERCADOPAGO_ACCESS_TOKEN`. Copia el ID numérico de la aplicación a `MERCADOPAGO_APPLICATION_ID`; conserva `MERCADOPAGO_EXPECTED_LIVE_MODE=false`. El token de producción y el modo live quedan fuera de esta demo.
- Configura el webhook de prueba más adelante y copia su **firma secreta de prueba** a `MERCADOPAGO_WEBHOOK_SECRET`. Para arrancar antes de tener la firma, deja un valor temporal no vacío y sustitúyelo antes de pagar.

En PowerShell, desde la raíz:

```powershell
Copy-Item .env.example .env
notepad .env
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

La primera vez, Keycloak importa `infra/keycloak/digital-store-realm.json`: crea el realm `digital-store`, los roles `CUSTOMER` y `ADMIN`, el cliente web `digital-store-web`, y `order-service-client` con el secreto de `.env`. El correo saliente se dirige a Mailpit. En una instalación existente, `--import-realm` **omite** el realm ya creado: comprueba en la consola de Keycloak que el secreto del cliente y los roles coincidan con `.env`; cambiar el JSON por sí solo no actualiza ese realm. [Keycloak documenta esta regla](https://www.keycloak.org/server/importExport).

No ejecutes `script.sql` sobre las bases: las tablas se crean con las migraciones Flyway de cada servicio. `docker compose down` detiene el entorno y conserva datos; `down -v` borra los volúmenes, incluidos pedidos y archivos.

## URLs locales y comprobaciones

| Componente                  | URL                     |
| --------------------------- | ----------------------- |
| Web de desarrollo           | `http://localhost:5173` |
| Gateway API                 | `http://localhost:8080` |
| Keycloak, consola y cuentas | `http://localhost:8081` |
| Mailpit, correo de prueba   | `http://localhost:8025` |
| Eureka                      | `http://localhost:8761` |
| Config Server               | `http://localhost:8888` |

Inicia la web en otra terminal: `cd web`, `npm ci`, `npm run dev`. Vite envía `/api` al Gateway. Comprueba el catálogo con `Invoke-RestMethod http://localhost:8080/api/products`. El health del Gateway se consulta **dentro del contenedor**, en el puerto de gestión 8082: `docker compose exec gateway-service curl -fsS http://localhost:8082/actuator/health`. `docker compose ps` debe mostrar sanos los servicios persistentes; `kafka-init` debe terminar con código 0.

## Contrato HTTP local

El contrato versionado está en [`docs/contracts/http-api-v1.yaml`](docs/contracts/http-api-v1.yaml). Para explorarlo con Swagger UI:

```powershell
docker compose up -d swagger-ui
```

Abre `http://localhost:8083`. El visor es de solo lectura y no ejecuta peticiones. Se inicia únicamente al solicitar el servicio `swagger-ui`; para detenerlo usa `docker compose stop swagger-ui`.

Valida el contrato desde la raíz con:

```powershell
npx --yes @redocly/cli@2.79.0 lint docs/contracts/http-api-v1.yaml --extends=spec
```

## Observabilidad local

Define `GRAFANA_ADMIN_PASSWORD` en `.env` y levanta el perfil de observabilidad desde la raíz:

```powershell
docker compose -f compose.yaml -f compose.observability.yaml --profile observability up --build -d
docker compose -f compose.yaml -f compose.observability.yaml --profile observability ps
```

Abre Grafana en `http://localhost:3000` (usuario `admin`) y Prometheus en `http://localhost:9090/targets`; los cuatro servicios Java deben estar `UP`. El dashboard versionado está en `infra/observability/grafana/dashboards/digital-store.json`. En Grafana, usa Tempo con `{ span."order.id" = "UUID_DEL_PEDIDO" }` y Loki con `{service_name="order-service"} |= "UUID_DEL_PEDIDO"` para encontrar una compra nueva. El webhook genera otra traza con el mismo atributo `order.id`. Los Actuator y los puertos de Tempo, Loki y Collector solo son accesibles dentro de Compose. `docker compose -f compose.yaml -f compose.observability.yaml --profile observability down` detiene toda la pila sin borrar datos. Usa los mismos dos archivos al bajarla: `docker compose down` con solo `compose.yaml` puede dejar Prometheus activo, mientras que los servicios Java vuelven a arrancar sin el endpoint de métricas. Para volver a la tienda sin observabilidad, arráncala después con `docker compose -f compose.yaml up -d`.

## Pruebas de rendimiento

HT-14 usa k6 en Docker para medir catálogo, creación de pedidos y descargas. La guía de ejecución, los umbrales iniciales, el entorno aislado y la comparación con Redis están en [`infra/perf/README.md`](infra/perf/README.md). El CI ejecuta únicamente un smoke de catálogo y guarda su reporte JSON como artefacto.

## Kubernetes local

HT-15 ejecuta la tienda completa en Minikube con Kustomize. La preparación, los manifiestos, el acceso local y la prueba de persistencia están en [`infra/k8s/README.md`](infra/k8s/README.md). Este entorno usa datos propios; no copia los volúmenes de Compose.

## Preparar un producto y un comprador

1. En Keycloak (`http://localhost:8081`), entra a la consola con `KEYCLOAK_ADMIN` y `KEYCLOAK_ADMIN_PASSWORD`; selecciona el realm `digital-store`. Crea un usuario administrador, marca **Email verified**, asigna el rol de realm `ADMIN` y establece una contraseña no temporal. La cuenta bootstrap de `master` no es el administrador de la tienda.
2. En PowerShell, crea un archivo de ejemplo con `'Contenido de la guía Java 21' | Set-Content -LiteralPath (Join-Path $env:TEMP 'guia-java-21.txt') -Encoding utf8`. En la web, inicia sesión como ese administrador. En la pestaña **Productos**, crea `SKU=guia-java-21`, título `Guía práctica de Java 21`, descripción `Archivo de ejemplo` y precio `12.00` PEN. Adjunta el archivo `guia-java-21.txt` de la carpeta temporal y publícalo. Confirma que aparece en el catálogo público. El archivo queda en el volumen `product-files`.
3. Cierra esa sesión. En **Iniciar sesión**, usa **Registrarse** para crear un comprador con otro correo. Abre Mailpit, sigue el enlace de verificación y vuelve a iniciar sesión. El token debe tener `email_verified=true` y el rol `CUSTOMER`; el cliente de servicio conserva únicamente `CATALOG_INTERNAL_READ`.

## Túnel y webhook de Mercado Pago

Con Docker y Gateway activos, abre otra terminal y mantenla abierta:

```powershell
cloudflared tunnel --url http://localhost:8080
```

Copia la URL HTTPS `https://...trycloudflare.com`. En la aplicación de Mercado Pago, selecciona **Prueba → Configuración de webhooks**, pega `https://...trycloudflare.com/api/webhooks/mercadopago`, marca **Order (Mercado Pago)** y guarda. Copia la firma secreta generada a `.env` como `MERCADOPAGO_WEBHOOK_SECRET` y recrea Order para que lea el valor nuevo:

```powershell
docker compose up -d --force-recreate order-service
docker compose ps
```

Una URL de túnel rápido cambia al reiniciarlo; actualízala en Mercado Pago antes de repetir la prueba. El túnel solo recibe el webhook; la web y Keycloak siguen en `localhost`. Mercado Pago exige una URL HTTPS para notificaciones de prueba y envía el evento `Order (Mercado Pago)` por POST. [Configuración oficial de notificaciones](https://www.mercadopago.com.pe/developers/es/docs/checkout-pro-orders/notifications).

## Recorrido de aceptación de la demo

1. Con el comprador verificado, añade el producto publicado y crea un pedido. En **Mis pedidos** comprueba el estado inicial `CREATED` y el precio capturado.
2. Pulsa **Pagar con Mercado Pago**. Se abre el `checkout_url` de prueba. Para Checkout Pro con Orders, haz el pago en una ventana de incógnito sin sesión de Mercado Pago; usa la tarjeta de prueba mostrada por tu aplicación o la [tarjeta de prueba oficial](https://www.mercadopago.com.pe/developers/es/docs/checkout-pro-orders/integration-test/test-purchase-with-card) y el titular `APRO` para simular aprobación. No uses una tarjeta real.
3. En **Mercado Pago Developers → Tu aplicación → Prueba → Webhooks**, confirma `order.processed` con entrega HTTP `200`. Si aparece `401`, revisa la firma secreta; si aparece `503`, revisa Gateway, Order y el túnel con `docker compose ps` y `docker compose logs --tail=100 gateway-service order-service`.
4. Actualiza **Mis pedidos** hasta ver `PAID`. En **Mi biblioteca**, comprueba que aparece el producto y descarga el archivo. En Mailpit, busca el correo de compra enviado al comprador. La pantalla de éxito de Mercado Pago por sí sola no confirma la acreditación en Digital Store: el webhook validado actualiza el pedido y genera el evento.
5. Si el pedido queda `REVIEW_REQUIRED`, usa la vista administrativa para inspeccionar el motivo. Comprueba especialmente que `MERCADOPAGO_APPLICATION_ID` coincide con la aplicación de prueba y que `MERCADOPAGO_EXPECTED_LIVE_MODE=false`.

El evento Kafka `orders.events.v1` usa como clave el ID del pedido; su payload `ORDER_PAID` versión 1 está definido en [`docs/contracts/order-paid-v1.schema.json`](docs/contracts/order-paid-v1.schema.json). El contrato HTTP está en [`docs/contracts/http-api-v1.yaml`](docs/contracts/http-api-v1.yaml).

## Verificación y CI local

Desde la raíz ejecuta `./mvnw verify` en Bash/WSL o `.\mvnw.cmd verify` en PowerShell. Dentro de `web/`, ejecuta `npm ci`, `npm run lint` y `npm run build`. `verify` comprueba formato Java con Spotless y ejecuta pruebas, incluidas las de integración con Testcontainers; requiere Docker activo. Si falla el formato, ejecuta `mvn spotless:apply` y repite `verify`.

`ci.yml` se ejecuta al subir cambios a `master`, `chore/**`, `feat/**` o `fix/**`; ejecuta las pruebas Java, valida el contrato OpenAPI, revisa y compila la web, y construye imágenes en ese orden. En `master`, analiza Java con SonarQube después de `verify` y espera el Quality Gate antes de construir imágenes. Requiere un runner de GitHub Actions **Linux x64 en WSL2**, con etiqueta `portafolio-local` y acceso a Docker Desktop. En GitHub, abre **Settings → Actions → Runners → New self-hosted runner**, sigue los comandos Linux proporcionados, asigna la etiqueta y deja `./run.sh` activo.

### Cobertura y SonarQube local

`verify` genera informes JaCoCo en `services/*/target/site/jacoco/jacoco.xml` y HTML en la misma carpeta. SonarQube usa un Compose independiente: añade `SONAR_DB_USER` y `SONAR_DB_PASSWORD` a `.env` según `.env.example`, y arráncalo desde la raíz con PowerShell:

```powershell
docker compose --env-file .env -f infra/sonar/compose.yaml up -d
docker compose --env-file .env -f infra/sonar/compose.yaml ps
```

Abre `http://localhost:9000` y crea el proyecto con clave `digital-store`. En **Settings → Secrets and variables → Actions** de GitHub, guarda un token de análisis del proyecto como secreto `SONAR_TOKEN` y `http://localhost:9000` como variable `SONAR_HOST_URL`. Mantén SonarQube activo en la máquina del runner y comprueba desde WSL2 que `curl -fsS http://localhost:9000/api/system/status` devuelve `"status":"UP"`. El análisis corre solo en `master`; un Quality Gate fallido detiene ese job antes de construir imágenes. Para parar SonarQube sin borrar sus volúmenes, ejecuta `docker compose --env-file .env -f infra/sonar/compose.yaml down`.

Para `deploy-local.yml`, crea en **Settings → Secrets and variables → Actions** los secretos `POSTGRES_USER`, `POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `ORDER_SERVICE_CLIENT_SECRET`, `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_WEBHOOK_SECRET` y `MERCADOPAGO_APPLICATION_ID`, con los mismos valores locales de prueba. El workflow manual se ejecuta solo desde `master` y usa Compose en esa máquina. CI usa valores de relleno para construir imágenes y no llama a Mercado Pago real. El túnel no es necesario para `mvn verify` ni para CI.
