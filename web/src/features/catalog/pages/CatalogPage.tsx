import { useEffect, useState } from 'react'
import {
  formatPrice,
  listProducts,
  type ProductPage,
  type ProductSummary,
} from '../api/products'

type CatalogPageProps = {
  query: string
  page: number
  onSearch: (query: string) => void
  onPageChange: (page: number) => void
  onOpen: (id: string) => void
  onAdd: (product: ProductSummary) => void
}

export function CatalogPage({
  query,
  page,
  onSearch,
  onPageChange,
  onOpen,
  onAdd,
}: CatalogPageProps) {
  const [input, setInput] = useState(query)
  const [response, setResponse] = useState<{
    key: string
    result: ProductPage | null
    error: string | null
  }>({ key: '', result: null, error: null })
  const requestKey = `${query}\u0000${page}`
  const loading = response.key !== requestKey
  const result = loading ? null : response.result
  const error = loading ? null : response.error

  useEffect(() => {
    const controller = new AbortController()

    listProducts(query, page, controller.signal)
      .then((products) => setResponse({ key: requestKey, result: products, error: null }))
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setResponse({
            key: requestKey,
            result: null,
            error: cause instanceof Error && cause.message === 'Error HTTP 503'
              ? 'El catálogo no está disponible en este momento.'
              : 'No se pudo cargar el catálogo. Inténtalo de nuevo.',
          })
        }
      })

    return () => controller.abort()
  }, [query, page, requestKey])

  return (
    <section className="catalog-panel" aria-labelledby="catalog-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">Colección digital</span>
          <h2 id="catalog-title">Encuentra tu próximo recurso</h2>
        </div>
        {result && !loading && !error && (
          <span className="result-count">{result.totalItems} productos</span>
        )}
      </div>

      <form
        className="search-form"
        role="search"
        onSubmit={(event) => {
          event.preventDefault()
          onSearch(input.trim())
        }}
      >
        <label htmlFor="product-search">¿Qué te gustaría aprender?</label>
        <div className="search-controls">
          <input
            id="product-search"
            type="search"
            value={input}
            maxLength={100}
            placeholder="Busca un tema o producto"
            onChange={(event) => setInput(event.target.value)}
          />
          <button type="submit" className="primary">Buscar</button>
        </div>
      </form>

      {loading && <p className="feedback" role="status">Cargando productos…</p>}
      {error && <p className="error" role="alert">{error}</p>}

      {!loading && !error && result && (
        <>
          {result.items.length === 0 ? (
            <p className="feedback">No se encontraron productos con ese título.</p>
          ) : (
            <div className="product-grid">
              {result.items.map((product, index) => (
                <article className="product-card" key={product.id}>
                  <div className={`product-cover cover-${index % 4}`} aria-hidden="true">
                    <span className="cover-topline">DIGITAL / {String(index + 1 + page * 12).padStart(2, '0')}</span>
                    <span className="cover-initials">{product.title.trim().slice(0, 2).toUpperCase()}</span>
                    <span className="cover-bottomline">CREA A TU RITMO <span>↗</span></span>
                  </div>
                  <div className="product-card-body">
                    <span className="product-tag">Recurso digital</span>
                    <h3>{product.title}</h3>
                    <p className="product-price">{formatPrice(product)}</p>
                  </div>
                  <div className="product-actions">
                    <button type="button" className="secondary" onClick={() => onOpen(product.id)}>
                      Ver detalles
                    </button>
                    <button type="button" className="primary" onClick={() => onAdd(product)}>
                      Añadir <span aria-hidden="true">+</span>
                    </button>
                  </div>
                </article>
              ))}
            </div>
          )}

          {result.totalPages > 1 && (
            <nav className="pagination" aria-label="Páginas de productos">
              <button
                type="button"
                className="secondary"
                disabled={page === 0}
                onClick={() => onPageChange(page - 1)}
              >
                Anterior
              </button>
              <span>Página {page + 1} de {result.totalPages}</span>
              <button
                type="button"
                className="secondary"
                disabled={page + 1 >= result.totalPages}
                onClick={() => onPageChange(page + 1)}
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
