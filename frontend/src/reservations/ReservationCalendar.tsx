import { useEffect, useState } from 'react'
import { readAvailability } from './reservationApi'
import type { BusyPeriod } from './reservationApi'
import { addCalendarDays, calendarDay, londonMidnight, londonStart, londonTime, londonToday } from './reservationTime'

type CalendarState =
  | { status: 'loading' | 'error' }
  | { status: 'success'; periods: BusyPeriod[] }

const trackHours = ['06', '12', '18']

function ClockTime({ instant }: { instant: Date }) {
  // Split the formatted London time so the hour reads first and the offset stays visible but quieter.
  const parts = londonTime.formatToParts(instant)
  const offset = parts.find(({ type }) => type === 'timeZoneName')?.value
  const clock = parts.filter(({ type }) => type !== 'timeZoneName').map(({ value }) => value).join('').trim()
  return (
    <time dateTime={instant.toISOString()}>
      <span className="busy-clock">{clock}</span>
      {offset && <> <span className="busy-offset">{offset}</span></>}
    </time>
  )
}

function DayHeading({ day }: { day: string }) {
  const parts = calendarDay.formatToParts(new Date(day))
  const weekday = parts.find(({ type }) => type === 'weekday')?.value
  const date = parts.filter(({ type }) => type === 'day' || type === 'month').map(({ value }) => value).join(' ')
  return (
    <h4>
      <time dateTime={day}>
        <span className="calendar-weekday">{weekday}</span> <span className="calendar-date">{date}</span>
      </time>
    </h4>
  )
}

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
    <div className={days === 1 ? 'calendar-days calendar-days-single' : 'calendar-days'}>
      {Array.from({ length: days }, (_, index) => {
        const day = addCalendarDays(date, index)
        const from = Date.parse(londonMidnight(day))
        const to = Date.parse(londonMidnight(addCalendarDays(day, 1)))
        const position = (instant: number) => `${(instant - from) / (to - from) * 100}%`
        // Place hour marks at real London wall-clock hours, so 23- and 25-hour days stay accurate.
        const ticks = trackHours.flatMap((hour) => {
          const tick = londonStart(`${day}T${hour}:00`)
          return tick.status === 'valid' ? [{ hour, left: position(Date.parse(tick.instant)) }] : []
        })
        const periods = calendar.periods.filter(({ start, end }) => Date.parse(start) < to && Date.parse(end) > from)
        return (
          <article className={periods.length === 0 ? 'calendar-day is-free' : 'calendar-day is-busy'} key={day}>
            <header className="calendar-day-head">
              <DayHeading day={day} />
              {periods.length > 0 && <span className="calendar-count">Zajęte: {periods.length}</span>}
            </header>
            <div className="calendar-day-body">
              <div className="calendar-track" aria-hidden="true">
                <div className="calendar-track-bar">
                  {ticks.map(({ hour, left }) => <span className="calendar-tick" key={hour} style={{ left }} />)}
                  {periods.map(({ start, end }) => {
                    const left = Math.max(from, Date.parse(start))
                    const right = Math.min(to, Date.parse(end))
                    return <span className="calendar-segment" key={`${start}/${end}`}
                      style={{ left: position(left), width: `${(right - left) / (to - from) * 100}%` }} />
                  })}
                </div>
                <div className="calendar-track-labels">
                  <span style={{ left: '0%' }}>00</span>
                  {ticks.map(({ hour, left }) => <span key={hour} style={{ left }}>{hour}</span>)}
                  <span style={{ left: '100%' }}>24</span>
                </div>
              </div>
              {periods.length === 0 ? <p className="calendar-free">Brak rezerwacji.</p> : (
                <ul className="busy-list">
                  {periods.map(({ start, end }) => {
                    const visibleStart = new Date(Math.max(from, Date.parse(start)))
                    const visibleEnd = new Date(Math.min(to, Date.parse(end)))
                    return (
                      <li className="busy-period" key={`${start}/${end}`}>
                        <span className="busy-range">
                          <ClockTime instant={visibleStart} />
                          <span aria-hidden="true" className="busy-dash">–</span>
                          <span className="visually-hidden">do</span>
                          <ClockTime instant={visibleEnd} />
                        </span>
                        {Date.parse(start) < from && <small className="busy-note">Początek w poprzednim dniu.</small>}
                        {Date.parse(end) > to && <small className="busy-note">Koniec w kolejnym dniu.</small>}
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
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
    <section className="panel reservation-calendar" aria-labelledby="reservation-calendar-title">
      <h3 id="reservation-calendar-title">Kalendarz zajętości</h3>
      <div className="reservation-fields calendar-controls">
        <label>Data w kalendarzu <input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>
        <label>Zakres kalendarza
          <select value={days} onChange={(event) => setDays(Number(event.target.value))}>
            <option value={1}>Dzień</option>
            <option value={7}>7 dni od wybranej daty</option>
          </select>
        </label>
        <button type="button" className="secondary-button" disabled={chargerId === undefined || !validDate}
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
