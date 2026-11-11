import { useEffect, useState, type FormEvent } from 'react'
import { formatPrice } from '../catalog/api/products'
import {
  changeProductStatus,
  createDraft,
  listAdminProducts,
  uploadProductFile,
  type AdminProductPage,
} from './api/adminProducts'

const MAX_FILE_SIZE = 50 * 1024 * 1024

const statusLabels = {
  DRAFT: 'Borrador',
  PUBLISHED: 'Publicado',
  ARCHIVED: 'Archivado',
} as const

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'No se pudo completar la operación.'
}

export function AdminProductsPage() {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [response, setResponse] = useState<{
    key: string
    data: AdminProductPage | null
    error: string | null
  }>({ key: '', data: null, error: null })
  const [sku, setSku] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [price, setPrice] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const requestKey = `${page}:${revision}`
  const loading = response.key !== requestKey
  const data = loading ? null : response.data
  const loadError = loading ? null : response.error

  useEffect(() => {
    const controller = new AbortController()

    listAdminProducts(page, controller.signal)
      .then((products) => setResponse({ key: requestKey, data: products, error: null }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setResponse({ key: requestKey, data: null, error: errorMessage(error) })
        }
      })

    return () => controller.abort()
  }, [page, requestKey])

  async function runAction(action: () => Promise<unknown>, successMessage: string) {
    setBusy(true)
    setActionError(null)
    setMessage(null)

    try {
      await action()
      setMessage(successMessage)
      setRevision((current) => current + 1)
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const amount = Number(price)
    if (!Number.isFinite(amount) || amount <= 0 || !/^\d+(?:\.\d{1,2})?$/.test(price)) {
      setMessage(null)
      setActionError('Introduce un precio positivo con máximo dos decimales.')
      return
    }

    void runAction(async () => {
      await createDraft({
        sku: sku.trim(),
        title: title.trim(),
        description: description.trim(),
        price: amount,
      })
      setSku('')
      setTitle('')
      setDescription('')
      setPrice('')
      setPage(0)
    }, 'Borrador creado.')
  }

  function handleUpload(id: string, file: File) {
    if (file.size === 0 || file.size > MAX_FILE_SIZE) {
      setMessage(null)
      setActionError('El archivo debe medir entre 1 byte y 50 MB.')
      return
    }

    void runAction(() => uploadProductFile(id, file), 'Archivo cargado.')
  }

  function handleStatus(id: string, action: 'publish' | 'archive') {
    void runAction(
      () => changeProductStatus(id, action),
      action === 'publish' ? 'Producto publicado.' : 'Producto archivado.',
    )
  }

  return (
    <section className="admin-panel" aria-labelledby="admin-title">
      <div className="admin-heading">
        <div>
          <span className="eyebrow">Administración</span>
          <h2 id="admin-title">Productos digitales</h2>
        </div>
      </div>

      {actionError && <p className="error" role="alert">{actionError}</p>}
      {message && <p className="success" role="status">{message}</p>}

      <form className="admin-form" onSubmit={handleCreate}>
        <h3>Crear borrador</h3>
        <div className="admin-fields">
          <label>
            SKU
            <input
              required
              maxLength={64}
              pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,63}"
              value={sku}
              onChange={(event) => setSku(event.target.value)}
              placeholder="EBOOK-JAVA-21"
            />
          </label>
          <label>
            Título
            <input
              required
              maxLength={200}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Guía de Java 21"
            />
          </label>
          <label>
            Precio en PEN
            <input
              required
              type="number"
              min="0.01"
              step="0.01"
              value={price}
              onChange={(event) => setPrice(event.target.value)}
              placeholder="39.90"
            />
          </label>
          <label className="admin-description">
            Descripción
            <textarea
              required
              rows={4}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="¿Qué incluye este producto?"
            />
          </label>
        </div>
        <button type="submit" className="primary" disabled={busy}>
          {busy ? 'Guardando…' : 'Crear borrador'}
        </button>
      </form>

      <div className="admin-list-heading">
        <h3>Productos</h3>
        {data && <span className="result-count">{data.totalItems} en total</span>}
      </div>

      {loading && <p className="feedback" role="status">Cargando productos…</p>}
      {loadError && (
        <div>
          <p className="error" role="alert">{loadError}</p>
          <button type="button" className="secondary" onClick={() => setRevision((value) => value + 1)}>
            Reintentar
          </button>
        </div>
      )}

      {data && (
        <>
          {data.items.length === 0 ? (
            <p className="feedback">Todavía no hay productos en esta página.</p>
          ) : (
            <div className="admin-product-list">
              {data.items.map((product) => (
                <article className="admin-product" key={product.id}>
                  <div className="admin-product-main">
                    <span className={`status-badge status-${product.status.toLowerCase()}`}>
                      {statusLabels[product.status]}
                    </span>
                    <h4>{product.title}</h4>
                    <p className="admin-sku">SKU: {product.sku}</p>
                    <p>{formatPrice(product)}</p>
                    <p className="admin-file">
                      Archivo: {product.fileName ?? 'Sin archivo'}
                    </p>
                  </div>
                  <div className="admin-product-actions">
                    {product.status === 'DRAFT' && !product.fileName && (
                      <label className="file-picker">
                        Subir PDF, ZIP o TXT
                        <input
                          type="file"
                          accept=".pdf,.zip,.txt"
                          disabled={busy}
                          onChange={(event) => {
                            const file = event.target.files?.[0]
                            if (file) handleUpload(product.id, file)
                            event.target.value = ''
                          }}
                        />
                      </label>
                    )}
                    {product.status === 'DRAFT' && (
                      <button
                        type="button"
                        className="primary"
                        disabled={busy || !product.fileName}
                        onClick={() => handleStatus(product.id, 'publish')}
                      >
                        Publicar
                      </button>
                    )}
                    {product.status === 'PUBLISHED' && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => handleStatus(product.id, 'archive')}
                      >
                        Archivar
                      </button>
                    )}
                  </div>
                </article>
              ))}
            </div>
          )}

          {data.totalPages > 1 && (
            <nav className="pagination" aria-label="Páginas de administración">
              <button
                type="button"
                className="secondary"
                disabled={busy || page === 0}
                onClick={() => setPage((current) => current - 1)}
              >
                Anterior
              </button>
              <span>Página {page + 1} de {data.totalPages}</span>
              <button
                type="button"
                className="secondary"
                disabled={busy || page + 1 >= data.totalPages}
                onClick={() => setPage((current) => current + 1)}
              >
                Siguiente
              </button>
            </nav>
          )}
        </>
      )}
    </section>
  )
}
