export type Charger = { id: number; name: string; location: string }
export type BusyPeriod = { start: string; end: string }
export type Reservation = BusyPeriod & { id: number; chargerId: number }

export const reservationsPath = '/api/reservations'

export async function readReservations(): Promise<Reservation[]> {
  const response = await fetch(reservationsPath, { credentials: 'same-origin' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.json()
}

export async function readAvailability(
  chargerId: number, from: string, to: string, signal: AbortSignal,
): Promise<BusyPeriod[]> {
  const range = new URLSearchParams({ from, to })
  const response = await fetch(`/api/chargers/${chargerId}/availability?${range}`, { signal })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.json()
}
