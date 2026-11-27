package pl.evcharging.backend.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import pl.evcharging.backend.charger.ChargerRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static pl.evcharging.backend.reservation.ReservationException.Reason.CONFLICT;
import static pl.evcharging.backend.reservation.ReservationException.Reason.INVALID_INPUT;
import static pl.evcharging.backend.reservation.ReservationException.Reason.NOT_FOUND;

@Service
public class ReservationService {

    private final ChargerRepository chargers;
    private final ReservationRepository reservations;
    private final Clock clock;

    public ReservationService(ChargerRepository chargers, ReservationRepository reservations, Clock clock) {
        this.chargers = chargers;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation create(long chargerId, UUID ownerId, Instant start, int hours) {
        if (hours < 1 || hours > 6) {
            throw new ReservationException(INVALID_INPUT, "Czas rezerwacji musi wynosić od 1 do 6 pełnych godzin.");
        }

        // Hold the charger lock until commit so another request checks against the saved reservation.
        chargers.findByIdForUpdate(chargerId).orElseThrow(ReservationService::chargerNotFound);
        if (start.isBefore(clock.instant())) {
            throw new ReservationException(INVALID_INPUT, "Początek rezerwacji nie może być w przeszłości.");
        }
        Instant startsAt = start.truncatedTo(ChronoUnit.MICROS);
        Instant endsAt = startsAt.plus(hours, ChronoUnit.HOURS);
        if (reservations.existsByChargerIdAndStartsAtLessThanAndEndsAtGreaterThan(chargerId, endsAt, startsAt)) {
            throw new ReservationException(CONFLICT, "Wybrana ładowarka jest zajęta w tym terminie.");
        }
        return reservations.save(new Reservation(chargerId, ownerId, startsAt, endsAt));
    }

    @Transactional(readOnly = true)
    public List<Reservation> findOwned(UUID ownerId) {
        return reservations.findAllByOwnerIdOrderByStartsAtAscIdAsc(ownerId);
    }

    @Transactional
    public void cancel(long id, UUID ownerId) {
        if (ownerId == null || reservations.deleteOwned(id, ownerId) == 0) {
            throw new ReservationException(NOT_FOUND, "Nie znaleziono rezerwacji.");
        }
    }

    @Transactional(readOnly = true)
    public List<Reservation> findBusyPeriods(long chargerId, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new ReservationException(INVALID_INPUT, "Koniec zakresu musi wypadać po jego początku.");
        }
        if (!chargers.existsById(chargerId)) {
            throw chargerNotFound();
        }
        return reservations.findAllByChargerIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(chargerId, to, from);
    }

    private static ReservationException chargerNotFound() {
        return new ReservationException(NOT_FOUND, "Nie znaleziono ładowarki.");
    }
}
