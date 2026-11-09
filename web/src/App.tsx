import { useEffect, useState } from 'react'
import { keycloak } from './app/keycloak'
import './App.css'

type AppProps = {
  initializationError?: boolean
}

function App({ initializationError = false }: AppProps) {
  const [authenticated, setAuthenticated] = useState(keycloak.authenticated === true)
  const [error, setError] = useState<string | null>(
    initializationError ? 'No se pudo conectar con Keycloak. Revisa que esté en ejecución y recarga la página.' : null,
  )
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    keycloak.onAuthSuccess = () => setAuthenticated(true)
    keycloak.onAuthLogout = () => setAuthenticated(false)

    return () => {
      keycloak.onAuthSuccess = undefined
      keycloak.onAuthLogout = undefined
    }
  }, [])

  const name = keycloak.tokenParsed?.name
    ?? keycloak.tokenParsed?.preferred_username
    ?? 'Usuario'
  const role = keycloak.hasRealmRole('ADMIN')
    ? 'ADMIN'
    : keycloak.hasRealmRole('CUSTOMER')
      ? 'CUSTOMER'
      : 'Sin rol asignado'

  async function startSession() {
    setBusy(true)
    setError(null)
    try {
      await keycloak.login({ redirectUri: `${window.location.origin}/` })
    } catch {
      setError('No se pudo iniciar sesión. Inténtalo de nuevo.')
      setBusy(false)
    }
  }

  async function endSession() {
    setBusy(true)
    setError(null)
    try {
      await keycloak.logout({ redirectUri: `${window.location.origin}/` })
    } catch {
      setError('No se pudo cerrar sesión. Inténtalo de nuevo.')
      setBusy(false)
    }
  }

  async function openAccount() {
    setBusy(true)
    setError(null)
    try {
      await keycloak.accountManagement()
    } catch {
      setError('No se pudo abrir Mi cuenta. Inténtalo de nuevo.')
      setBusy(false)
    }
  }

  return (
    <main className="page">
      <section className="card" aria-labelledby="page-title">
        <span className="eyebrow">Digital Store</span>
        <h1 id="page-title">Tu tienda digital</h1>
        <p className="intro">Accede a tu cuenta para consultar tus compras y gestionar tu perfil.</p>

        {error && <p className="error" role="alert">{error}</p>}

        {authenticated ? (
          <div className="session">
            <p className="session-label">Sesión iniciada</p>
            <p className="user-name">{name}</p>
            <p className="user-role">Rol: {role}</p>
            <div className="actions">
              <button type="button" className="primary" onClick={openAccount} disabled={busy}>
                Mi cuenta
              </button>
              <button type="button" className="secondary" onClick={endSession} disabled={busy}>
                Cerrar sesión
              </button>
            </div>
          </div>
        ) : (
          <div className="session">
            <p className="session-label">Todavía no has iniciado sesión.</p>
            <button type="button" className="primary" onClick={startSession} disabled={busy || initializationError}>
              Iniciar sesión
            </button>
          </div>
        )}
      </section>
    </main>
  )
}

export default App
