import { useEffect, useState } from 'react'
import EnergySection from './energy/EnergySection'
import ReservationsSection from './reservations/ReservationsSection'
import type { Charger } from './reservations/reservationApi'
import './App.css'

type ChargerListState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'success'; chargers: Charger[] }

function App() {
  const [chargerList, setChargerList] = useState<ChargerListState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()

    fetch('/api/chargers', { signal: controller.signal })
      .then((response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`)
        }
        return response.json()
      })
      .then((chargers: Charger[]) => {
        if (!Array.isArray(chargers)) {
          throw new Error('Expected a list of chargers')
        }
        if (!controller.signal.aborted) {
          setChargerList({ status: 'success', chargers })
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setChargerList({ status: 'error' })
        }
      })

    return () => controller.abort()
  }, [])

  return (
    <>
      <header className="planner-header">
        <div className="planner-header-inner">
          <h1><span aria-hidden="true" className="planner-mark">ϟ</span>Planer ładowania</h1>
          <nav aria-label="Sekcje planera">
            <a href="#energy-title">Miks energii</a>
            <a href="#energy-search-title">Czyste okno</a>
            <a href="#reservations-title">Rezerwacje</a>
          </nav>
        </div>
      </header>
      <main className="planner-main">
        <EnergySection />
        <ReservationsSection
          chargers={chargerList.status === 'success' ? chargerList.chargers : []}
          chargerStatus={chargerList.status}
        />
      </main>
    </>
  )
}

export default App
