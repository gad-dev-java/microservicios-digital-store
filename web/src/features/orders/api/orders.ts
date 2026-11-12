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
