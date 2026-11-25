# HT-15: tienda local en Minikube

Este overlay despliega la misma tienda de Compose en Kubernetes local. Usa DNS de Services, PostgreSQL y Kafka persistentes, un PVC para archivos de Catalog y sondas de salud. El navegador mantiene `http://localhost:5173` para la web y `http://localhost:8081` para Keycloak. No usa Eureka ni Config Server.

## Antes de arrancar

Desde la raíz del repositorio, en PowerShell, inicia Docker Desktop y comprueba que `docker info` responde. Deja libres los puertos de la tienda con `docker compose down` (sin `-v`; conserva los volúmenes de Compose). Minikube usa volúmenes distintos: **los usuarios, productos y pedidos de Compose no se copian automáticamente**. Necesitas un `.env` con las credenciales locales y los valores de Mercado Pago de **prueba**. No versiones `.env` ni el Secret generado.

```powershell
minikube start --driver=docker --cpus=6 --memory=10240
kubectl config current-context
kubectl get storageclass
```

Ajusta CPU y memoria según el equipo. El contexto debe ser `minikube` y debe existir una StorageClass predeterminada. Si el clúster ya está iniciado, no necesitas recrearlo. Los PVC de PostgreSQL, Kafka y archivos sobreviven a reinicios de pods y a `minikube stop`; `minikube delete` elimina el clúster y sus datos.

## Construir y cargar imágenes

```powershell
foreach ($service in @('catalog-service', 'order-service', 'notification-service', 'gateway-service')) {
    docker build --build-arg "SERVICE_NAME=$service" -t "digital-store/${service}:ht15" .
    if ($LASTEXITCODE -ne 0) { throw "Falló la imagen $service" }
    minikube image load --overwrite=true "digital-store/${service}:ht15"
    if ($LASTEXITCODE -ne 0) { throw "No se cargó la imagen $service" }
}
docker build -f web/Dockerfile.k8s -t digital-store/web:ht15 web
if ($LASTEXITCODE -ne 0) { throw 'Falló la imagen web' }
minikube image load --overwrite=true digital-store/web:ht15
if ($LASTEXITCODE -ne 0) { throw 'No se cargó la imagen web' }
```

`minikube image load` funciona con el runtime predeterminado de Minikube; no hace falta cambiar el Docker daemon del equipo. Al reconstruir con la misma etiqueta, vuelve a cargar la imagen y reinicia su Deployment.

## Crear configuración y desplegar

El script crea el Namespace, tres ConfigMaps con los archivos versionados y un Secret desde `.env`. Solo muestra los nombres de los objetos; no imprime las credenciales. Fija el contexto `minikube` para evitar aplicar secretos en otro clúster.

```powershell
./infra/k8s/bootstrap.ps1
kubectl kustomize infra/k8s/overlays/minikube | Out-Null
kubectl --context minikube apply -k infra/k8s/overlays/minikube
kubectl --context minikube -n digital-store get pods,svc,pvc,jobs
```

El primer arranque crea las cuatro bases con `infra/postgres/init/01-databases.sql` y Keycloak importa el realm. Esos pasos solo ocurren con una base nueva. Las tablas de Catalog, Order y Notification las crean sus migraciones Flyway. Comprueba que los PVC estén `Bound`, el Job `kafka-init` esté `Complete` y los Deployments estén disponibles:

```powershell
kubectl --context minikube -n digital-store wait --for=condition=complete job/kafka-init --timeout=300s
foreach ($name in @('catalog-service', 'order-service', 'notification-service', 'gateway-service', 'web', 'keycloak')) {
    kubectl --context minikube -n digital-store rollout status "deployment/$name" --timeout=300s
}
```

Si un pod no inicia, usa `kubectl --context minikube -n digital-store describe pod NOMBRE` y `kubectl --context minikube -n digital-store logs NOMBRE --all-containers --tail=100`. Si cambias `.env`, repite `bootstrap.ps1` y reinicia los Deployments que leen el Secret. Si cambias `create-topics.sh` tras completar el Job, elimínalo y vuelve a aplicar el overlay para ejecutarlo de nuevo.

Si Nginx devuelve `502` para `/api/...`, revisa `kubectl --context minikube -n digital-store get pods,endpoints`: `gateway-service` debe tener un endpoint. Un evento `Startup probe failed: HTTP probe failed with statuscode: 401` indica que la seguridad está bloqueando `/actuator/health/liveness` o `/actuator/health/readiness`; las tres aplicaciones protegidas deben permitir estas subrutas sin JWT. Reconstruye y carga sus imágenes después de cambiar Java; reiniciar el pod sin actualizar la imagen conserva el error.

## Entradas locales y webhook

Abre cuatro terminales independientes y mantén cada comando activo:

```powershell
kubectl --context minikube -n digital-store port-forward svc/web 5173:80
kubectl --context minikube -n digital-store port-forward svc/keycloak 8081:8080
kubectl --context minikube -n digital-store port-forward svc/mailpit 8025:8025
kubectl --context minikube -n digital-store port-forward svc/gateway-service 8080:8080
```

La web está en `http://localhost:5173`, Keycloak en `http://localhost:8081`, Mailpit en `http://localhost:8025` y Gateway en `http://localhost:8080`. El Service de Gateway publica únicamente su puerto de aplicación `8080`; el puerto de gestión `8082` queda dentro del pod. Los demás Services son `ClusterIP` y no tienen puertos del host. Para Mercado Pago, abre otra terminal:

```powershell
cloudflared tunnel --url http://localhost:8080
```

En la configuración de **prueba** de Mercado Pago, usa la nueva URL `https://TU-TUNEL.trycloudflare.com/api/webhooks/mercadopago` y el evento Order. Cada túnel rápido puede generar otra URL. El túnel da acceso al webhook por Gateway, no a Keycloak ni a los endpoints Actuator.

## Aceptación

1. Crea un administrador en el realm nuevo, publica un producto con archivo y registra un comprador. Abre el correo de verificación en Mailpit.
2. Compra el producto con Mercado Pago de prueba. Comprueba la notificación HTTP `200`, el pedido `PAID`, el correo y la descarga desde Mi biblioteca.
3. Verifica que `http://localhost:8080/api/internal/catalog/products/ID` devuelve un rechazo y que `http://localhost:8080/actuator/prometheus` no publica métricas.
4. Reinicia `statefulset/postgres` y `deployment/catalog-service`, espera a que estén listos y verifica que el pedido y el archivo siguen disponibles:

```powershell
kubectl --context minikube -n digital-store rollout restart statefulset/postgres
kubectl --context minikube -n digital-store rollout status statefulset/postgres --timeout=300s
kubectl --context minikube -n digital-store rollout restart deployment/catalog-service
kubectl --context minikube -n digital-store rollout status deployment/catalog-service --timeout=300s
kubectl --context minikube -n digital-store get pvc
```

Un `port-forward` puede desconectarse cuando cambia el pod; vuelve a iniciarlo. Detén la demo con `minikube stop`. No uses `minikube delete` ni elimines el Namespace si quieres conservar pedidos y archivos. Compose puede volver a levantarse de manera independiente con `docker compose up --build -d`.

Las sondas `liveness` y `readiness` de Java usan Actuator; Gateway las recibe en el puerto de gestión ligado a la IP del pod. El perfil `k8s` desactiva la exportación OTLP porque este overlay no despliega el Collector de HT-13. La instrumentación y el perfil de observabilidad de Compose continúan disponibles.

Referencias: [Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/), [imágenes en Minikube](https://minikube.sigs.k8s.io/docs/handbook/pushing/), [sondas de Spring Boot](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes) y [port-forward](https://kubernetes.io/docs/tasks/access-application-cluster/port-forward-access-application-cluster/).
