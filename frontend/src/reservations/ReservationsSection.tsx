import { useState } from 'react'
import type { FormEvent } from 'react'
import ReservationCalendar from './ReservationCalendar'
import type { Charger } from './reservationApi'
import { isPast, londonDateTime, londonStart } from './reservationTime'
import { useReservations } from './useReservations'
import './ReservationsSection.css'

const durationOptions = [1, 2, 3, 4, 5, 6]

export default function ReservationsSection({ chargers, chargerStatus }: {
  chargers: Charger[]
  chargerStatus: 'loading' | 'error' | 'success'
}) {
  const [selection, setSelection] = useState<number>()
  const chargerId = chargers.find(({ id }) => id === selection)?.id ?? chargers[0]?.id
  const [start, setStart] = useState('')
  const [hours, setHours] = useState(1)
  const { own, pending, notice, calendarRevision, retrySession, showError, reserve, cancel } = useReservations()

  function submitReservation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (chargerId === undefined || own.status !== 'ready' || pending !== null) return
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
    void reserve({ chargerId, start: plannedStart.instant, hours })
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

          <form className="reservation-fields" onSubmit={submitReservation}>
            <label className="reservation-charger">Ładowarka do rezerwacji i kalendarza
              <select id="reservation-charger" value={chargerId ?? ''} disabled={chargers.length === 0 || pending !== null}
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
            <button type="button" className="secondary-button" onClick={retrySession}>Ponów odczyt rezerwacji</button>
          </>
        )}
        {own.status === 'ready' && (own.reservations.length === 0 ? <p>Nie masz rezerwacji.</p> : (
          <ul className="reservation-list">
            {own.reservations.map((reservation) => (
              <li className="reservation-card own-reservation" key={reservation.id}>
                <strong>{chargers.find(({ id }) => id === reservation.chargerId)?.name ?? `Ładowarka nr ${reservation.chargerId}`}</strong>
                <p><span className="own-label">Początek</span> <time dateTime={reservation.start}>{londonDateTime.format(new Date(reservation.start))}</time></p>
                <p><span className="own-label">Koniec</span> <time dateTime={reservation.end}>{londonDateTime.format(new Date(reservation.end))}</time></p>
                <button type="button" className="danger-button" disabled={pending !== null} onClick={() => void cancel(reservation.id)}>
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
