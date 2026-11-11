export type ProductSummary = {
  id: string
  title: string
  price: number
  currency: 'PEN'
}

export type ProductDetail = ProductSummary & {
  description: string
}

export type ProductPage = {
  items: ProductSummary[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Error HTTP ${response.status}`)
  }

  return response.json() as Promise<T>
}

export async function listProducts(
  title: string,
  page: number,
  signal?: AbortSignal,
): Promise<ProductPage> {
  const params = new URLSearchParams({ q: title, page: String(page), size: '12' })
  const response = await fetch(`/api/products?${params}`, { signal })
  return readJson<ProductPage>(response)
}

export async function getProduct(id: string, signal?: AbortSignal): Promise<ProductDetail> {
  const response = await fetch(`/api/products/${encodeURIComponent(id)}`, { signal })
  return readJson<ProductDetail>(response)
}

export function formatPrice(product: Pick<ProductSummary, 'price' | 'currency'>): string {
  return new Intl.NumberFormat('es-PE', {
    style: 'currency',
    currency: product.currency,
  }).format(product.price)
}
