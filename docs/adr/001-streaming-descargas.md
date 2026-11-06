# ADR 001: Descarga en streaming mediante Order Service

**Estado:** aceptada para el MVP.

## Contexto

Order Service posee los derechos de descarga (`entitlements`) y Catalog Service almacena los archivos. El comprador solo puede recibir un archivo después de validar su identidad y su derecho de acceso.

## Decisión

La petición entra por Gateway. Order Service valida el derecho y solicita el archivo al endpoint interno de Catalog Service con una credencial de servicio. Los tres saltos transmiten el contenido en streaming, con límites de tamaño, concurrencia y tiempo de inactividad. Gateway no expone `/api/internal/**`.

Se elige este flujo porque requiere menos piezas: no abre una ruta pública nueva en Catalog ni necesita emitir y validar tokens de descarga. Un enlace reutilizable durante 60 segundos para su comprador no sería, por sí mismo, un problema de autorización; el uso único no es un requisito del MVP.

## Consecuencias

Order Service y Gateway consumen conexiones durante la descarga, aunque no almacenan el archivo completo en memoria. Si la demanda crece, se evaluará una ruta directa de Catalog con un token breve; el uso único solo se añadirá si aparece un requisito que lo justifique.
