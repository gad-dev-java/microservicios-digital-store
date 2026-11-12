import { authenticatedFetch } from '../../../app/authenticatedFetch'

export type OrderItem = {
  productId: string
  sku: string
  title: string
  unitPrice: number
}

export type Order = {
  id: string
  status: string
  totalAmount: number
  currency: 'PEN'
  items: OrderItem[]
}

export type CreatedOrder = {
  order: Order
  replayed: boolean
}

type ApiProblem = {
  code?: string
  detail?: string
}

export async function createOrder(productIds: string[], idempotencyKey: string): Promise<CreatedOrder> {
  const response = await authenticatedFetch('/api/orders', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify({ productIds }),
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => null) as ApiProblem | null
    const knownError: Record<string, string> = {
      EMAIL_NOT_VERIFIED: 'Verifica tu correo en Mi cuenta antes de crear el pedido.',
      PRODUCT_ALREADY_OWNED: 'Ya tienes uno de estos productos. Quítalo del carrito e inténtalo de nuevo.',
      PRODUCT_NOT_FOUND: 'Uno de los productos ya no está disponible. Revisa el carrito.',
      CATALOG_UNAVAILABLE: 'El catálogo no está disponible. Inténtalo de nuevo en unos segundos.',
      IDEMPOTENCY_CONFLICT: 'El carrito cambió durante el intento. Vuelve a crear el pedido.',
    }

    if (response.status === 401) {
      throw new Error('La sesión expiró. Inicia sesión de nuevo.')
    }
    if (response.status === 403 && !problem?.code) {
      throw new Error('Tu cuenta no tiene permiso para crear pedidos.')
    }

    throw new Error((problem?.code && knownError[problem.code]) || problem?.detail || `No se pudo crear el pedido (HTTP ${response.status}).`)
  }

  return {
    order: await response.json() as Order,
    replayed: response.status === 200,
  }
}

export type OrderSummary = {
  id: string
  buyerEmail: string
  status: string
  totalAmount: number
  currency: 'PEN'
  createdAt: string
  paidAt: string | null
  paymentStatus: string | null
  reviewReason: string | null
}

export type OrderPage = {
  items: OrderSummary[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export type OrderDetail = {
  order: OrderSummary
  items: OrderItem[]
}

async function readOrderResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    if (response.status === 401) throw new Error('La sesión expiró. Inicia sesión de nuevo.')
    if (response.status === 403) throw new Error('No tienes permiso para consultar estos pedidos.')
    if (response.status === 404) throw new Error('Pedido no encontrado.')
    throw new Error(`No se pudieron consultar los pedidos (HTTP ${response.status}).`)
  }

  return await response.json() as T
}

export async function listOrders(
  mode: 'mine' | 'admin',
  page: number,
  signal?: AbortSignal,
): Promise<OrderPage> {
  const path = mode === 'mine' ? '/api/orders/me' : '/api/admin/orders'
  const response = await authenticatedFetch(`${path}?page=${page}&size=20`, { signal })
  return readOrderResponse<OrderPage>(response)
}

export async function getMyOrder(id: string): Promise<OrderDetail> {
  const response = await authenticatedFetch(`/api/orders/${encodeURIComponent(id)}`)
  return readOrderResponse<OrderDetail>(response)
}

export type CheckoutSession = {
  attemptId: string
  checkoutUrl: string
  reused: boolean
}

export async function openCheckout(orderId: string): Promise<CheckoutSession> {
  const response = await authenticatedFetch(`/api/orders/${encodeURIComponent(orderId)}/checkout`, {
    method: 'POST',
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => null) as ApiProblem | null
    const knownError: Record<string, string> = {
      ORDER_NOT_FOUND: 'Pedido no encontrado.',
      ORDER_NOT_PAYABLE: 'Este pedido ya no permite iniciar un pago.',
      PRODUCT_ALREADY_OWNED: 'Ya compraste uno de los productos de este pedido. Revisa tus compras antes de intentar pagar de nuevo.',
      PAYMENT_IN_PROGRESS: 'El intento de pago está en curso o requiere revisión. Consulta el estado del pedido.',
      PAYMENT_PROVIDER_UNAVAILABLE: 'Mercado Pago no está disponible. Consulta el estado del pedido antes de reintentar.',
    }

    if (response.status === 401) throw new Error('La sesión expiró. Inicia sesión de nuevo.')
    if (response.status === 403) throw new Error('No tienes permiso para pagar este pedido.')
    throw new Error(
      (problem?.code && knownError[problem.code])
      || problem?.detail
      || `No se pudo abrir el pago de prueba (HTTP ${response.status}).`,
    )
  }

  return await response.json() as CheckoutSession
}
