# Digital Store Web

Cliente React, Vite y TypeScript. La sesión utiliza el cliente público `digital-store-web` del realm `digital-store` en Keycloak.

## Ejecutar en local

1. Levanta Keycloak en `http://localhost:8081` y comprueba que el cliente permita `http://localhost:5173/*` como URI de redirección y `http://localhost:5173` como origen web.
2. Ejecuta `npm ci` y `npm run dev` dentro de `web/`.
3. Abre `http://localhost:5173/` y prueba **Iniciar sesión**, **Mi cuenta** y **Cerrar sesión**.

Los valores locales están en `src/app/keycloak.ts`. Para usar otro servidor, copia `.env.example` a `.env.local` y cambia las variables `VITE_KEYCLOAK_*`. Estas variables son públicas; nunca coloques secretos de cliente en archivos de Vite.

## Peticiones privadas

Usa `authenticatedFetch` de `src/app/authenticatedFetch.ts` para las futuras llamadas privadas:

```ts
import { authenticatedFetch } from './app/authenticatedFetch'

const response = await authenticatedFetch('http://localhost:8080/api/orders')
```

La función renueva el token con `updateToken(30)` y agrega el encabezado `Authorization`. Los tokens se conservan solo en la instancia de `keycloak-js` en memoria.
