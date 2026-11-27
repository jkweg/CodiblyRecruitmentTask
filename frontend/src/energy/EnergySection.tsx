import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import './EnergySection.css'

type DailyEnergyMix = {
  date: string
  averageMix: Record<string, number>
  cleanShare: number | null
  availableSlots: number
  expectedSlots: number
  complete: boolean
}

type ChargingWindow = {
  start: string
  end: string
  averageCleanShare: number
}

type MixState =
  | { status: 'loading' | 'error' }
  | { status: 'success'; days: DailyEnergyMix[] }

type SearchState =
  | { status: 'idle' | 'loading' | 'empty' | 'error' }
  | { status: 'success'; window: ChargingWindow }

const dayLabels = ['Dziś', 'Jutro', 'Pojutrze']
const durationOptions = [1, 2, 3, 4, 5, 6]
const fuelLabels: Record<string, string> = {
  biomass: 'Biomasa', coal: 'Węgiel', gas: 'Gaz', hydro: 'Woda', imports: 'Import',
  nuclear: 'Energia jądrowa', other: 'Inne', solar: 'Słońce', wind: 'Wiatr',
}
const calendarDate = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long', timeZone: 'UTC' })
const londonDateTime = new Intl.DateTimeFormat('pl-PL', {
  timeZone: 'Europe/London', year: 'numeric', month: 'long', day: 'numeric',
  hour: '2-digit', minute: '2-digit', timeZoneName: 'shortOffset',
})
const percentage = new Intl.NumberFormat('pl-PL', { style: 'percent', maximumFractionDigits: 2 })

function formatPercentage(value: number) {
  return percentage.format(value / 100)
}

function DailyMixComparison() {
  const [mix, setMix] = useState<MixState>({ status: 'loading' })

  useEffect(() => {
    const request = new AbortController()
    async function loadMix() {
      try {
        const response = await fetch('/api/energy/mix', { signal: request.signal })
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        const days: DailyEnergyMix[] = await response.json()
        if (!request.signal.aborted) setMix({ status: 'success', days })
      } catch {
        if (!request.signal.aborted) setMix({ status: 'error' })
      }
    }
    void loadMix()
    return () => request.abort()
  }, [])

  if (mix.status === 'loading') return <p role="status">Pobieranie miksu energii…</p>
  if (mix.status !== 'success') {
    return <p role="alert">Nie udało się pobrać miksu energii. Odśwież stronę, żeby spróbować ponownie.</p>
  }

  const rankedDays = mix.days.filter((day) => day.cleanShare !== null)
  const highestShare = Math.max(...rankedDays.map((day) => day.cleanShare ?? 0))
  const leaders = rankedDays.filter((day) => day.cleanShare === highestShare)

  return (
    <>
      {leaders.length > 0 && mix.days.some((day) => !day.complete) && (
        <p>Porównanie opiera się na częściowych danych.</p>
      )}
      <div className="energy-days">
        {mix.days.map((day, index) => (
          <article className="energy-card" key={day.date}>
            <h3>{dayLabels[index]} <time dateTime={day.date}>{calendarDate.format(new Date(day.date))}</time></h3>
            {day.cleanShare === null ? (
              <p>Brak danych o miksie.</p>
            ) : (
              <p className="energy-share">Czysta energia <strong>{formatPercentage(day.cleanShare)}</strong></p>
            )}
            {leaders.includes(day) && (
              <p>{leaders.length > 1 ? 'Najwyższy udział ex aequo' : 'Najwyższy udział'}</p>
            )}
            {!day.complete && <p>Pokrycie prognozy {day.availableSlots}/{day.expectedSlots} półgodzinnych slotów.</p>}
            {Object.keys(day.averageMix).length > 0 && (
              <dl className="energy-fuels">
                {Object.entries(day.averageMix).map(([fuel, share]) => (
                  <div key={fuel}>
                    <dt>{fuelLabels[fuel] ?? fuel}</dt><dd>{formatPercentage(share)}</dd>
                  </div>
                ))}
              </dl>
            )}
          </article>
        ))}
      </div>
    </>
  )
}

function ChargingWindowSearch() {
  const [hours, setHours] = useState(1)
  const [search, setSearch] = useState<SearchState>({ status: 'idle' })
  const activeRequest = useRef<AbortController | null>(null)

  useEffect(() => () => activeRequest.current?.abort(), [])

  function changeDuration(value: number) {
    activeRequest.current?.abort()
    activeRequest.current = null
    setHours(value)
    setSearch({ status: 'idle' })
  }

  async function findWindow(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (activeRequest.current) return

    const request = new AbortController()
    activeRequest.current = request
    setSearch({ status: 'loading' })
    try {
      const response = await fetch(`/api/energy/best-window?hours=${hours}`, { signal: request.signal })
      if (response.status === 204) {
        if (!request.signal.aborted) setSearch({ status: 'empty' })
        return
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const window: ChargingWindow = await response.json()
      if (!request.signal.aborted) setSearch({ status: 'success', window })
    } catch {
      if (!request.signal.aborted) setSearch({ status: 'error' })
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }

  return (
    <section className="energy-search" aria-labelledby="energy-search-title">
      <h3 id="energy-search-title">Najczystsze okno ładowania</h3>
      <p>Wyszukiwanie obejmuje jutro i pojutrze według czasu londyńskiego (Europe/London).</p>
      <form onSubmit={findWindow}>
        <label htmlFor="charging-hours">Czas ładowania</label>
        <select id="charging-hours" value={hours} onChange={(event) => changeDuration(Number(event.target.value))}>
          {durationOptions.map((duration) => <option key={duration} value={duration}>{duration} h</option>)}
        </select>
        <button type="submit" disabled={search.status === 'loading'}>Znajdź okno</button>
      </form>
      {search.status === 'loading' && <p role="status">Szukam okna ładowania…</p>}
      {search.status === 'empty' && (
        <p role="status">Brak ciągłego okna o tej długości w dostępnej prognozie. Spróbuj wybrać krótszy czas.</p>
      )}
      {search.status === 'error' && <p role="alert">Nie udało się znaleźć okna. Spróbuj ponownie.</p>}
      {search.status === 'success' && (
        <div className="energy-result" role="status">
          <dl>
            <div><dt>Początek</dt><dd><time dateTime={search.window.start}>{londonDateTime.format(new Date(search.window.start))}</time></dd></div>
            <div><dt>Koniec</dt><dd><time dateTime={search.window.end}>{londonDateTime.format(new Date(search.window.end))}</time></dd></div>
            <div><dt>Średni udział czystej energii</dt><dd>{formatPercentage(search.window.averageCleanShare)}</dd></div>
          </dl>
        </div>
      )}
    </section>
  )
}

export default function EnergySection() {
  return (
    <section className="energy-section" aria-labelledby="energy-title">
      <h2 id="energy-title">Miks energii w Wielkiej Brytanii</h2>
      <DailyMixComparison />
      <ChargingWindowSearch />
    </section>
  )
}
