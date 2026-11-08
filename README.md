# Digital Store

## CI y despliegue local

El repositorio usa un runner de GitHub Actions instalado en Windows con la
etiqueta `portafolio-local`. Para configurarlo, entra en GitHub:
Settings → Actions → Runners → New self-hosted runner. Ejecuta los comandos
que GitHub proporciona y añade la etiqueta durante la configuración.

Inicia Docker Desktop y, desde la carpeta de instalación del runner, ejecuta
`.\run.cmd`. Mantén esa terminal abierta para recibir trabajos.

`ci.yml` se ejecuta al subir cambios a `master`, `chore/**`, `feat/**` o
`fix/**`. Ejecuta `mvn verify`, instala y compila `web/`, y construye las
imágenes Docker. No ejecuta código de pull requests externos.

Para desplegar, configura en GitHub Actions los secretos `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN` y `KEYCLOAK_ADMIN_PASSWORD` con los
valores del stack local. Después de integrar los cambios en `master`, abre
Actions → Deploy local → Run workflow. El despliegue ejecuta Docker Compose
en la máquina del runner. Comprueba el resultado con `docker compose ps`.