#!/usr/bin/env bash
set -Eeuo pipefail

: "${COMPOSE_PROJECT_NAME:?Define COMPOSE_PROJECT_NAME antes de escanear}"

reports_dir="security-reports"
mkdir -p "$reports_dir"

services=(
  config-server
  discovery-server
  catalog-service
  order-service
  notification-service
  gateway-service
)

failed=0

for service in "${services[@]}"; do
  image="${COMPOSE_PROJECT_NAME}-${service}"
  echo "::group::Escanear ${image}"

  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "No existe la imagen local: ${image}" >&2
    failed=1
    echo "::endgroup::"
    continue
  fi

  if ! trivy image \
    --image-src docker \
    --format spdx-json \
    --output "${reports_dir}/${service}.spdx.json" \
    "$image"; then
    echo "No se pudo generar el SBOM de ${image}" >&2
    failed=1
    echo "::endgroup::"
    continue
  fi

  if ! trivy image \
    --image-src docker \
    --scanners vuln \
    --ignorefile .trivyignore.yaml \
    --format json \
    --output "${reports_dir}/${service}.vulnerabilities.json" \
    "$image"; then
    echo "No se pudo generar el informe de ${image}" >&2
    failed=1
    echo "::endgroup::"
    continue
  fi

  if ! trivy image \
    --image-src docker \
    --scanners vuln \
    --ignorefile .trivyignore.yaml \
    --severity CRITICAL \
    --exit-code 1 \
    "$image"; then
    echo "La imagen ${image} no cumple la política" >&2
    failed=1
  fi

  echo "::endgroup::"
done

exit "$failed"