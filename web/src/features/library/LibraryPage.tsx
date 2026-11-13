import { useEffect, useState } from 'react'
import { downloadProduct, getLibrary, type LibraryItem, type LibraryPage as LibraryResult } from './api/library'

const dateFormat = new Intl.DateTimeFormat('es-PE', { dateStyle: 'medium' })

export function LibraryPage() {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<LibraryResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const [downloadingId, setDownloadingId] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()

    void getLibrary(page, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setResult(data)
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(cause instanceof Error ? cause.message : 'No se pudo cargar la biblioteca.')
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })

    return () => controller.abort()
  }, [page, revision])

  function changePage(nextPage: number) {
    setPage(nextPage)
    setResult(null)
    setLoading(true)
    setError(null)
    setDownloadError(null)
  }

  function refresh() {
    setResult(null)
    setLoading(true)
    setError(null)
    setRevision((current) => current + 1)
  }

  async function download(item: LibraryItem) {
    if (downloadingId !== null) return
    setDownloadingId(item.productId)
    setDownloadError(null)
    try {
      await downloadProduct(item)
    } catch (cause) {
      setDownloadError(cause instanceof Error ? cause.message : 'No se pudo descargar el archivo.')
    } finally {
      setDownloadingId(null)
    }
  }

  return (
    <section className="card library-panel" aria-labelledby="library-title">
      <div className="library-heading">
        <div>
          <span className="eyebrow">Tus recursos</span>
          <h2 id="library-title">Mi biblioteca</h2>
          <p>Accede a los productos digitales de tus compras confirmadas.</p>
        </div>
        {!loading && result && <span className="library-count">{result.totalItems} {result.totalItems === 1 ? 'producto' : 'productos'}</span>}
      </div>

      {loading && <p role="status">Cargando tu biblioteca…</p>}
      {error && (
        <div className="library-feedback" role="alert">
          <p>{error}</p>
          <button type="button" className="secondary" onClick={refresh}>Reintentar</button>
        </div>
      )}
      {downloadError && <p className="error library-download-error" role="alert">{downloadError}</p>}

      {!loading && !error && result?.items.length === 0 && (
        <div className="library-empty">
          <span className="library-empty-icon" aria-hidden="true">✳</span>
          <h3>Tu biblioteca todavía está vacía</h3>
          <p>Cuando se confirme una compra, encontrarás aquí tus archivos para descargarlos.</p>
        </div>
      )}

      {!loading && !error && result && result.items.length > 0 && (
        <ul className="library-grid">
          {result.items.map((item) => (
            <li key={item.productId} className="library-item">
              <div className="library-cover" aria-hidden="true"><span>{item.title.slice(0, 1).toUpperCase()}</span></div>
              <div className="library-item-content">
                <span className="library-sku">{item.sku}</span>
                <h3>{item.title}</h3>
                <p>Adquirido el {dateFormat.format(new Date(item.grantedAt))}</p>
                <small>Pedido #{item.orderId.slice(0, 8).toUpperCase()}</small>
                <button
                  type="button"
                  className="primary library-download-button"
                  onClick={() => void download(item)}
                  disabled={downloadingId !== null}
                  aria-label={`Descargar ${item.title}`}
                >
                  {downloadingId === item.productId ? 'Preparando descarga…' : 'Descargar archivo'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {!loading && !error && result && result.totalPages > 1 && (
        <nav className="pagination library-pagination" aria-label="Páginas de la biblioteca">
          <button type="button" onClick={() => changePage(page - 1)} disabled={page === 0 || downloadingId !== null}>Anterior</button>
          <span>Página {page + 1} de {result.totalPages}</span>
          <button type="button" onClick={() => changePage(page + 1)} disabled={page + 1 >= result.totalPages || downloadingId !== null}>Siguiente</button>
        </nav>
      )}
    </section>
  )
}
