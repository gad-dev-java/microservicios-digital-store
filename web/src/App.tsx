import { useEffect, useState } from 'react'
import { keycloak } from './app/keycloak'
import { AdminProductsPage } from './features/admin/AdminProductsPage'
import { formatPrice, type ProductSummary } from './features/catalog/api/products'
import { CatalogPage } from './features/catalog/pages/CatalogPage'
import { ProductDetailPage } from './features/catalog/pages/ProductDetailPage'
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
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [selectedProductId, setSelectedProductId] = useState<string | null>(null)
  const [cart, setCart] = useState<ProductSummary[]>([])
  const [adminOpen, setAdminOpen] = useState(false)

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
  const isAdmin = authenticated && keycloak.hasRealmRole('ADMIN')

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
    setAdminOpen(false)
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

  function addToCart(product: ProductSummary) {
    setCart((current) => current.some((item) => item.id === product.id)
      ? current
      : [...current, product])
  }

  function searchProducts(title: string) {
    setPage(0)
    setQuery(title)
  }

  return (
    <main className="page">
      <section className="card" aria-labelledby="page-title">
        <span className="eyebrow">Digital Store</span>
        <h1 id="page-title">Tu tienda digital</h1>
        <p className="intro">Explora productos digitales y prepara tu carrito.</p>

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
              {isAdmin && (
                <button
                  type="button"
                  className="secondary"
                  aria-pressed={adminOpen}
                  onClick={() => setAdminOpen((open) => !open)}
                >
                  {adminOpen ? 'Ver tienda' : 'Administrar productos'}
                </button>
              )}
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

      {isAdmin && adminOpen ? (
        <AdminProductsPage />
      ) : (
        <div className="storefront">
        {selectedProductId ? (
          <ProductDetailPage
            id={selectedProductId}
            onBack={() => setSelectedProductId(null)}
            onAdd={addToCart}
          />
        ) : (
          <CatalogPage
            query={query}
            page={page}
            onSearch={searchProducts}
            onPageChange={setPage}
            onOpen={setSelectedProductId}
            onAdd={addToCart}
          />
        )}

        <aside className="cart-panel" aria-labelledby="cart-title">
          <div className="cart-heading">
            <div>
              <span className="eyebrow">Tu selección</span>
              <h2 id="cart-title">Carrito</h2>
            </div>
            <span className="cart-count" aria-live="polite">{cart.length}</span>
          </div>

          {cart.length === 0 ? (
            <p className="cart-empty">Añade un producto para verlo aquí.</p>
          ) : (
            <ul className="cart-items">
              {cart.map((item) => (
                <li key={item.id}>
                  <div>
                    <strong>{item.title}</strong>
                    <span>{formatPrice(item)}</span>
                  </div>
                  <button
                    type="button"
                    className="remove-button"
                    aria-label={`Quitar ${item.title} del carrito`}
                    onClick={() => setCart((current) => current.filter((entry) => entry.id !== item.id))}
                  >
                    Quitar
                  </button>
                </li>
              ))}
            </ul>
          )}

          <p className="cart-note">El precio final se confirmará al crear el pedido.</p>
        </aside>
        </div>
      )}
    </main>
  )
}

export default App
