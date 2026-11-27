export type Charger = { id: number; name: string; location: string }
export type BusyPeriod = { start: string; end: string }
export type Reservation = BusyPeriod & { id: number; chargerId: number }

export type NewReservation = { chargerId: number; start: string; hours: number }

type CreateResult =
  | { status: 'created'; reservation: Reservation }
  | { status: 'conflict' | 'invalid' | 'charger-not-found' }

const reservationsPath = '/api/reservations'

export async function readReservations(): Promise<Reservation[]> {
  const response = await fetch(reservationsPath, { credentials: 'same-origin' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.json()
}

export async function createReservation(input: NewReservation): Promise<CreateResult> {
  const response = await fetch(reservationsPath, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
  if (response.status === 409) return { status: 'conflict' }
  if (response.status === 400) return { status: 'invalid' }
  if (response.status === 404) return { status: 'charger-not-found' }
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  const reservation: Reservation = await response.json()
  return { status: 'created', reservation }
}

export async function cancelReservation(id: number): Promise<'cancelled' | 'not-found'> {
  const response = await fetch(`${reservationsPath}/${id}`, { method: 'DELETE', credentials: 'same-origin' })
  if (response.status === 404) return 'not-found'
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return 'cancelled'
}

export async function readAvailability(
  chargerId: number, from: string, to: string, signal: AbortSignal,
): Promise<BusyPeriod[]> {
  const range = new URLSearchParams({ from, to })
  const response = await fetch(`/api/chargers/${chargerId}/availability?${range}`, { signal })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.json()
}
