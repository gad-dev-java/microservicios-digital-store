# Digital Store

## CI y despliegue local

El repositorio usa un runner de GitHub Actions Linux x64 dentro de WSL2,
con la etiqueta `portafolio-local`. En GitHub, abre Settings → Actions →
Runners → New self-hosted runner, selecciona **Linux / x64** y ejecuta en WSL
los comandos de instalación que GitHub proporciona. Añade la etiqueta durante
la configuración; los workflows requieren `self-hosted`, `Linux`, `X64` y
`portafolio-local`.

Inicia Docker Desktop con integración WSL2 y comprueba `docker compose version`
desde WSL. En la carpeta del runner, ejecuta `./run.sh` y mantén esa terminal
abierta para recibir trabajos.

`ci.yml` se ejecuta al subir cambios a `master`, `chore/**`, `feat/**` o
`fix/**`. Ejecuta `mvn verify`, instala dependencias, valida `web/` con Oxlint,
compila la web y construye las imágenes Docker. No ejecuta código de pull
requests externos. El paso `mvn verify` también comprueba el formato Java con Spotless;
para corregirlo antes de subir cambios, ejecuta `mvn spotless:apply` desde la raíz.

Para desplegar, configura en GitHub Actions los secretos `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` y
`ORDER_SERVICE_CLIENT_SECRET` con los valores del stack local. Después de
integrar los cambios en `master`, abre
Actions → Deploy local → Run workflow. El despliegue ejecuta Docker Compose
en la máquina del runner. Comprueba el resultado con `docker compose ps`.
