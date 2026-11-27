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
const fuelColors: Record<string, string> = {
  wind: '#247653', nuclear: '#6854a2', biomass: '#79a84e', hydro: '#307da0',
  solar: '#d2a231', gas: '#35455a', coal: '#724936', imports: '#c37a58', other: '#8894a1',
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

function MixDetails({ day }: { day: DailyEnergyMix }) {
  const fuels = Object.entries(day.averageMix).map(([fuel, share]) => ({
    fuel, share, color: fuelColors[fuel] ?? '#8894a1',
  }))
  const total = fuels.reduce((sum, fuel) => sum + fuel.share, 0)
  if (day.cleanShare === null || total <= 0) {
    return <div className="panel mix-details"><p>Brak danych o miksie dla wybranego dnia.</p></div>
  }

  let accumulatedShare = 0
  const segments = fuels.filter(({ share }) => share > 0).map(({ color, share }) => {
    const start = accumulatedShare / total * 100
    accumulatedShare += share
    return `${color} ${start}% ${accumulatedShare / total * 100}%`
  })
  const ring = segments.length === 1
    ? fuels.find(({ share }) => share > 0)?.color
    : `conic-gradient(${segments.join(', ')})`

  return (
    <section className="panel mix-details" aria-labelledby="mix-detail-title">
      <h3 id="mix-detail-title">Źródła energii <time dateTime={day.date}>{calendarDate.format(new Date(day.date))}</time></h3>
      <div className="mix-details-content">
        <div role="img" aria-label={`Wykres źródeł energii. Czysta energia ${formatPercentage(day.cleanShare)}`}
          className="mix-ring" style={{ background: ring }}>
          <div aria-hidden="true"><strong>{formatPercentage(day.cleanShare)}</strong><span>Czysta energia</span></div>
        </div>
        <dl className="energy-fuels" aria-label="Udziały źródeł energii">
          {fuels.map(({ fuel, share, color }) => (
            <div key={fuel}>
              <dt><span aria-hidden="true" style={{ backgroundColor: color }} />{fuelLabels[fuel] ?? fuel}</dt>
              <dd>{formatPercentage(share)}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  )
}

function DailyMixComparison() {
  const [mix, setMix] = useState<MixState>({ status: 'loading' })
  const [selectedDate, setSelectedDate] = useState<string>()

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
  const selected = mix.days.find((day) => day.date === selectedDate) ?? rankedDays[0] ?? mix.days[0]

  return (
    <div className="energy-comparison">
      {leaders.length > 0 && mix.days.some((day) => !day.complete) && (
        <p className="data-note">Porównanie opiera się na częściowych danych.</p>
      )}
      <div className="energy-days" role="group" aria-label="Wybierz dzień prognozy">
        {mix.days.map((day, index) => (
          <button type="button" className="energy-card" key={day.date}
            aria-pressed={selected?.date === day.date} onClick={() => setSelectedDate(day.date)}>
            <strong className="day-name">{dayLabels[index]}</strong>
            <time dateTime={day.date}>{calendarDate.format(new Date(day.date))}</time>
            {day.cleanShare === null ? (
              <span className="day-share day-empty">Brak danych o miksie.</span>
            ) : (
              <strong className="day-share">{formatPercentage(day.cleanShare)}</strong>
            )}
            {leaders.includes(day) && (
              <span className="day-leader">{leaders.length > 1 ? 'Najwyższy udział ex aequo' : 'Najwyższy udział'}</span>
            )}
            {!day.complete && <span className="day-coverage">Pokrycie {day.availableSlots}/{day.expectedSlots} slotów</span>}
          </button>
        ))}
      </div>
      {selected && <MixDetails day={selected} />}
    </div>
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
    <section className="panel energy-search" aria-labelledby="energy-search-title">
      <h3 id="energy-search-title" tabIndex={-1}>Najczystsze okno ładowania</h3>
      <p>Wyszukiwanie obejmuje jutro i pojutrze według czasu londyńskiego (Europe/London).</p>
      <form onSubmit={findWindow}>
        <label htmlFor="charging-hours">Czas ładowania</label>
        <select id="charging-hours" value={hours} onChange={(event) => changeDuration(Number(event.target.value))}>
          {durationOptions.map((duration) => <option key={duration} value={duration}>{duration} h</option>)}
        </select>
        <button className="primary-button" type="submit" disabled={search.status === 'loading'}>Znajdź okno</button>
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
      <div className="section-heading">
        <h2 id="energy-title" tabIndex={-1}>Miks energii w Wielkiej Brytanii</h2>
        <p>Porównaj udział czystej energii dziś, jutro i pojutrze.</p>
      </div>
      <div className="energy-layout">
        <div><DailyMixComparison /></div>
        <ChargingWindowSearch />
      </div>
    </section>
  )
}
