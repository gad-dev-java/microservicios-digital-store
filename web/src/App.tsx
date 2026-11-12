import { useEffect, useRef, useState } from 'react'
import { keycloak } from './app/keycloak'
import { AdminProductsPage } from './features/admin/AdminProductsPage'
import { formatPrice, type ProductSummary } from './features/catalog/api/products'
import { CatalogPage } from './features/catalog/pages/CatalogPage'
import { ProductDetailPage } from './features/catalog/pages/ProductDetailPage'
import { createOrder, type CreatedOrder } from './features/orders/api/orders'
import { OrdersPage } from './features/orders/OrdersPage'
import heroArt from './assets/hero.png'
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

  function openStore() {
    setAdminOpen(false)
    setOrdersMode(null)
    setSelectedProductId(null)
  }

  const showingStore = ordersMode === null && !adminOpen

  return (
    <div className="site-shell">
      <div className="demo-banner">Tienda de demostración · Los pagos se realizan en modo de prueba</div>
      <header className="site-header">
        <div className="header-inner">
          <button type="button" className="brand" onClick={openStore} aria-label="Digital Store, ir a la tienda">
            <span className="brand-mark" aria-hidden="true">D</span>
            <span>digital<span className="brand-accent">store.</span></span>
          </button>
          <nav className="site-nav" aria-label="Navegación principal">
            <button type="button" className={showingStore ? 'nav-link active' : 'nav-link'} onClick={openStore} aria-current={showingStore ? 'page' : undefined}>Tienda</button>
            {authenticated && keycloak.hasRealmRole('CUSTOMER') && (
              <button type="button" className={ordersMode === 'mine' ? 'nav-link active' : 'nav-link'} onClick={() => { setAdminOpen(false); setOrdersMode('mine') }} aria-current={ordersMode === 'mine' ? 'page' : undefined}>Mis pedidos</button>
            )}
            {isAdmin && (
              <>
                <button type="button" className={adminOpen ? 'nav-link active' : 'nav-link'} onClick={() => { setOrdersMode(null); setAdminOpen(true) }} aria-current={adminOpen ? 'page' : undefined}>Productos</button>
                <button type="button" className={ordersMode === 'admin' ? 'nav-link active' : 'nav-link'} onClick={() => { setAdminOpen(false); setOrdersMode('admin') }} aria-current={ordersMode === 'admin' ? 'page' : undefined}>Pedidos</button>
              </>
            )}
          </nav>
          <div className="header-actions">
            {authenticated ? (
              <>
                <div className="user-chip" title={`Rol: ${role}`}>
                  <span className="user-avatar" aria-hidden="true">{String(name).charAt(0).toUpperCase()}</span>
                  <span className="user-chip-text"><strong>{name}</strong><small>{role === 'ADMIN' ? 'Administrador' : 'Mi cuenta'}</small></span>
                </div>
                <button type="button" className="header-text-button" onClick={openAccount} disabled={busy}>Mi cuenta</button>
                <button type="button" className="header-text-button" onClick={endSession} disabled={busy}>Salir</button>
              </>
            ) : (
              <button type="button" className="primary header-login" onClick={startSession} disabled={busy || initializationError}>Iniciar sesión</button>
            )}
          </div>
        </div>
      </header>

      <main className="page">
        {error && <p className="error global-error" role="alert">{error}</p>}

        {showingStore ? (
          <>
            {!selectedProductId && (
              <section className="hero" aria-labelledby="page-title">
                <div className="hero-copy">
                  <span className="hero-kicker"><span aria-hidden="true">✦</span> Descubre algo nuevo</span>
                  <h1 id="page-title">Ideas que puedes <em>llevar más lejos.</em></h1>
                  <p>Encuentra recursos digitales para aprender, crear y avanzar a tu ritmo.</p>
                  <a className="hero-cta" href="#catalogo">Explorar productos <span aria-hidden="true">↗</span></a>
                </div>
                <div className="hero-visual" aria-hidden="true">
                  <div className="hero-orbit hero-orbit-one" />
                  <div className="hero-orbit hero-orbit-two" />
                  <div className="hero-art-card"><img src={heroArt} alt="" /></div>
                  <div className="hero-float hero-float-top">01 / Explora</div>
                  <div className="hero-float hero-float-bottom">Digital goods ✳</div>
                </div>
              </section>
            )}

            <div className="storefront" id="catalogo">
              {selectedProductId ? (
                <ProductDetailPage id={selectedProductId} onBack={() => setSelectedProductId(null)} onAdd={addToCart} />
              ) : (
                <CatalogPage query={query} page={page} onSearch={searchProducts} onPageChange={setPage} onOpen={setSelectedProductId} onAdd={addToCart} />
              )}

              <aside className="cart-panel" aria-labelledby="cart-title">
                <div className="cart-heading">
                  <div><span className="eyebrow">Tu selección</span><h2 id="cart-title">Carrito</h2></div>
                  <span className="cart-count" aria-live="polite">{cart.length}</span>
                </div>
                {cart.length === 0 ? (
                  <div className="cart-empty-state"><span className="cart-empty-icon" aria-hidden="true">✳</span><strong>Tu carrito está vacío</strong><p>Explora el catálogo y añade algo que te inspire.</p></div>
                ) : (
                  <ul className="cart-items">
                    {cart.map((item) => (
                      <li key={item.id}>
                        <div><strong>{item.title}</strong><span>{formatPrice(item)}</span></div>
                        <button type="button" className="remove-button" aria-label={`Quitar ${item.title} del carrito`} onClick={() => removeFromCart(item.id)} disabled={creatingOrder}>Quitar</button>
                      </li>
                    ))}
                  </ul>
                )}
                {cart.length > 0 && (
                  <>
                    <div className="cart-total"><span>Total estimado</span><strong>{formatPrice({ price: cart.reduce((total, item) => total + item.price, 0), currency: 'PEN' })}</strong></div>
                    <p className="cart-note">El precio final se confirma al crear el pedido.</p>
                    <button type="button" className="primary create-order-button" onClick={submitOrder} disabled={creatingOrder}>{creatingOrder ? 'Creando pedido…' : 'Crear pedido'} <span aria-hidden="true">→</span></button>
                  </>
                )}
                {orderError && <p className="error" role="alert">{orderError}</p>}
                {orderResult && (
                  <div className="order-confirmation" role="status">
                    <span className="confirmation-mark" aria-hidden="true">✓</span>
                    <h3>{orderResult.replayed ? 'Pedido recuperado' : 'Pedido creado'}</h3>
                    <p>Número: <strong>{orderResult.order.id}</strong></p>
                    <p>Total: <strong>{formatPrice({ price: orderResult.order.totalAmount, currency: orderResult.order.currency })}</strong></p>
                    <p>Continúa al pago desde Mis pedidos.</p>
                    <button type="button" className="secondary" onClick={() => setOrdersMode('mine')}>Ver mis pedidos</button>
                  </div>
                )}
              </aside>
            </div>
          </>
        ) : (
          <>
            <section className="workspace-intro">
              <span className="eyebrow">{adminOpen || ordersMode === 'admin' ? 'Panel de administración' : 'Tu espacio'}</span>
              <h1>{adminOpen ? 'Gestiona tu catálogo' : ordersMode === 'admin' ? 'Control de pedidos' : 'Tus compras digitales'}</h1>
              <p>{adminOpen ? 'Prepara, publica y organiza tus productos.' : ordersMode === 'admin' ? 'Consulta pedidos y revisa los pagos que requieren atención.' : 'Consulta el estado de tus pedidos y continúa tus pagos.'}</p>
            </section>
            {ordersMode && authenticated ? <OrdersPage key={ordersMode} mode={ordersMode} /> : isAdmin && adminOpen ? <AdminProductsPage /> : null}
          </>
        )}
      </main>
      <footer className="site-footer"><span>digitalstore.</span><span>Recursos digitales · Experiencia de demostración</span></footer>
    </div>
  )
}

export default App
