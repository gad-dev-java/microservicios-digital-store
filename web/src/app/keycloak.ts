import Keycloak from 'keycloak-js'

// Una sola instancia conserva los tokens únicamente en memoria durante la sesión de la página.
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8081',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'digital-store',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'digital-store-web',
})

let initialization: Promise<boolean> | undefined

export function initializeKeycloak(): Promise<boolean> {
  initialization ??= keycloak.init({
    onLoad: 'check-sso',
    pkceMethod: 'S256',
  })

  return initialization
}
