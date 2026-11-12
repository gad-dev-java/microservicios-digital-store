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
  const [detailOrderId, setDetailOrderId] = useState<string | null>(null)
  const [detail, setDetail] = useState<OrderDetail | null>(null)
  const [detailError, setDetailError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoadingOrderId, setDetailLoadingOrderId] = useState<string | null>(null)
  const [checkoutOrderId, setCheckoutOrderId] = useState<string | null>(null)
  const [reviewOrderId, setReviewOrderId] = useState<string | null>(null)
  const submittingCheckout = useRef(false)
  const detailRequest = useRef(0)
  const checkoutDialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = checkoutDialog.current
    if (checkoutError && dialog && !dialog.open) dialog.showModal()
    if (!checkoutError && dialog?.open) dialog.close()
  }, [checkoutError])

  useEffect(() => () => { detailRequest.current += 1 }, [])

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
    detailRequest.current += 1
    setDetailOrderId(null)
    setDetail(null)
    setDetailError(null)
    setDetailLoadingOrderId(null)
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
    if (detailOrderId === id) {
      detailRequest.current += 1
      setDetailOrderId(null)
      setDetail(null)
      setDetailError(null)
      setDetailLoadingOrderId(null)
      return
    }

    const request = ++detailRequest.current
    setDetailOrderId(id)
    setDetail(null)
    setDetailError(null)
    setDetailLoadingOrderId(id)
    try {
      const order = await getMyOrder(id)
      if (request === detailRequest.current) setDetail(order)
    } catch (cause) {
      if (request === detailRequest.current) {
        setDetailError(cause instanceof Error ? cause.message : 'No se pudo abrir el pedido.')
      }
    } finally {
      if (request === detailRequest.current) setDetailLoadingOrderId(null)
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
      setCheckoutError(message)
      setCheckoutOrderId(null)
      submittingCheckout.current = false
      try {
        setResult(await listOrders(mode, page))
        if (detailOrderId === orderId) setDetail(await getMyOrder(orderId))
      } catch {
        // La consulta de estado es auxiliar; conservamos el error original del checkout.
      }
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
                    aria-expanded={detailOrderId === order.id}
                    aria-controls={detailOrderId === order.id ? `order-detail-${order.id}` : undefined}
                    disabled={checkoutOrderId !== null}
                  >
                    {detailOrderId === order.id ? 'Ocultar detalle' : 'Ver detalle'}
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
              {mode === 'mine' && detailOrderId === order.id && (
                <section id={`order-detail-${order.id}`} className="orders-detail" aria-label={`Detalle del pedido ${order.id}`}>
                  <div className="orders-detail-heading">
                    <h3>Detalle del pedido</h3>
                    <button type="button" className="text-button" onClick={() => void openDetail(order.id)}>Cerrar</button>
                  </div>
                  {detailLoadingOrderId === order.id && <p role="status">Cargando detalle…</p>}
                  {detailError && <p className="error" role="alert">{detailError}</p>}
                  {detail?.order.id === order.id && (
                    <>
                      <div className="orders-detail-meta">
                        <p><span>Número</span><strong>{detail.order.id}</strong></p>
                        <p><span>Estado</span><strong>{orderStates[detail.order.status] ?? detail.order.status}</strong></p>
                        <p><span>Pago</span><strong>{detail.order.paymentStatus
                          ? paymentStates[detail.order.paymentStatus] ?? detail.order.paymentStatus
                          : 'Aún sin intento'}</strong></p>
                        {detail.order.paidAt && <p><span>Pagado</span><strong>{formatDate(detail.order.paidAt)}</strong></p>}
                        {detail.order.paymentStatus === 'REVIEW_REQUIRED' && detail.order.reviewReason && (
                          <p><span>Motivo de revisión</span><strong>{detail.order.reviewReason}</strong></p>
                        )}
                      </div>
                      <h4>Productos</h4>
                      <ul className="orders-detail-items">
                        {detail.items.map((item) => (
                          <li key={item.productId}>
                            <span><strong>{item.title}</strong><small>SKU: {item.sku}</small></span>
                            <strong>{formatPrice({ price: item.unitPrice, currency: detail.order.currency })}</strong>
                          </li>
                        ))}
                      </ul>
                      <div className="orders-detail-total"><span>Total</span><strong>{formatPrice({
                        price: detail.order.totalAmount,
                        currency: detail.order.currency,
                      })}</strong></div>
                    </>
                  )}
                </section>
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

      <dialog
        ref={checkoutDialog}
        className="checkout-dialog"
        role="alertdialog"
        aria-labelledby="checkout-dialog-title"
        aria-describedby="checkout-dialog-description"
        onClose={() => setCheckoutError(null)}
        onClick={(event) => {
          if (event.target === event.currentTarget) event.currentTarget.close()
        }}
      >
        <div className="checkout-dialog-heading">
          <span className="checkout-dialog-icon" aria-hidden="true">!</span>
          <button type="button" className="dialog-close" aria-label="Cerrar aviso" onClick={() => checkoutDialog.current?.close()}>×</button>
        </div>
        <h2 id="checkout-dialog-title">No se pudo continuar con el pago</h2>
        <p id="checkout-dialog-description">{checkoutError}</p>
        <button type="button" className="primary" onClick={() => checkoutDialog.current?.close()}>Entendido</button>
      </dialog>
    </section>
  )
}
