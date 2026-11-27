import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../App'

const chargers = [{ id: 1, name: 'Ładowarka testowa', location: 'Kraków' }]
const mix = [
  { date: '2026-10-24', averageMix: { wind: 30, gas: 70 }, cleanShare: 30, availableSlots: 48, expectedSlots: 48, complete: true },
  { date: '2026-10-25', averageMix: { wind: 65 }, cleanShare: 65, availableSlots: 30, expectedSlots: 50, complete: false },
  { date: '2026-10-26', averageMix: {}, cleanShare: null, availableSlots: 0, expectedSlots: 48, complete: false },
]
const windowResult = {
  start: '2026-10-25T00:30:00Z',
  end: '2026-10-25T02:30:00Z',
  averageCleanShare: 75.5,
}

type BestWindowResponder = (url: string, signal?: AbortSignal | null) => Response | Promise<Response>

function mockApi({
  dailyMix = () => Response.json(mix),
  bestWindow = () => Response.json(windowResult),
}: { dailyMix?: () => Response; bestWindow?: BestWindowResponder } = {}) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/chargers') return Response.json(chargers)
    if (url === '/api/reservations' || url.startsWith('/api/chargers/1/availability?')) return Response.json([])
    if (url === '/api/energy/mix') return dailyMix()
    if (url.startsWith('/api/energy/best-window?')) return bestWindow(url, options?.signal)
    throw new Error(`Unexpected request: ${url}`)
  }))
}

function chooseHours(hours: string) {
  fireEvent.change(screen.getByRole('combobox', { name: 'Czas ładowania' }), { target: { value: hours } })
}

function submitSearch() {
  fireEvent.click(screen.getByRole('button', { name: 'Znajdź okno' }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

test('compares all three days and identifies a partial day with the highest available share', async () => {
  mockApi()
  render(<App />)

  const energy = screen.getByRole('region', { name: 'Miks energii w Wielkiej Brytanii' })
  const choices = await within(energy).findByRole('group', { name: 'Wybierz dzień prognozy' })
  const [today, tomorrow, dayAfter] = within(choices).getAllByRole('button')
  expect(today.textContent).toContain('24 października 2026')
  expect(today.getAttribute('aria-pressed')).toBe('true')
  expect(within(energy).getByText('Wiatr')).toBeTruthy()
  expect(within(tomorrow).getByText('Najwyższy udział')).toBeTruthy()
  expect(within(tomorrow).getByText(/30\/50/)).toBeTruthy()
  expect(dayAfter.textContent).toContain('26 października 2026')
  expect(within(dayAfter).getByText('Brak danych o miksie.')).toBeTruthy()
  expect(screen.getByText('Porównanie opiera się na częściowych danych.')).toBeTruthy()
  expect(screen.getAllByText('Najwyższy udział')).toHaveLength(1)
})

test('marks both leaders in a tie and excludes days without data', async () => {
  mockApi({ dailyMix: () => Response.json([{ ...mix[0], cleanShare: 65 }, mix[1], mix[2]]) })
  render(<App />)
  expect(await screen.findAllByText('Najwyższy udział ex aequo')).toHaveLength(2)
})

test('shows no leader when all three days lack data', async () => {
  mockApi({ dailyMix: () => Response.json(mix.map((day) => ({
    ...day, cleanShare: null, averageMix: {}, availableSlots: 0, complete: false,
  }))) })
  render(<App />)
  expect(await screen.findAllByText('Brak danych o miksie.')).toHaveLength(3)
  expect(screen.queryByText(/Najwyższy udział/)).toBeNull()
  expect(screen.queryByRole('img', { name: /Wykres źródeł energii/ })).toBeNull()
})

test('changes the selected day and keeps raw API percentages in the legend', async () => {
  mockApi({ dailyMix: () => Response.json([
    { ...mix[0], averageMix: { wind: 20, gas: 60 }, cleanShare: 18 },
    mix[1], mix[2],
  ]) })
  render(<App />)
  const todayRing = await screen.findByRole('img', { name: /Czysta energia 18/ })
  expect(todayRing.getAttribute('style')).toMatch(/0% 25%.*25% 100%/)
  expect(screen.getByText('Wiatr').closest('div')?.textContent).toMatch(/20\s*%/)
  expect(screen.getByText('Gaz').closest('div')?.textContent).toMatch(/60\s*%/)
  const windColor = screen.getByText('Wiatr').querySelector('span')?.style.backgroundColor
  const tomorrow = screen.getByRole('button', { name: /^Jutro/ })
  fireEvent.click(tomorrow)
  expect(tomorrow.getAttribute('aria-pressed')).toBe('true')
  expect(screen.getByRole('img', { name: /Czysta energia 65/ })).toBeTruthy()
  expect(screen.getByText('Wiatr').querySelector('span')?.style.backgroundColor).toBe(windColor)
  expect(screen.queryByText('Gaz')).toBeNull()

  fireEvent.click(screen.getByRole('button', { name: /^Pojutrze/ }))
  expect(screen.getByText('Brak danych o miksie dla wybranego dnia.')).toBeTruthy()
  expect(screen.queryByRole('img', { name: /Wykres źródeł energii/ })).toBeNull()
})

test('requests the selected duration and shows dates, percentages and London offsets', async () => {
  const bestWindow = vi.fn(() => Response.json(windowResult))
  mockApi({ bestWindow })
  render(<App />)
  chooseHours('2')
  submitSearch()

  const result = await screen.findByText('Średni udział czystej energii')
  const resultText = result.closest('dl')?.textContent
  expect(bestWindow).toHaveBeenCalledWith('/api/energy/best-window?hours=2', expect.any(AbortSignal))
  expect(resultText).toMatch(/25 października 2026.*01:30 GMT\+1.*25 października 2026.*02:30 GMT/)
  expect(resultText).toMatch(/75,5\s*%/)
})

test('handles a 204 without reading JSON', async () => {
  const response = new Response(null, { status: 204 })
  const json = vi.spyOn(response, 'json')
  mockApi({ bestWindow: () => response })
  render(<App />)
  submitSearch()

  expect(await screen.findByText(/Brak ciągłego okna/)).toBeTruthy()
  expect(json).not.toHaveBeenCalled()
})

test('shows a separate London date for each end of a window across midnight', async () => {
  mockApi({ bestWindow: () => Response.json({
    start: '2026-11-28T23:30:00Z', end: '2026-11-29T00:30:00Z', averageCleanShare: 75,
  }) })
  render(<App />)
  submitSearch()

  expect(await screen.findByText(/28 listopada 2026.*23:30 GMT/)).toBeTruthy()
  expect(screen.getByText(/29 listopada 2026.*00:30 GMT/)).toBeTruthy()
})

test('mix and search failures leave the charger list usable', async () => {
  mockApi({
    dailyMix: () => new Response(null, { status: 502 }),
    bestWindow: () => new Response(null, { status: 502 }),
  })
  render(<App />)
  expect(await screen.findByText(/Nie udało się pobrać miksu/)).toBeTruthy()
  expect(await screen.findByRole('option', { name: /Ładowarka testowa.*Kraków/ })).toBeTruthy()
  submitSearch()
  expect(await screen.findByText(/Nie udało się znaleźć okna/)).toBeTruthy()
  expect(screen.getByRole('option', { name: /Ładowarka testowa.*Kraków/ })).toBeTruthy()
})

test('ignores an old response that arrives after searching for a different duration', async () => {
  let resolveOldRequest: (response: Response) => void = () => {}
  const oldResponse = new Promise<Response>((resolve) => { resolveOldRequest = resolve })
  const bestWindow = vi.fn<BestWindowResponder>((url) => url.endsWith('hours=1')
    ? oldResponse
    : Response.json({ ...windowResult, averageCleanShare: 80 }))
  mockApi({ bestWindow })
  render(<App />)
  submitSearch()
  expect(screen.getByText('Szukam okna ładowania…')).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Znajdź okno' }) as HTMLButtonElement).disabled).toBe(true)

  chooseHours('2')
  expect(bestWindow.mock.calls[0][1]?.aborted).toBe(true)
  submitSearch()
  expect(await screen.findByText(/^80\s*%$/)).toBeTruthy()
  await act(async () => {
    resolveOldRequest(Response.json({ ...windowResult, averageCleanShare: 99 }))
  })
  expect(screen.getByText(/^80\s*%$/)).toBeTruthy()
  expect(screen.queryByText(/^99\s*%$/)).toBeNull()
})
