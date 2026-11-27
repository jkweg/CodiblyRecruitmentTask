import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from '../App'

const chargers = [
  { id: 1, name: 'Pierwsza ładowarka', location: 'Kraków' },
  { id: 2, name: 'Druga ładowarka', location: 'Warszawa' },
]
const saved = { id: 4, chargerId: 1, start: '2026-12-01T10:00:00Z', end: '2026-12-01T12:00:00Z' }
const mixedDay = { date: '2026-12-01', averageMix: { wind: 60 }, cleanShare: 60, availableSlots: 48, expectedSlots: 48, complete: true }

type AvailabilityReply = (url: URL, signal?: AbortSignal | null) => Response | Promise<Response>

function mockApi({
  list = () => Response.json([]),
  create = () => Response.json(saved, { status: 201 }),
  cancel = () => new Response(null, { status: 204 }),
  availability = () => Response.json([]),
  mix = () => Response.json([mixedDay]),
}: {
  list?: () => Response | Promise<Response>
  create?: () => Response | Promise<Response>
  cancel?: () => Response | Promise<Response>
  availability?: AvailabilityReply
  mix?: () => Response
} = {}) {
  const api = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/chargers') return Response.json(chargers)
    if (url === '/api/energy/mix') return mix()
    if (url === '/api/reservations' && options?.method === 'POST') return create()
    if (url === '/api/reservations') return list()
    if (url === `/api/reservations/${saved.id}` && options?.method === 'DELETE') return cancel()
    if (url.includes('/availability?')) return availability(new URL(url, 'http://localhost'), options?.signal)
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', api)
  return api
}

function deferredResponse() {
  let resolve: (response: Response) => void = () => {}
  const promise = new Promise<Response>((complete) => { resolve = complete })
  return { promise, resolve }
}

const activeButton = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Zarezerwuj' })
const calendar = () => within(screen.getByRole('region', { name: 'Kalendarz zajętości' }))
const ownList = () => within(screen.getByRole('region', { name: 'Moje rezerwacje' }))

async function fillForm(start = '2026-12-01T10:00') {
  await waitFor(() => expect(activeButton().disabled).toBe(false))
  fireEvent.change(screen.getByLabelText('Początek rezerwacji'), { target: { value: start } })
  fireEvent.change(screen.getByLabelText('Czas rezerwacji'), { target: { value: '2' } })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-03-01T12:00:00Z'))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

test('initializes one owner session in StrictMode and blocks the first write until it finishes', async () => {
  const initial = deferredResponse()
  const api = mockApi({ list: () => initial.promise })
  render(<StrictMode><App /></StrictMode>)
  await screen.findByRole('option', { name: /Pierwsza ładowarka/ })
  expect(activeButton().disabled).toBe(true)
  fireEvent.click(activeButton())
  expect(api.mock.calls.filter(([path]) => path === '/api/reservations')).toEqual([
    ['/api/reservations', { credentials: 'same-origin' }],
  ])
  await act(async () => initial.resolve(Response.json([])))
  expect(activeButton().disabled).toBe(false)
})

test('retries a failed owner session read before allowing a reservation', async () => {
  const retry = deferredResponse()
  const list = vi.fn(() => retry.promise)
    .mockResolvedValueOnce(new Response(null, { status: 502 }))
  const create = vi.fn(() => Response.json(saved, { status: 201 }))
  mockApi({ list, create })
  render(<StrictMode><App /></StrictMode>)

  const retryButton = await ownList().findByRole('button', { name: 'Ponów odczyt rezerwacji' })
  expect(activeButton().disabled).toBe(true)
  expect(list).toHaveBeenCalledTimes(1)
  fireEvent.click(retryButton)
  await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
  expect(activeButton().disabled).toBe(true)
  fireEvent.submit(activeButton().closest('form')!)
  expect(create).not.toHaveBeenCalled()

  await act(async () => retry.resolve(Response.json([])))
  await fillForm()
  fireEvent.click(activeButton())
  expect(await ownList().findByText('Pierwsza ładowarka')).toBeTruthy()
  expect(create).toHaveBeenCalledTimes(1)
  expect(list).toHaveBeenCalledTimes(2)
})

test('creates a reservation while energy is unavailable and refreshes own list and calendar', async () => {
  let isBusy = false
  const created = { ...saved, start: '2026-07-01T09:00:00Z', end: '2026-07-01T11:00:00Z' }
  const write = deferredResponse()
  const availability = vi.fn(() => Response.json(isBusy ? [created] : []))
  const api = mockApi({ create: () => write.promise, availability, mix: () => new Response(null, { status: 502 }) })
  render(<App />)
  await fillForm('2026-07-01T10:00')
  fireEvent.change(screen.getByLabelText('Data w kalendarzu'), { target: { value: '2026-07-01' } })
  expect(await calendar().findByText('Brak rezerwacji.')).toBeTruthy()
  const before = availability.mock.calls.length
  const form = activeButton().closest('form')!
  act(() => {
    fireEvent.submit(form)
    fireEvent.submit(form)
  })
  const posts = api.mock.calls.filter(([path, options]) => path === '/api/reservations' && options?.method === 'POST')
  expect(posts).toHaveLength(1)
  expect(posts[0][1]?.credentials).toBe('same-origin')
  expect(JSON.parse(String(posts[0][1]?.body))).toEqual({ chargerId: 1, start: '2026-07-01T09:00:00.000Z', hours: 2 })
  isBusy = true
  await act(async () => write.resolve(Response.json(created, { status: 201 })))
  expect(await ownList().findByText('Pierwsza ładowarka')).toBeTruthy()
  expect(await calendar().findByText(/10:00/)).toBeTruthy()
  expect(availability.mock.calls.length).toBeGreaterThan(before)
  expect(screen.getByText(/Nie udało się pobrać miksu/)).toBeTruthy()
})

test('keeps all entered values on conflict, refreshes the calendar and leaves energy visible', async () => {
  const availability = vi.fn(() => Response.json([]))
  mockApi({ create: () => new Response(null, { status: 409 }), availability })
  render(<App />)
  await fillForm()
  fireEvent.change(screen.getByLabelText('Ładowarka do rezerwacji i kalendarza'), { target: { value: '2' } })
  await calendar().findByText('Brak rezerwacji.')
  const before = availability.mock.calls.length
  fireEvent.click(activeButton())
  expect(await screen.findByText(/Ładowarka jest zajęta/)).toBeTruthy()
  expect(screen.getByLabelText<HTMLInputElement>('Początek rezerwacji').value).toContain('2026-12-01T10:00')
  expect(screen.getByLabelText<HTMLSelectElement>('Czas rezerwacji').value).toBe('2')
  expect(screen.getByLabelText<HTMLSelectElement>('Ładowarka do rezerwacji i kalendarza').value).toBe('2')
  expect(availability.mock.calls.length).toBeGreaterThan(before)
  expect(screen.getByText('Najwyższy udział')).toBeTruthy()
})

test('cancels once, handles 204 without JSON and releases the current calendar interval', async () => {
  let isBusy = true
  const response = new Response(null, { status: 204 })
  const json = vi.spyOn(response, 'json')
  const write = deferredResponse()
  const cancel = vi.fn(() => write.promise)
  mockApi({ list: () => Response.json([saved]), cancel, availability: () => Response.json(isBusy ? [saved] : []) })
  render(<App />)
  fireEvent.change(screen.getByLabelText('Data w kalendarzu'), { target: { value: '2026-12-01' } })
  expect(await calendar().findByText(/10:00/)).toBeTruthy()
  const button = await ownList().findByRole('button', { name: /Anuluj rezerwację/ })
  act(() => {
    fireEvent.click(button)
    fireEvent.click(button)
  })
  expect(cancel).toHaveBeenCalledTimes(1)
  isBusy = false
  await act(async () => write.resolve(response))
  expect(await ownList().findByText('Nie masz rezerwacji.')).toBeTruthy()
  expect(await calendar().findByText('Brak rezerwacji.')).toBeTruthy()
  expect(json).not.toHaveBeenCalled()
})

test('renders a boundary-crossing period and ignores the old charger and range response', async () => {
  const delayed = deferredResponse()
  const availability = vi.fn<AvailabilityReply>((url) => url.pathname.includes('/1/')
    ? delayed.promise
    : Response.json([{ start: '2026-10-24T22:30:00Z', end: '2026-10-25T01:30:00Z' }]))
  mockApi({ availability })
  render(<App />)
  await screen.findByRole('option', { name: /Pierwsza ładowarka/ })
  await waitFor(() => expect(availability).toHaveBeenCalled())
  fireEvent.change(screen.getByLabelText('Data w kalendarzu'), { target: { value: '2026-10-25' } })
  fireEvent.change(screen.getByLabelText('Zakres kalendarza'), { target: { value: '7' } })
  fireEvent.change(screen.getByLabelText('Ładowarka do rezerwacji i kalendarza'), { target: { value: '2' } })

  expect(await calendar().findByText('Początek w poprzednim dniu.')).toBeTruthy()
  expect(calendar().getAllByRole('article')).toHaveLength(7)
  const [url] = availability.mock.calls.at(-1)!
  expect(url.pathname).toBe('/api/chargers/2/availability')
  expect(url.searchParams.get('from')).toBe('2026-10-24T23:00:00.000Z')
  expect(url.searchParams.get('to')).toBe('2026-11-01T00:00:00.000Z')
  expect(availability.mock.calls[0][1]?.aborted).toBe(true)
  await act(async () => delayed.resolve(Response.json([])))
  expect(calendar().getByText('Początek w poprzednim dniu.')).toBeTruthy()
})

test.each([
  ['2026-03-29T01:30', /godzina nie istnieje/],
  ['2026-10-25T01:30', /godzina występuje.*dwukrotnie/],
])('rejects invalid London wall time %s before sending a request', async (start, message) => {
  const create = vi.fn(() => Response.json(saved, { status: 201 }))
  mockApi({ create })
  render(<App />)
  await fillForm(start)
  fireEvent.click(activeButton())
  expect(await screen.findByText(message)).toBeTruthy()
  expect(create).not.toHaveBeenCalled()
})
