import { useEffect, useState } from 'react'
import { formatPrice } from '../catalog/api/products'
import { getMyOrder, listOrders, type OrderDetail, type OrderPage } from './api/orders'

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

export function OrdersPage({ mode }: OrdersPageProps) {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<OrderPage | null>(null)
  const [detail, setDetail] = useState<OrderDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)

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
    setDetail(null)
    setPage(nextPage)
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
                <strong>Pedido {order.id}</strong>
                <span>{orderStates[order.status] ?? order.status}</span>
              </div>
              {mode === 'admin' && <p>Comprador: {order.buyerEmail}</p>}
              <p>Creado: {formatDate(order.createdAt)}</p>
              <p>Pago: {order.paymentStatus
                ? paymentStates[order.paymentStatus] ?? order.paymentStatus
                : 'Aún sin intento'}</p>
              {order.reviewReason && <p>Motivo de revisión: {order.reviewReason}</p>}
              <p>Total: <strong>{formatPrice({
                price: order.totalAmount,
                currency: order.currency,
              })}</strong></p>
              {mode === 'mine' && (
                <button
                  type="button"
                  className="secondary"
                  onClick={() => void openDetail(order.id)}
                  disabled={detailLoading}
                >
                  Ver detalle
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {!loading && !error && result && result.totalPages > 1 && (
        <nav className="pagination" aria-label="Páginas de pedidos">
          <button type="button" disabled={page === 0} onClick={() => changePage(page - 1)}>
            Anterior
          </button>
          <span>Página {page + 1} de {result.totalPages}</span>
          <button
            type="button"
            disabled={page + 1 >= result.totalPages}
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
          {detail.order.reviewReason && <p>Motivo de revisión: {detail.order.reviewReason}</p>}
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
        </section>
      )}
    </section>
  )
}
