import { useEffect, useState, type FormEvent } from 'react'
import {
  listPaymentAttempts,
  reconcilePaymentAttempt,
  resolvePaymentAttempt,
  type PaymentAttemptReview,
} from './api/paymentReviews'

type Props = {
  orderId: string
  onUpdated: () => void
}

const statusLabels: Record<string, string> = {
  CREATING: 'Preparando pago',
  PENDING: 'Pendiente',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  EXPIRED: 'Expirado',
  ERROR: 'Cerrado para nuevo intento',
  REVIEW_REQUIRED: 'En revisión',
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('es-PE', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function AdminPaymentReviewPanel({ orderId, onUpdated }: Props) {
  const [attempts, setAttempts] = useState<PaymentAttemptReview[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    void listPaymentAttempts(orderId, controller.signal)
      .then((data) => setAttempts(data))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setLoadError(error instanceof Error ? error.message : 'No se pudo cargar el historial.')
        }
      })
    return () => controller.abort()
  }, [orderId])

  async function runAction(attemptId: string, action: () => Promise<void>, successMessage: string) {
    if (busyId !== null) return false
    setBusyId(attemptId)
    setActionError(null)
    setMessage(null)
    try {
      await action()
      setAttempts(await listPaymentAttempts(orderId))
      onUpdated()
      setMessage(successMessage)
      return true
    } catch (error) {
      setActionError(error instanceof Error ? error.message : 'No se pudo completar la operación.')
      // Un conflicto puede indicar que un webhook cambió el estado mientras se revisaba.
      try {
        setAttempts(await listPaymentAttempts(orderId))
        onUpdated()
      } catch {
        // Conservamos el error de la operación original.
      }
      return false
    } finally {
      setBusyId(null)
    }
  }

  function handleResolve(event: FormEvent<HTMLFormElement>, attemptId: string) {
    event.preventDefault()
    const trimmedReason = reason.trim()
    if (!trimmedReason || trimmedReason.length > 1000 || !acknowledged) return

    void runAction(
      attemptId,
      () => resolvePaymentAttempt(attemptId, trimmedReason),
      'Intento cerrado. El comprador ya puede iniciar otro checkout.',
    ).then((succeeded) => {
      if (succeeded) {
        setReason('')
        setAcknowledged(false)
      }
    })
  }

  return (
    <section className="payment-review-panel" aria-label={`Intentos del pedido ${orderId}`}>
      <h3>Historial de intentos de pago</h3>
      {attempts === null && !loadError && <p role="status">Cargando intentos…</p>}
      {loadError && <p className="error" role="alert">{loadError}</p>}
      {actionError && <p className="error" role="alert">{actionError}</p>}
      {message && <p className="success" role="status">{message}</p>}
      {attempts?.length === 0 && <p>Este pedido aún no tiene intentos de pago.</p>}

      {attempts && attempts.length > 0 && (
        <ol className="payment-review-list">
          {attempts.map((attempt) => {
            const inReview = attempt.status === 'REVIEW_REQUIRED'
            const canResolve = inReview
              && attempt.reviewReason === 'UNRESOLVED_TIMEOUT'
              && attempt.providerOrderId === null

            return (
              <li key={attempt.id} className="payment-review-item">
                <p><strong>Intento {attempt.id}</strong></p>
                <p>Estado: {statusLabels[attempt.status] ?? attempt.status}</p>
                <p>Creado: {formatDate(attempt.createdAt)}</p>
                <p>Actualizado: {formatDate(attempt.updatedAt)}</p>
                {attempt.providerOrderId && <p>Orden en Mercado Pago: {attempt.providerOrderId}</p>}
                {attempt.providerStatus && (
                  <p>Proveedor: {attempt.providerStatus}
                    {attempt.providerStatusDetail ? ` / ${attempt.providerStatusDetail}` : ''}
                  </p>
                )}
                {attempt.reviewReason && <p>Motivo de revisión: {attempt.reviewReason}</p>}
                {attempt.resolvedAt && <p>Resuelto: {formatDate(attempt.resolvedAt)}</p>}
                {attempt.resolvedBy && <p>Resuelto por: {attempt.resolvedBy}</p>}
                {attempt.resolutionReason && <p>Motivo administrativo: {attempt.resolutionReason}</p>}

                {inReview && (
                  <button
                    type="button"
                    className="secondary"
                    disabled={busyId !== null}
                    onClick={() => void runAction(
                      attempt.id,
                      () => reconcilePaymentAttempt(attempt.id),
                      'Consulta terminada. Revisa el estado actualizado.',
                    )}
                  >
                    {busyId === attempt.id ? 'Consultando…' : 'Consultar Mercado Pago'}
                  </button>
                )}

                {canResolve && (
                  <form className="payment-review-form" onSubmit={(event) => handleResolve(event, attempt.id)}>
                    <label>
                      Motivo para cerrar el intento
                      <textarea
                        required
                        maxLength={1000}
                        rows={3}
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                        disabled={busyId !== null}
                      />
                    </label>
                    <label className="payment-review-confirmation">
                      <input
                        type="checkbox"
                        checked={acknowledged}
                        onChange={(event) => setAcknowledged(event.target.checked)}
                        disabled={busyId !== null}
                      />
                      Revisé el historial del proveedor y acepto el riesgo de un pago tardío.
                    </label>
                    <button
                      type="submit"
                      className="secondary"
                      disabled={busyId !== null || !reason.trim() || !acknowledged}
                    >
                      Cerrar y permitir otro pago
                    </button>
                  </form>
                )}
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}
