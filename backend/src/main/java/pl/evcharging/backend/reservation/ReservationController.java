package pl.evcharging.backend.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import static pl.evcharging.backend.reservation.CreateReservationRequest.INVALID_CHARGER_ID;
import static pl.evcharging.backend.reservation.CreateReservationRequest.INVALID_HOURS;

@RestController
public class ReservationController {

    private static final String RESERVATIONS_PATH = "/api/reservations";

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping(RESERVATIONS_PATH)
    public ResponseEntity<ReservationResponse> create(@RequestBody CreateReservationRequest input,
            @CookieValue(name = ReservationOwnerCookie.NAME, required = false) String token, HttpServletRequest request) {
        if (input.chargerId() == null || input.chargerId() < 1) {
            throw badRequest(INVALID_CHARGER_ID);
        }
        if (input.hours() == null) {
            throw badRequest(INVALID_HOURS);
        }

        Instant start = parseInstant(input.start(), "start");
        UUID existingOwner = ReservationOwnerCookie.read(token);
        UUID owner = existingOwner == null ? UUID.randomUUID() : existingOwner;
        Reservation saved = reservationService.create(input.chargerId(), owner, start, input.hours());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore());
        if (existingOwner == null) {
            response.header(HttpHeaders.SET_COOKIE, ReservationOwnerCookie.build(owner, request));
        }
        return response.body(ReservationResponse.from(saved));
    }

    @GetMapping(RESERVATIONS_PATH)
    public ResponseEntity<List<ReservationResponse>> listOwned(
            @CookieValue(name = ReservationOwnerCookie.NAME, required = false) String token, HttpServletRequest request) {
        UUID owner = ReservationOwnerCookie.read(token);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (owner == null) {
            return response.header(HttpHeaders.SET_COOKIE, ReservationOwnerCookie.build(UUID.randomUUID(), request)).body(List.of());
        }
        return response.body(reservationService.findOwned(owner).stream().map(ReservationResponse::from).toList());
    }

    @DeleteMapping(RESERVATIONS_PATH + "/{id}")
    public ResponseEntity<Void> cancel(@PathVariable long id,
            @CookieValue(name = ReservationOwnerCookie.NAME, required = false) String token) {
        reservationService.cancel(id, ReservationOwnerCookie.read(token));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/chargers/{chargerId}/availability")
    public ResponseEntity<List<BusyPeriod>> availability(@PathVariable long chargerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        List<BusyPeriod> busy = reservationService.findBusyPeriods(chargerId, parseInstant(from, "from"),
                parseInstant(to, "to")).stream()
                .map(reservation -> new BusyPeriod(reservation.getStartsAt(), reservation.getEndsAt()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(busy);
    }

    private static Instant parseInstant(String value, String field) {
        if (value != null) {
            try {
                Instant instant = OffsetDateTime.parse(value).toInstant();
                int utcYear = instant.atOffset(ZoneOffset.UTC).getYear();
                if (utcYear >= 1 && utcYear <= 9999) {
                    return instant;
                }
            } catch (DateTimeParseException ignored) {
                // Report the field using the same message for missing and invalid timestamps.
            }
        }
        throw badRequest("Pole " + field + " musi zawierać datę i godzinę ISO 8601 z offsetem, np. 2026-12-01T11:00:00+01:00.");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ReservationResponse(long id, long chargerId, Instant start, Instant end) {
        static ReservationResponse from(Reservation reservation) {
            return new ReservationResponse(reservation.getId(), reservation.getChargerId(),
                    reservation.getStartsAt(), reservation.getEndsAt());
        }
    }

    public record BusyPeriod(Instant start, Instant end) {
    }
}
