import { useEffect, useRef, useState } from 'react'
import { keycloak } from './app/keycloak'
import { AdminProductsPage } from './features/admin/AdminProductsPage'
import { formatPrice, type ProductSummary } from './features/catalog/api/products'
import { CatalogPage } from './features/catalog/pages/CatalogPage'
import { ProductDetailPage } from './features/catalog/pages/ProductDetailPage'
import { createOrder, type CreatedOrder } from './features/orders/api/orders'
import { OrdersPage } from './features/orders/OrdersPage'
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
  const [ordersMode, setOrdersMode] = useState<'mine' | 'admin' | null>(null)
  const [orderResult, setOrderResult] = useState<CreatedOrder | null>(null)
  const [orderError, setOrderError] = useState<string | null>(null)
  const [creatingOrder, setCreatingOrder] = useState(false)
  const pendingOrder = useRef<{ productIds: string[]; key: string } | null>(null)
  const submittingOrder = useRef(false)

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
    setOrdersMode(null)
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
    if (submittingOrder.current || cart.some((item) => item.id === product.id)) return
    pendingOrder.current = null
    setOrderResult(null)
    setOrderError(null)
    setCart((current) => [...current, product])
  }

  function removeFromCart(id: string) {
    if (submittingOrder.current) return
    pendingOrder.current = null
    setOrderError(null)
    setCart((current) => current.filter((item) => item.id !== id))
  }

  async function submitOrder() {
    if (submittingOrder.current || cart.length === 0) return
    if (!authenticated) {
      setOrderError('Inicia sesión para crear un pedido.')
      return
    }
    if (!keycloak.hasRealmRole('CUSTOMER')) {
      setOrderError('Tu cuenta no tiene permiso para crear pedidos.')
      return
    }
    if (keycloak.tokenParsed?.email_verified !== true) {
      setOrderError('Verifica tu correo en Mi cuenta antes de crear un pedido.')
      return
    }

    const productIds = cart.map((item) => item.id)
    const previous = pendingOrder.current
    const sameCart = previous && previous.productIds.length === productIds.length
      && previous.productIds.every((id, index) => id === productIds[index])
    const key = sameCart ? previous.key : crypto.randomUUID()
    pendingOrder.current = { productIds, key }
    submittingOrder.current = true
    setCreatingOrder(true)
    setOrderError(null)
    try {
      const result = await createOrder(productIds, key)
      setOrderResult(result)
      setCart([])
      pendingOrder.current = null
    } catch (cause) {
      setOrderError(cause instanceof Error ? cause.message : 'No se pudo crear el pedido.')
    } finally {
      submittingOrder.current = false
      setCreatingOrder(false)
    }
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
              {keycloak.hasRealmRole('CUSTOMER') && (
                <button
                  type="button"
                  className="secondary"
                  aria-pressed={ordersMode === 'mine'}
                  onClick={() => {
                    setAdminOpen(false)
                    setOrdersMode((current) => current === 'mine' ? null : 'mine')
                  }}
                  disabled={busy}
                >
                  {ordersMode === 'mine' ? 'Ver tienda' : 'Mis pedidos'}
                </button>
              )}
              {isAdmin && (
                <button
                  type="button"
                  className="secondary"
                  aria-pressed={adminOpen}
                  onClick={() => {
                    setOrdersMode(null)
                    setAdminOpen((open) => !open)
                  }}
                  disabled={busy}
                >
                  {adminOpen ? 'Ver tienda' : 'Administrar productos'}
                </button>
              )}
              {isAdmin && (
                <button
                  type="button"
                  className="secondary"
                  aria-pressed={ordersMode === 'admin'}
                  onClick={() => {
                    setAdminOpen(false)
                    setOrdersMode((current) => current === 'admin' ? null : 'admin')
                  }}
                  disabled={busy}
                >
                  {ordersMode === 'admin' ? 'Ver tienda' : 'Revisar pedidos'}
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

      {ordersMode && authenticated ? (
        <OrdersPage key={ordersMode} mode={ordersMode} />
      ) : isAdmin && adminOpen ? (
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
                    onClick={() => removeFromCart(item.id)}
                    disabled={creatingOrder}
                  >
                    Quitar
                  </button>
                </li>
              ))}
            </ul>
          )}

          <p className="cart-note">El precio final se confirmará al crear el pedido.</p>
          {cart.length > 0 && (
            <button type="button" className="primary create-order-button" onClick={submitOrder} disabled={creatingOrder}>
              {creatingOrder ? 'Creando pedido…' : 'Crear pedido'}
            </button>
          )}
          {orderError && <p className="error" role="alert">{orderError}</p>}
          {orderResult && (
            <div className="order-confirmation" role="status">
              <h3>{orderResult.replayed ? 'Pedido recuperado' : 'Pedido creado'}</h3>
              <p>Número: <strong>{orderResult.order.id}</strong></p>
              <p>Estado: {orderResult.order.status}</p>
              <ul>
                {orderResult.order.items.map((item) => (
                  <li key={item.productId}>{item.title} — {formatPrice({ price: item.unitPrice, currency: orderResult.order.currency })}</li>
                ))}
              </ul>
              <p>Total: <strong>{formatPrice({ price: orderResult.order.totalAmount, currency: orderResult.order.currency })}</strong></p>
              <p className="cart-note">El pago se habilitará en una historia posterior.</p>
              <button
                type="button"
                className="secondary"
                onClick={() => setOrdersMode('mine')}
              >
                Ver mis pedidos
              </button>
            </div>
          )}
        </aside>
        </div>
      )}
    </main>
  )
}

export default App
