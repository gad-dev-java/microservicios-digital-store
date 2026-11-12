import { useEffect, useRef, useState } from 'react'
import { formatPrice } from '../catalog/api/products'
import { AdminPaymentReviewPanel } from './AdminPaymentReviewPanel'
import { getMyOrder, listOrders, openCheckout, type OrderDetail, type OrderPage, type OrderSummary } from './api/orders'

type OrdersPageProps = {
  mode: 'mine' | 'admin'
}

const orderStates: Record<string, string> = {
  CREATED: 'Creado',
  PENDING_PAYMENT: 'Pendiente de pago',
  PAID: 'Pagado',
  PAYMENT_FAILED: 'Pago fallido',
}

const paymentStates: Record<string, string> = {
  CREATING: 'Preparando pago',
  PENDING: 'Pendiente',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  EXPIRED: 'Expirado',
  ERROR: 'Error',
  REVIEW_REQUIRED: 'En revisión',
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('es-PE', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function canOpenCheckout(order: OrderSummary): boolean {
  return ['CREATED', 'PENDING_PAYMENT', 'PAYMENT_FAILED'].includes(order.status)
    && !['CREATING', 'REVIEW_REQUIRED'].includes(order.paymentStatus ?? '')
}

function checkoutUrl(value: string): string {
  const url = new URL(value)
  const host = url.hostname.toLowerCase()
  if (url.protocol !== 'https:' || (host !== 'mercadopago.com.pe' && !host.endsWith('.mercadopago.com.pe'))) {
    throw new Error('La dirección del checkout no es válida.')
  }
  return url.href
}

export function OrdersPage({ mode }: OrdersPageProps) {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<OrderPage | null>(null)
  const [detail, setDetail] = useState<OrderDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [checkoutOrderId, setCheckoutOrderId] = useState<string | null>(null)
  const [reviewOrderId, setReviewOrderId] = useState<string | null>(null)
  const submittingCheckout = useRef(false)

  useEffect(() => {
    const controller = new AbortController()

    void listOrders(mode, page, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setResult(data)
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(cause instanceof Error ? cause.message : 'No se pudieron cargar los pedidos.')
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })

    return () => controller.abort()
  }, [mode, page])

  function changePage(nextPage: number) {
    setLoading(true)
    setError(null)
    setCheckoutError(null)
    setDetail(null)
    setReviewOrderId(null)
    setPage(nextPage)
  }

  function refreshOrders() {
    void listOrders(mode, page)
      .then((data) => setResult(data))
      .catch((cause: unknown) => {
        setError(cause instanceof Error ? cause.message : 'No se pudieron actualizar los pedidos.')
      })
  }

  async function openDetail(id: string) {
    if (detailLoading) return
    setError(null)
    setDetailLoading(true)
    try {
      setDetail(await getMyOrder(id))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'No se pudo abrir el pedido.')
    } finally {
      setDetailLoading(false)
    }
  }

  async function pay(orderId: string) {
    if (submittingCheckout.current) return
    submittingCheckout.current = true
    setCheckoutOrderId(orderId)
    setError(null)
    setCheckoutError(null)

    try {
      const session = await openCheckout(orderId)
      window.location.assign(checkoutUrl(session.checkoutUrl))
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'No se pudo abrir el pago.'
      try {
        setResult(await listOrders(mode, page))
        if (detail?.order.id === orderId) setDetail(await getMyOrder(orderId))
      } catch {
        // La consulta de estado es auxiliar; conservamos el error original del checkout.
      }
      setCheckoutError(message)
      setCheckoutOrderId(null)
      submittingCheckout.current = false
    }
  }

  return (
    <section className="card orders-panel" aria-labelledby="orders-title">
      <div className="orders-heading">
        <div>
          <span className="eyebrow">Digital Store</span>
          <h2 id="orders-title">{mode === 'mine' ? 'Mis pedidos' : 'Revisar pedidos'}</h2>
        </div>
        {!loading && result && <span>{result.totalItems} en total</span>}
      </div>

      {loading && <p role="status">Cargando pedidos…</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {checkoutError && <p className="error" role="alert">{checkoutError}</p>}
      {!loading && !error && result?.items.length === 0 && (
        <p>{mode === 'mine' ? 'Todavía no tienes pedidos.' : 'Todavía no hay pedidos.'}</p>
      )}

      {!loading && !error && result && (
        <ul className="orders-list">
          {result.items.map((order) => (
            <li key={order.id} className="orders-list-item">
              <div className="orders-item-heading">
                <div><span className="order-id-label">Pedido #{order.id.slice(0, 8).toUpperCase()}</span><small className="order-full-id">{order.id}</small></div>
                <span className={`order-status order-status-${order.status.toLowerCase()}`}>{orderStates[order.status] ?? order.status}</span>
              </div>
              {mode === 'admin' && <p>Comprador: {order.buyerEmail}</p>}
              <p>Creado: {formatDate(order.createdAt)}</p>
              <p>Pago: <span className="payment-state">{order.paymentStatus
                ? paymentStates[order.paymentStatus] ?? order.paymentStatus
                : 'Aún sin intento'}</span></p>
              {order.paymentStatus === 'REVIEW_REQUIRED' && order.reviewReason && (
                <p>Motivo de revisión: {order.reviewReason}</p>
              )}
              <p>Total: <strong>{formatPrice({
                price: order.totalAmount,
                currency: order.currency,
              })}</strong></p>
              {mode === 'mine' && (
                <div className="orders-payment-actions">
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => void openDetail(order.id)}
                    disabled={detailLoading || checkoutOrderId !== null}
                  >
                    Ver detalle
                  </button>
                  {canOpenCheckout(order) && (
                    <button
                      type="button"
                      className="primary"
                      onClick={() => void pay(order.id)}
                      disabled={checkoutOrderId !== null}
                    >
                      {checkoutOrderId === order.id
                        ? 'Abriendo checkout…'
                        : order.paymentStatus === 'PENDING'
                          ? 'Continuar pago'
                          : 'Pagar con Mercado Pago'}
                    </button>
                  )}
                </div>
              )}
              {mode === 'admin' && (
                <div className="orders-payment-actions">
                  <button
                    type="button"
                    className="secondary"
                    aria-expanded={reviewOrderId === order.id}
                    onClick={() => setReviewOrderId((current) => current === order.id ? null : order.id)}
                  >
                    {reviewOrderId === order.id ? 'Ocultar intentos' : 'Ver intentos'}
                  </button>
                </div>
              )}
              {mode === 'admin' && reviewOrderId === order.id && (
                <AdminPaymentReviewPanel orderId={order.id} onUpdated={refreshOrders} />
              )}
              {mode === 'mine' && order.paymentStatus === 'CREATING' && (
                <p>El pago se está preparando. Consulta el estado antes de volver a intentarlo.</p>
              )}
              {mode === 'mine' && order.paymentStatus === 'REVIEW_REQUIRED' && (
                <p>El pago está en revisión. No inicies otro checkout.</p>
              )}
            </li>
          ))}
        </ul>
      )}

      {!loading && !error && result && result.totalPages > 1 && (
        <nav className="pagination" aria-label="Páginas de pedidos">
          <button type="button" disabled={page === 0 || checkoutOrderId !== null} onClick={() => changePage(page - 1)}>
            Anterior
          </button>
          <span>Página {page + 1} de {result.totalPages}</span>
          <button
            type="button"
            disabled={page + 1 >= result.totalPages || checkoutOrderId !== null}
            onClick={() => changePage(page + 1)}
          >
            Siguiente
          </button>
        </nav>
      )}

      {detailLoading && <p role="status">Cargando detalle…</p>}
      {detail && (
        <section className="order-confirmation orders-detail" aria-label="Detalle del pedido">
          <div className="orders-item-heading">
            <h3>Detalle del pedido</h3>
            <button type="button" className="secondary" onClick={() => setDetail(null)}>
              Cerrar
            </button>
          </div>
          <p>Número: {detail.order.id}</p>
          <p>Estado: {orderStates[detail.order.status] ?? detail.order.status}</p>
          <p>Pago: {detail.order.paymentStatus
            ? paymentStates[detail.order.paymentStatus] ?? detail.order.paymentStatus
            : 'Aún sin intento'}</p>
          {detail.order.paidAt && <p>Pagado: {formatDate(detail.order.paidAt)}</p>}
          {detail.order.paymentStatus === 'REVIEW_REQUIRED' && detail.order.reviewReason && (
            <p>Motivo de revisión: {detail.order.reviewReason}</p>
          )}
          <ul>
            {detail.items.map((item) => (
              <li key={item.productId}>
                {item.title} ({item.sku}) — {formatPrice({
                  price: item.unitPrice,
                  currency: detail.order.currency,
                })}
              </li>
            ))}
          </ul>
          <p>Total: <strong>{formatPrice({
            price: detail.order.totalAmount,
            currency: detail.order.currency,
          })}</strong></p>
          {canOpenCheckout(detail.order) && (
            <button
              type="button"
              className="primary"
              onClick={() => void pay(detail.order.id)}
              disabled={checkoutOrderId !== null}
            >
              {checkoutOrderId === detail.order.id
                ? 'Abriendo checkout…'
                : detail.order.paymentStatus === 'PENDING'
                  ? 'Continuar pago'
                  : 'Pagar con Mercado Pago'}
            </button>
          )}
        </section>
      )}
    </section>
  )
}
