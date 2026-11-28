import { useEffect, useRef, useState } from 'react'
import type { MouseEvent, ReactNode } from 'react'
import EnergySection from './energy/EnergySection'
import ReservationsSection from './reservations/ReservationsSection'
import type { Charger } from './reservations/reservationApi'
import './App.css'

type ChargerListState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'success'; chargers: Charger[] }

type SectionId = 'energy' | 'window' | 'reservations'

type PlannerSection = {
  id: SectionId
  headingId: string
  // The control that receives focus, so a click lands where the user can act.
  focusTarget: string
  label: string
  hint: string
  icon: ReactNode
}

function NavIcon({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
  )
}

const sections: PlannerSection[] = [
  {
    id: 'energy', headingId: 'energy-title', focusTarget: '.energy-days [aria-pressed="true"]',
    label: 'Miks energii', hint: 'Dziś, jutro, pojutrze',
    icon: <NavIcon><path d="M12 3a9 9 0 1 0 9 9h-9z" /><path d="M15 3.5A9 9 0 0 1 20.5 9H15z" /></NavIcon>,
  },
  {
    id: 'window', headingId: 'energy-search-title', focusTarget: '#charging-hours',
    label: 'Czyste okno', hint: 'Najlepsze 1–6 h',
    icon: <NavIcon><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></NavIcon>,
  },
  {
    id: 'reservations', headingId: 'reservations-title', focusTarget: '#reservation-charger',
    label: 'Rezerwacje', hint: 'Formularz i kalendarz',
    icon: <NavIcon><rect x="3.5" y="5" width="17" height="15.5" rx="2.5" /><path d="M8 3v4M16 3v4M3.5 10h17M8 14h3" /></NavIcon>,
  },
]

function sectionOf(headingId: string) {
  return document.getElementById(headingId)?.closest('section') ?? null
}

function BrandMark() {
  return (
    <svg className="planner-mark" viewBox="0 0 40 40" aria-hidden="true">
      <rect width="40" height="40" rx="11" fill="#146147" />
      <path d="M22.6 7.5 11.8 22.4h7.5l-1.9 10.1 10.9-15.1h-7.6z" fill="#fff" />
      <circle cx="31.4" cy="8.6" r="4.6" fill="#e07a2e" stroke="#fff" strokeWidth="2" />
    </svg>
  )
}

/** Tracks which planner section the reader is in, and moves to a section when its link is used. */
function useSectionNavigation() {
  const [active, setActive] = useState<SectionId>('energy')
  // A link click drives the scroll; ignore scroll positions until that scroll settles.
  const lockedUntil = useRef(0)

  useEffect(() => {
    let frame = 0
    const update = () => {
      frame = 0
      if (Date.now() < lockedUntil.current) return
      const energy = sectionOf('energy-title')
      const search = sectionOf('energy-search-title')
      const reservations = sectionOf('reservations-title')
      if (!energy || !search || !reservations) return

      const threshold = (document.querySelector('.planner-header')?.getBoundingClientRect().height ?? 0) + 48
      // On wide screens the search panel sits beside the day cards, so scrolling alone cannot pick it.
      const stacked = search.getBoundingClientRect().left - energy.getBoundingClientRect().left < 8
      const atBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4
      let next: SectionId = 'energy'
      if (stacked && search.getBoundingClientRect().top <= threshold) next = 'window'
      if (atBottom || reservations.getBoundingClientRect().top <= threshold) next = 'reservations'
      setActive((current) => (!stacked && current === 'window' && next === 'energy' ? current : next))
    }
    const schedule = () => { if (!frame) frame = requestAnimationFrame(update) }
    window.addEventListener('scroll', schedule, { passive: true })
    window.addEventListener('resize', schedule)
    return () => {
      window.removeEventListener('scroll', schedule)
      window.removeEventListener('resize', schedule)
      cancelAnimationFrame(frame)
    }
  }, [])

  function goTo(section: PlannerSection, event: MouseEvent<HTMLAnchorElement>) {
    const heading = document.getElementById(section.headingId)
    const container = heading?.closest('section')
    if (!heading || !container) return
    event.preventDefault()

    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    lockedUntil.current = Date.now() + (reduceMotion ? 150 : 900)
    container.scrollIntoView?.({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
    const control = container.querySelector<HTMLElement>(section.focusTarget)
    const usable = control && !control.matches(':disabled') ? control : heading
    usable.focus({ preventScroll: true })
    history.replaceState(null, '', `#${section.headingId}`)
    setActive(section.id)
  }

  return { active, goTo }
}

function App() {
  const [chargerList, setChargerList] = useState<ChargerListState>({ status: 'loading' })
  const { active, goTo } = useSectionNavigation()

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
          <div className="planner-brand">
            <BrandMark />
            <div>
              <h1>Planer ładowania</h1>
              <p className="planner-tagline">Czysta energia · Wielka Brytania</p>
            </div>
          </div>
          <nav aria-label="Sekcje planera">
            {sections.map((section) => (
              <a key={section.id} href={`#${section.headingId}`} className="planner-nav-link"
                aria-current={active === section.id ? 'location' : undefined}
                onClick={(event) => goTo(section, event)}>
                <span className="planner-nav-icon">{section.icon}</span>
                <span className="planner-nav-text">
                  <span className="planner-nav-label">{section.label}</span>
                  <span className="planner-nav-hint">{section.hint}</span>
                </span>
              </a>
            ))}
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
