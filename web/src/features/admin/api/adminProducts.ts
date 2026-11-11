import { authenticatedFetch } from '../../../app/authenticatedFetch'

export type AdminProduct = {
  id: string
  sku: string
  title: string
  description: string
  price: number
  currency: 'PEN'
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  fileName: string | null
  fileSize: number | null
}

export type AdminProductPage = {
  items: AdminProduct[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export type CreateDraft = {
  sku: string
  title: string
  description: string
  price: number
}

type ApiProblem = {
  detail?: string
  code?: string
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as ApiProblem | null

    if (response.status === 401) {
      throw new Error('La sesión expiró. Inicia sesión de nuevo.')
    }
    if (response.status === 403) {
      throw new Error('Tu cuenta no tiene permiso de administrador.')
    }
    if (response.status === 413) {
      throw new Error('El archivo supera el tamaño permitido de 50 MB.')
    }

    throw new Error(problem?.detail ?? `La operación falló (HTTP ${response.status}).`)
  }

  return response.json() as Promise<T>
}

export async function listAdminProducts(
  page: number,
  signal?: AbortSignal,
): Promise<AdminProductPage> {
  const params = new URLSearchParams({ page: String(page), size: '20' })
  const response = await authenticatedFetch(`/api/admin/products?${params}`, { signal })
  return readJson<AdminProductPage>(response)
}

export async function createDraft(draft: CreateDraft): Promise<AdminProduct> {
  const response = await authenticatedFetch('/api/admin/products', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(draft),
  })
  return readJson<AdminProduct>(response)
}

export async function uploadProductFile(id: string, file: File): Promise<AdminProduct> {
  const body = new FormData()
  body.append('file', file)

  const response = await authenticatedFetch(`/api/admin/products/${id}/file`, {
    method: 'POST',
    body,
  })
  return readJson<AdminProduct>(response)
}

export async function changeProductStatus(
  id: string,
  action: 'publish' | 'archive',
): Promise<AdminProduct> {
  const response = await authenticatedFetch(`/api/admin/products/${id}/${action}`, {
    method: 'POST',
  })
  return readJson<AdminProduct>(response)
}
