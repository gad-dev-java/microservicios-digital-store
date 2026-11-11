import { useEffect, useState } from 'react'
import { formatPrice, getProduct, type ProductDetail } from '../api/products'

type ProductDetailPageProps = {
  id: string
  onBack: () => void
  onAdd: (product: ProductDetail) => void
}

export function ProductDetailPage({ id, onBack, onAdd }: ProductDetailPageProps) {
  const [product, setProduct] = useState<ProductDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()

    getProduct(id, controller.signal)
      .then(setProduct)
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(cause instanceof Error && cause.message === 'Error HTTP 404'
            ? 'Este producto ya no está disponible.'
            : 'No se pudo abrir el producto. Inténtalo de nuevo.')
        }
      })

    return () => controller.abort()
  }, [id])

  return (
    <section className="catalog-panel detail-panel" aria-labelledby="detail-title">
      <button type="button" className="text-button" onClick={onBack}>
        ← Volver al catálogo
      </button>

      {!product && !error && <p className="feedback" role="status">Cargando detalle…</p>}
      {error && <p className="error" role="alert">{error}</p>}

      {product && (
        <>
          <span className="eyebrow">Producto digital</span>
          <h2 id="detail-title">{product.title}</h2>
          <p className="detail-description">{product.description}</p>
          <div className="detail-footer">
            <strong className="detail-price">{formatPrice(product)}</strong>
            <button type="button" className="primary" onClick={() => onAdd(product)}>
              Añadir al carrito
            </button>
          </div>
        </>
      )}
    </section>
  )
}
