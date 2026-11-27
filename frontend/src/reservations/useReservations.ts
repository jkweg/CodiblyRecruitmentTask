import { useEffect, useRef, useState } from 'react'
import { cancelReservation, createReservation, readReservations } from './reservationApi'
import type { NewReservation, Reservation } from './reservationApi'

type OwnReservations =
  | { status: 'loading' | 'error' }
  | { status: 'ready'; reservations: Reservation[] }

type Pending = { kind: 'create' } | { kind: 'cancel'; id: number }
type Notice = { kind: 'error' | 'success'; text: string }

export function useReservations() {
  const [own, setOwn] = useState<OwnReservations>({ status: 'loading' })
  const [sessionAttempt, setSessionAttempt] = useState(0)
  const [calendarRevision, setCalendarRevision] = useState(0)
  const [pending, setPending] = useState<Pending | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const sessionRequest = useRef<Promise<Reservation[]> | null>(null)
  const mutationPending = useRef(false)

  useEffect(() => {
    let current = true
    // React can replay this effect before the server issues the owner cookie.
    sessionRequest.current ??= readReservations()
    sessionRequest.current.then(
      (reservations) => { if (current) setOwn({ status: 'ready', reservations }) },
      () => { if (current) setOwn({ status: 'error' }) },
    )
    return () => { current = false }
  }, [sessionAttempt])

  function retrySession() {
    sessionRequest.current = null
    setOwn({ status: 'loading' })
    setSessionAttempt((value) => value + 1)
  }

  function showError(text: string) {
    setNotice({ kind: 'error', text })
  }

  function beginMutation(operation: Pending) {
    if (mutationPending.current || own.status !== 'ready') return false
    // The ref locks immediately, including before React renders the disabled controls.
    mutationPending.current = true
    setPending(operation)
    setNotice(null)
    return true
  }

  function endMutation() {
    mutationPending.current = false
    setPending(null)
  }

  function updateReservations(update: (reservations: Reservation[]) => Reservation[], text: string) {
    setOwn((current) => current.status === 'ready'
      ? { status: 'ready', reservations: update(current.reservations) }
      : current)
    setCalendarRevision((value) => value + 1)
    setNotice({ kind: 'success', text })
  }

  async function reserve(input: NewReservation) {
    if (!beginMutation({ kind: 'create' })) return
    try {
      const result = await createReservation(input)
      switch (result.status) {
        case 'conflict':
          setCalendarRevision((value) => value + 1)
          showError('Ładowarka jest zajęta w tym terminie. Sprawdź kalendarz i wybierz inny termin.')
          return
        case 'invalid':
          showError('Sprawdź datę, godzinę rozpoczęcia i czas trwania rezerwacji.')
          return
        case 'charger-not-found':
          showError('Ta ładowarka jest niedostępna. Wybierz inną ładowarkę.')
          return
        case 'created':
          updateReservations(
            (list) => [...list, result.reservation].sort((a, b) => Date.parse(a.start) - Date.parse(b.start)),
            'Rezerwacja została zapisana.',
          )
      }
    } catch {
      showError('Nie udało się zapisać rezerwacji. Odśwież stronę i sprawdź listę przed ponowieniem.')
    } finally {
      endMutation()
    }
  }

  async function cancel(id: number) {
    if (!beginMutation({ kind: 'cancel', id })) return
    try {
      if (await cancelReservation(id) === 'not-found') {
        showError('Ta rezerwacja jest już niedostępna. Odśwież stronę, żeby pobrać aktualną listę.')
        return
      }
      updateReservations((list) => list.filter((reservation) => reservation.id !== id), 'Rezerwacja została anulowana.')
    } catch {
      showError('Nie udało się anulować rezerwacji. Odśwież stronę, żeby sprawdzić jej stan.')
    } finally {
      endMutation()
    }
  }

  return { own, pending, notice, calendarRevision, retrySession, showError, reserve, cancel }
}
