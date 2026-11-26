import { useEffect, useState } from 'react'
import './App.css'

type Charger = {
  id: number
  name: string
  location: string
}

function App() {
  const [chargers, setChargers] = useState<Charger[]>([])
  const [hasError, setHasError] = useState(false)

  useEffect(() => {
    const controller = new AbortController()

    fetch('/api/chargers', { signal: controller.signal })
      .then((response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`)
        }
        return response.json()
      })
      .then((data: Charger[]) => setChargers(data))
      .catch(() => {
        if (!controller.signal.aborted) {
          setHasError(true)
        }
      })

    return () => controller.abort()
  }, [])

  return (
    <main>
      <h1>Ładowarki</h1>

      {hasError ? (
        <p role="alert">Nie udało się pobrać ładowarek.</p>
      ) : (
        <ul>
          {chargers.map((charger) => (
            <li key={charger.id}>
              <strong>{charger.name}</strong> — {charger.location}
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}

export default App