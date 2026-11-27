const london = 'Europe/London'
const dayMs = 24 * 60 * 60 * 1000
const wallClock = new Intl.DateTimeFormat('en-GB', {
  timeZone: london, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

function londonWallTime(timestamp: number) {
  const parts = Object.fromEntries(wallClock.formatToParts(timestamp).map(({ type, value }) => [type, value]))
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}

export function londonToday() {
  return londonWallTime(Date.now()).slice(0, 10)
}

export function addCalendarDays(date: string, days: number) {
  return new Date(Date.parse(`${date}T00:00Z`) + days * dayMs).toISOString().slice(0, 10)
}

export function londonStart(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return { status: 'invalid' } as const
  const wallTimestamp = Date.parse(`${value}Z`)
  if (!Number.isFinite(wallTimestamp)) return { status: 'invalid' } as const

  // Check the offsets on both sides of a possible clock change, and verify each candidate in London.
  const offsets = new Set([wallTimestamp - dayMs, wallTimestamp + dayMs].map(
    (sample) => Date.parse(`${londonWallTime(sample)}Z`) - sample,
  ))
  const matches = [...offsets].map((offset) => wallTimestamp - offset)
    .filter((timestamp) => londonWallTime(timestamp) === value)
  if (matches.length === 0) return { status: 'missing' } as const
  if (matches.length > 1) return { status: 'ambiguous' } as const
  return { status: 'valid', instant: new Date(matches[0]).toISOString() } as const
}

export function londonMidnight(date: string) {
  const start = londonStart(`${date}T00:00`)
  if (start.status !== 'valid') throw new Error('Invalid London calendar day')
  return start.instant
}

export function isPast(instant: string) {
  return Date.parse(instant) < Date.now()
}

export const londonDateTime = new Intl.DateTimeFormat('pl-PL', {
  timeZone: london, dateStyle: 'medium', timeStyle: 'long',
})
export const londonTime = new Intl.DateTimeFormat('pl-PL', {
  timeZone: london, hour: '2-digit', minute: '2-digit', timeZoneName: 'shortOffset',
})
export const calendarDay = new Intl.DateTimeFormat('pl-PL', {
  timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long',
})
