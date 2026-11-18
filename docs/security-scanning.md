# Escaneo de imágenes

HT-12 analiza las imágenes construidas para config-server, discovery-server,
catalog-service, order-service, notification-service y gateway-service.

Trivy genera un SBOM SPDX JSON y un informe JSON de vulnerabilidades por imagen.
Se registran todas las severidades. Una vulnerabilidad CRITICAL bloquea CI y el
despliegue local. No se excluyen vulnerabilidades solo porque aún no tengan
corrección disponible.

Las excepciones se registran en `.trivyignore.yaml`. Cada excepción requiere un
ID específico, el componente afectado, una justificación y `expired_at`.
Revisar y retirar la excepción antes de esa fecha. No añadir excepciones
generales para permitir que pase el pipeline.

Los informes se descargan desde los artefactos del workflow de GitHub Actions.
El escaneo solo usa las imágenes locales recién construidas.