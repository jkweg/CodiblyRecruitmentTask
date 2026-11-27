import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import ReservationCalendar from './ReservationCalendar'
import { readReservations, reservationsPath } from './reservationApi'
import type { Charger, Reservation } from './reservationApi'
import { isPast, londonDateTime, londonStart } from './reservationTime'
import './ReservationsSection.css'

type OwnReservations =
  | { status: 'loading' | 'error' }
  | { status: 'ready'; reservations: Reservation[] }

type Pending = { kind: 'create' } | { kind: 'cancel'; id: number }
type Notice = { kind: 'error' | 'success'; text: string }

const durationOptions = [1, 2, 3, 4, 5, 6]

export default function ReservationsSection({ chargers, chargerStatus }: {
  chargers: Charger[]
  chargerStatus: 'loading' | 'error' | 'success'
}) {
  const [selection, setSelection] = useState<number>()
  const chargerId = chargers.find(({ id }) => id === selection)?.id ?? chargers[0]?.id
  const [start, setStart] = useState('')
  const [hours, setHours] = useState(1)
  const [own, setOwn] = useState<OwnReservations>({ status: 'loading' })
  const [sessionAttempt, setSessionAttempt] = useState(0)
  const [calendarRevision, setCalendarRevision] = useState(0)
  const [pending, setPending] = useState<Pending | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const sessionRequest = useRef<Promise<Reservation[]> | null>(null)
  const mutationPending = useRef(false)

  useEffect(() => {
    let current = true
    // Reuse the first GET during React's effect replay so only one owner cookie is issued.
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

  async function reserve(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (chargerId === undefined || own.status !== 'ready' || mutationPending.current) return
    const plannedStart = londonStart(start)
    if (plannedStart.status === 'missing') {
      showError('Ta godzina nie istnieje w Londynie z powodu zmiany czasu. Wybierz inną godzinę.')
      return
    }
    if (plannedStart.status === 'ambiguous') {
      showError('Ta godzina występuje w Londynie dwukrotnie. Wybierz inną godzinę.')
      return
    }
    if (plannedStart.status !== 'valid' || isPast(plannedStart.instant)) {
      showError('Wybierz poprawną datę i godzinę rozpoczęcia w przyszłości.')
      return
    }
    if (!beginMutation({ kind: 'create' })) return

    try {
      const response = await fetch(reservationsPath, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chargerId, start: plannedStart.instant, hours }),
      })
      if (response.status === 409) {
        setCalendarRevision((value) => value + 1)
        showError('Ładowarka jest zajęta w tym terminie. Sprawdź kalendarz i wybierz inny termin.')
        return
      }
      if (response.status === 400) {
        showError('Sprawdź datę, godzinę rozpoczęcia i czas trwania rezerwacji.')
        return
      }
      if (response.status === 404) {
        showError('Ta ładowarka jest niedostępna. Wybierz inną ładowarkę.')
        return
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const reservation: Reservation = await response.json()
      updateReservations((list) => [...list, reservation].sort((a, b) => Date.parse(a.start) - Date.parse(b.start)),
        'Rezerwacja została zapisana.')
    } catch {
      showError('Nie udało się zapisać rezerwacji. Odśwież stronę i sprawdź listę przed ponowieniem.')
    } finally {
      endMutation()
    }
  }

  async function cancel(id: number) {
    if (!beginMutation({ kind: 'cancel', id })) return
    try {
      const response = await fetch(`${reservationsPath}/${id}`, { method: 'DELETE', credentials: 'same-origin' })
      if (response.status === 404) {
        showError('Ta rezerwacja jest już niedostępna. Odśwież stronę, żeby pobrać aktualną listę.')
        return
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      updateReservations((list) => list.filter((reservation) => reservation.id !== id), 'Rezerwacja została anulowana.')
    } catch {
      showError('Nie udało się anulować rezerwacji. Odśwież stronę, żeby sprawdzić jej stan.')
    } finally {
      endMutation()
    }
  }

  return (
    <section className="reservations-section" aria-labelledby="reservations-title">
      <div className="section-heading">
        <h2 id="reservations-title" tabIndex={-1}>Rezerwacje ładowarek</h2>
        <p>Daty i godziny wpisujesz i odczytujesz w czasie londyńskim (Europe/London).</p>
      </div>
      <div className="reservation-layout">
        <section className="panel reservation-form-card" aria-labelledby="reservation-form-title">
          <h3 id="reservation-form-title">Nowa rezerwacja</h3>
          {chargerStatus === 'loading' && <p role="status">Pobieranie ładowarek…</p>}
          {chargerStatus === 'error' && (
            <p role="alert">Nie udało się pobrać ładowarek. Odśwież stronę, żeby spróbować ponownie.</p>
          )}
          {chargerStatus === 'success' && chargers.length === 0 && <p role="status">Brak ładowarek do wyświetlenia.</p>}

          <form className="reservation-fields" onSubmit={reserve}>
            <label className="reservation-charger">Ładowarka do rezerwacji i kalendarza
              <select value={chargerId ?? ''} disabled={chargers.length === 0 || pending !== null}
                onChange={(event) => setSelection(Number(event.target.value))}>
                {chargers.length === 0 && <option value="">Wybierz ładowarkę</option>}
                {chargers.map((charger) => <option key={charger.id} value={charger.id}>{charger.name} — {charger.location}</option>)}
              </select>
            </label>
            <label>Początek rezerwacji
              <input type="datetime-local" required value={start} disabled={pending !== null}
                onChange={(event) => setStart(event.target.value)} />
            </label>
            <label>Czas rezerwacji
              <select value={hours} disabled={pending !== null} onChange={(event) => setHours(Number(event.target.value))}>
                {durationOptions.map((duration) => <option key={duration} value={duration}>{duration} h</option>)}
              </select>
            </label>
            <button type="submit" className="primary-button" disabled={own.status !== 'ready' || chargerId === undefined || pending !== null}>
              {pending?.kind === 'create' ? 'Zapisywanie…' : 'Zarezerwuj'}
            </button>
          </form>
          {notice && <p className={notice.kind === 'success' ? 'success-note' : undefined}
            role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p>}
        </section>
        <ReservationCalendar chargerId={chargerId} revision={calendarRevision} />
      </div>

      <section className="panel reservation-own" aria-labelledby="own-reservations-title">
        <h3 id="own-reservations-title">Moje rezerwacje</h3>
        {own.status === 'loading' && <p role="status">Pobieranie własnych rezerwacji…</p>}
        {own.status === 'error' && (
          <>
            <p role="alert">Nie udało się pobrać własnych rezerwacji. Spróbuj ponownie, żeby odblokować zapis.</p>
            <button type="button" onClick={retrySession}>Ponów odczyt rezerwacji</button>
          </>
        )}
        {own.status === 'ready' && (own.reservations.length === 0 ? <p>Nie masz rezerwacji.</p> : (
          <ul className="reservation-list">
            {own.reservations.map((reservation) => (
              <li className="reservation-card" key={reservation.id}>
                <strong>{chargers.find(({ id }) => id === reservation.chargerId)?.name ?? `Ładowarka nr ${reservation.chargerId}`}</strong>
                <p>Początek <time dateTime={reservation.start}>{londonDateTime.format(new Date(reservation.start))}</time></p>
                <p>Koniec <time dateTime={reservation.end}>{londonDateTime.format(new Date(reservation.end))}</time></p>
                <button type="button" disabled={pending !== null} onClick={() => void cancel(reservation.id)}>
                  {pending?.kind === 'cancel' && pending.id === reservation.id
                    ? 'Anulowanie…' : `Anuluj rezerwację nr ${reservation.id}`}
                </button>
              </li>
            ))}
          </ul>
        ))}
      </section>
    </section>
  )
}
