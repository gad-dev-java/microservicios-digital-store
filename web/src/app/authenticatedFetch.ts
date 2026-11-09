import { initializeKeycloak, keycloak } from './keycloak'

export async function authenticatedFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  await initializeKeycloak()

  if (!keycloak.authenticated) {
    throw new Error('Inicia sesión para acceder a este recurso.')
  }

  try {
    await keycloak.updateToken(30)
  } catch {
    throw new Error('La sesión expiró. Vuelve a iniciar sesión.')
  }

  if (!keycloak.token) {
    throw new Error('No hay un token de acceso disponible.')
  }

  const headers = new Headers(input instanceof Request ? input.headers : undefined)
  new Headers(init.headers).forEach((value, name) => headers.set(name, value))
  headers.set('Authorization', `Bearer ${keycloak.token}`)

  return fetch(input, { ...init, headers })
}
