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
    <main>
      <h1>Ładowarki</h1>

      {chargerList.status === 'loading' && <p role="status">Pobieranie ładowarek…</p>}
      {chargerList.status === 'error' && (
        <p role="alert">Nie udało się pobrać ładowarek. Odśwież stronę, żeby spróbować ponownie.</p>
      )}
      {chargerList.status === 'success' && chargerList.chargers.length === 0 && (
        <p role="status">Brak ładowarek do wyświetlenia.</p>
      )}
      {chargerList.status === 'success' && chargerList.chargers.length > 0 && (
        <ul>
          {chargerList.chargers.map((charger) => (
            <li key={charger.id}>
              <strong>{charger.name}</strong> — {charger.location}
            </li>
          ))}
        </ul>
      )}
      <EnergySection />
      <ReservationsSection chargers={chargerList.status === 'success' ? chargerList.chargers : []} />
    </main>
  )
}

export default App
