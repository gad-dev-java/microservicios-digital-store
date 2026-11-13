import { authenticatedFetch } from '../../../app/authenticatedFetch'

export type LibraryItem = {
  productId: string
  orderId: string
  sku: string
  title: string
  grantedAt: string
}

export type LibraryPage = {
  items: LibraryItem[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

function requestError(status: number, action: 'open' | 'download'): Error {
  if (status === 401) return new Error('La sesión expiró. Inicia sesión de nuevo.')
  if (status === 403) return new Error('Tu cuenta no tiene permiso para acceder a la biblioteca.')
  if (status === 404) return new Error('Este producto no está disponible en tu biblioteca.')
  if (status === 429) return new Error('Hay varias descargas en curso. Espera unos segundos y vuelve a intentarlo.')
  if (status === 503) return new Error('El archivo no está disponible en este momento. Inténtalo más tarde.')
  return new Error(`No se pudo ${action === 'open' ? 'abrir la biblioteca' : 'descargar el archivo'} (HTTP ${status}).`)
}

function downloadName(header: string | null, fallback: string): string {
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header ?? '')?.[1]
  const plain = /filename\s*=\s*"([^"]+)"/i.exec(header ?? '')?.[1]
  let name = plain ?? fallback

  if (encoded) {
    try {
      name = decodeURIComponent(encoded)
    } catch {
      // Se conserva el nombre alternativo si el encabezado está mal codificado.
    }
  }

  return [...name]
    .map((character) => {
      const code = character.charCodeAt(0)
      return code < 32 || code === 127 || character === '/' || character === '\\' ? '_' : character
    })
    .join('')
    .slice(0, 255) || fallback
}

export async function getLibrary(page: number, signal?: AbortSignal): Promise<LibraryPage> {
  const response = await authenticatedFetch(`/api/library?page=${page}&size=20`, { signal })
  if (!response.ok) throw requestError(response.status, 'open')
  return await response.json() as LibraryPage
}

export async function downloadProduct(item: LibraryItem): Promise<void> {
  const response = await authenticatedFetch(`/api/library/${encodeURIComponent(item.productId)}/download`)
  if (!response.ok) throw requestError(response.status, 'download')

  const name = downloadName(response.headers.get('Content-Disposition'), `${item.sku}.bin`)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
