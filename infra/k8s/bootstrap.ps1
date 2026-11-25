$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$namespace = 'digital-store'
$environmentFile = Join-Path $repoRoot '.env'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw 'Falta .env en la raiz del repositorio. Copia .env.example y completa las credenciales de prueba.'
}

$requiredKeys = @(
    'POSTGRES_USER',
    'POSTGRES_PASSWORD',
    'KEYCLOAK_ADMIN',
    'KEYCLOAK_ADMIN_PASSWORD',
    'ORDER_SERVICE_CLIENT_SECRET',
    'MERCADOPAGO_ACCESS_TOKEN',
    'MERCADOPAGO_WEBHOOK_SECRET',
    'MERCADOPAGO_APPLICATION_ID',
    'MERCADOPAGO_EXPECTED_LIVE_MODE'
)
$keys = @{}
foreach ($line in [System.IO.File]::ReadAllLines($environmentFile)) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=') {
        $keys[$Matches[1]] = $line.Substring($Matches[1].Length + 1)
    }
}
if ($keys['MERCADOPAGO_EXPECTED_LIVE_MODE'] -ne 'false') {
    throw 'HT-15 solo permite MERCADOPAGO_EXPECTED_LIVE_MODE=false (entorno de prueba).'
}
foreach ($key in $requiredKeys) {
    if (-not $keys.ContainsKey($key)) {
        throw "Falta $key en .env"
    }
}

function Invoke-Kubectl {
    param([string[]] $Arguments)
    & kubectl --context minikube @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl fallo con codigo $LASTEXITCODE"
    }
}

Invoke-Kubectl -Arguments @('apply', '-f', (Join-Path $PSScriptRoot 'base/namespace.yaml'))

$configMaps = @(
    @{ Name = 'postgres-init'; File = (Join-Path $repoRoot 'infra/postgres/init/01-databases.sql') },
    @{ Name = 'keycloak-realm'; File = (Join-Path $repoRoot 'infra/keycloak/digital-store-realm.json') },
    @{ Name = 'kafka-topics'; File = (Join-Path $repoRoot 'infra/kafka/create-topics.sh') }
)
foreach ($map in $configMaps) {
    & kubectl --context minikube -n $namespace create configmap $map.Name `
        "--from-file=$($map.File)" --dry-run=client -o yaml |
        kubectl --context minikube apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo crear el ConfigMap $($map.Name)"
    }
}

& kubectl --context minikube -n $namespace create secret generic store-secrets `
    "--from-env-file=$environmentFile" --dry-run=client -o yaml |
    kubectl --context minikube apply -f -
if ($LASTEXITCODE -ne 0) {
    throw 'No se pudo crear el Secret store-secrets'
}

Write-Host 'ConfigMaps y Secret creados en Minikube. No se escribieron credenciales en el repositorio.'
