import { useEffect, useState } from 'react'
import { readAvailability } from './reservationApi'
import type { BusyPeriod } from './reservationApi'
import { addCalendarDays, calendarDay, londonMidnight, londonTime, londonToday } from './reservationTime'

type CalendarState =
  | { status: 'loading' | 'error' }
  | { status: 'success'; periods: BusyPeriod[] }

function CalendarDays({ chargerId, date, days }: { chargerId: number; date: string; days: number }) {
  const [calendar, setCalendar] = useState<CalendarState>({ status: 'loading' })

  useEffect(() => {
    const request = new AbortController()
    async function load() {
      try {
        const periods = await readAvailability(chargerId, londonMidnight(date),
          londonMidnight(addCalendarDays(date, days)), request.signal)
        if (!request.signal.aborted) setCalendar({ status: 'success', periods })
      } catch {
        if (!request.signal.aborted) setCalendar({ status: 'error' })
      }
    }
    void load()
    return () => request.abort()
  }, [chargerId, date, days])

  if (calendar.status === 'loading') return <p role="status">Pobieranie zajętości…</p>
  if (calendar.status !== 'success') {
    return <p role="alert">Nie udało się pobrać zajętości. Spróbuj odświeżyć kalendarz.</p>
  }

  return (
    <div className="reservation-days">
      {Array.from({ length: days }, (_, index) => {
        const day = addCalendarDays(date, index)
        const from = Date.parse(londonMidnight(day))
        const to = Date.parse(londonMidnight(addCalendarDays(day, 1)))
        const periods = calendar.periods.filter(({ start, end }) => Date.parse(start) < to && Date.parse(end) > from)
        return (
          <article className="reservation-card" key={day}>
            <h4><time dateTime={day}>{calendarDay.format(new Date(day))}</time></h4>
            {periods.length === 0 ? <p>Brak rezerwacji.</p> : (
              <ul>
                {periods.map(({ start, end }) => {
                  const visibleStart = new Date(Math.max(from, Date.parse(start)))
                  const visibleEnd = new Date(Math.min(to, Date.parse(end)))
                  return (
                    <li key={`${start}/${end}`}>
                      <time dateTime={visibleStart.toISOString()}>{londonTime.format(visibleStart)}</time>
                      {' – '}
                      <time dateTime={visibleEnd.toISOString()}>{londonTime.format(visibleEnd)}</time>
                      {Date.parse(start) < from && <small> Początek w poprzednim dniu.</small>}
                      {Date.parse(end) > to && <small> Koniec w kolejnym dniu.</small>}
                    </li>
                  )
                })}
              </ul>
            )}
          </article>
        )
      })}
    </div>
  )
}

export default function ReservationCalendar({ chargerId, revision }: { chargerId?: number; revision: number }) {
  const [date, setDate] = useState(londonToday)
  const [days, setDays] = useState(1)
  const [refresh, setRefresh] = useState(0)
  const validDate = /^\d{4}-\d{2}-\d{2}$/.test(date) && !date.startsWith('0000')

  return (
    <section aria-labelledby="reservation-calendar-title">
      <h3 id="reservation-calendar-title">Kalendarz zajętości</h3>
      <div className="reservation-fields">
        <label>Data w kalendarzu <input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>
        <label>Zakres kalendarza
          <select value={days} onChange={(event) => setDays(Number(event.target.value))}>
            <option value={1}>Dzień</option>
            <option value={7}>7 dni od wybranej daty</option>
          </select>
        </label>
        <button type="button" disabled={chargerId === undefined || !validDate}
          onClick={() => setRefresh((value) => value + 1)}>Odśwież kalendarz</button>
      </div>
      {chargerId === undefined && <p>Wybierz ładowarkę, żeby zobaczyć zajętość.</p>}
      {chargerId !== undefined && !validDate && <p>Wybierz datę kalendarza.</p>}
      {chargerId !== undefined && validDate && (
        <CalendarDays key={`${chargerId}/${date}/${days}/${revision}/${refresh}`} chargerId={chargerId} date={date} days={days} />
      )}
    </section>
  )
}
