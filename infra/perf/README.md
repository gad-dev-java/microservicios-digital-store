# HT-14: mediciones locales con k6

Ejecuta la carga completa en un proyecto Compose aislado, con usuarios y productos de prueba. No ejecutes estos escenarios contra Mercado Pago ni contra una demo compartida. Los reportes JSON quedan en `infra/perf/results/`, fuera de Git.

## Umbrales iniciales

La primera medición local, con 5 usuarios virtuales durante 1 minuto para catálogo y 2 durante 30 segundos para los escenarios privados, dio los siguientes p95: catálogo con Redis 12,75 ms; pedidos 23,56 ms; descargas 31,85 ms. Los límites iniciales del script dejan margen para variación local: 50, 100 y 150 ms, respectivamente, con menos de 1 % de errores y más de 99 % de comprobaciones correctas. Ajusta `K6_P95_MS` al repetir una línea base representativa en otro equipo. Un umbral incumplido hace fallar k6.

La comparación Redis activo/detenido se hace con el mismo `K6_MODE`, `PERF_VUS` y `PERF_DURATION`. La primera medición devolvió 1445 peticiones y p95 de 12,75 ms con Redis; 1439 y 6,80 ms sin Redis. Ambas tuvieron 0 % de errores. Es una comprobación de disponibilidad durante el fallo de caché, no evidencia de una ganancia de velocidad con este conjunto de datos pequeño.

## Ejecución manual

Desde PowerShell en la raíz del repositorio, crea el directorio de reportes y levanta el proyecto aislado. Si otra instancia usa los mismos puertos locales, detenla primero con `docker compose down` sin `-v`.

```powershell
New-Item -ItemType Directory -Force .\infra\perf\results | Out-Null
docker compose -p ht14 up --build -d
docker compose -p ht14 ps
docker compose -p ht14 run --rm --no-deps `
  -e K6_MODE=catalog `
  -e K6_BASE_URL=http://gateway-service:8080 `
  -e PERF_VUS=5 `
  -e PERF_DURATION=1m `
  -e K6_CACHE_MODE=redis-on `
  -e K6_REPORT=/results/catalog-redis-on.json `
  k6 run /scripts/store.js
```

Para comparar sin caché, ejecuta `docker compose -p ht14 stop redis`, repite el comando cambiando `K6_CACHE_MODE=redis-off` y el nombre del reporte, y luego ejecuta `docker compose -p ht14 start redis`. No arranques toda la pila entre las dos mediciones.

Para `K6_MODE=orders`, prepara un producto publicado no poseído por un comprador verificado en este proyecto. Pasa `K6_CUSTOMER_TOKEN` y `K6_PRODUCT_ID` con `docker compose run -e NOMBRE`, sin escribir el JWT en el comando ni en archivos. Para `K6_MODE=downloads`, el comprador debe poseer `K6_DOWNLOAD_PRODUCT_ID`. Los pedidos de carga se guardan realmente; no los ejecutes sobre datos compartidos. El JWT debe proceder del Keycloak del mismo proyecto Compose y tener vigencia durante la prueba.

Para la prueba de Catalog lento, usa `-f compose.yaml -f infra/perf/compose.slow.yaml` tanto en `up` como en `run`. El filtro solo se activa en el perfil `perf` y retrasa 2500 ms la consulta interna de Order. Los `503` y el fallo del umbral de errores son esperados en esta prueba; confirma en los logs de Order los timeouts y la apertura del Circuit Breaker. La primera ejecución lenta dio 86 respuestas fallidas, p95 de 2017,55 ms y ningún pedido persistido.

Si vas a explicar las métricas en Grafana, levanta `compose.observability.yaml` con el mismo nombre de proyecto `ht14` y los mismos archivos Compose. Un Grafana conectado a otro proyecto no observará esta pila aislada.

Al finalizar, ejecuta `docker compose -p ht14 down` sin `-v` para conservar datos de este proyecto si necesitas repetir una medición.

## CI

El workflow ejecuta solo 1 usuario virtual durante 10 segundos contra el catálogo público de Catalog en un proyecto Compose nuevo, sin puertos publicados. Usa un límite p95 de 500 ms para tolerar la variación del runner y guarda `ci-catalog.json` como artefacto. El proyecto y sus volúmenes se eliminan al terminar. No usa JWT ni llama al proveedor de pagos. Usa `PERF_VUS` y `PERF_DURATION` para no sustituir por accidente los escenarios del script con las variables reservadas `K6_VUS` y `K6_DURATION`.
