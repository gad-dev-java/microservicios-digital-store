import { authenticatedFetch } from '../../../app/authenticatedFetch'

export type PaymentAttemptReview = {
  id: string
  orderId: string
  providerOrderId: string | null
  status: string
  providerStatus: string | null
  providerStatusDetail: string | null
  reviewReason: string | null
  resolutionReason: string | null
  resolvedBy: string | null
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
}

type ApiProblem = {
  detail?: string
}

async function requireSuccess(response: Response): Promise<void> {
  if (response.ok) return

  const problem = await response.json().catch(() => null) as ApiProblem | null
  if (response.status === 401) throw new Error('La sesión expiró. Inicia sesión de nuevo.')
  if (response.status === 403) throw new Error('Tu cuenta no tiene permiso para revisar pagos.')
  if (response.status === 404) throw new Error('El intento de pago ya no existe.')
  if (response.status === 409) throw new Error(problem?.detail ?? 'El intento cambió de estado. Actualiza el historial.')
  if (response.status === 503) throw new Error('No se pudo consultar Mercado Pago. Inténtalo más tarde.')
  throw new Error(problem?.detail ?? `No se pudo completar la operación (HTTP ${response.status}).`)
}

export async function listPaymentAttempts(orderId: string, signal?: AbortSignal): Promise<PaymentAttemptReview[]> {
  const response = await authenticatedFetch(
    `/api/admin/orders/${encodeURIComponent(orderId)}/payment-attempts`,
    { signal },
  )
  await requireSuccess(response)
  return await response.json() as PaymentAttemptReview[]
}

export async function reconcilePaymentAttempt(attemptId: string): Promise<void> {
  const response = await authenticatedFetch(
    `/api/admin/payment-attempts/${encodeURIComponent(attemptId)}/reconcile`,
    { method: 'POST' },
  )
  await requireSuccess(response)
}

export async function resolvePaymentAttempt(attemptId: string, reason: string): Promise<void> {
  const response = await authenticatedFetch(
    `/api/admin/payment-attempts/${encodeURIComponent(attemptId)}/resolve`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        resolution: 'ERROR',
        reason,
        acknowledgeLatePaymentRisk: true,
      }),
    },
  )
  await requireSuccess(response)
}
