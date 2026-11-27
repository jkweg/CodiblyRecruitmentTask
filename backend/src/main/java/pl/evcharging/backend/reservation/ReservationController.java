package pl.evcharging.backend.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class ReservationController {

    private static final String RESERVATIONS_PATH = "/api/reservations";
    private static final String OWNER_COOKIE = "codibly_reservation_owner";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping(RESERVATIONS_PATH)
    public ResponseEntity<ReservationResponse> create(@RequestBody CreateReservationRequest input,
            @CookieValue(name = OWNER_COOKIE, required = false) String token, HttpServletRequest request) {
        if (input.chargerId() == null || !input.chargerId().isIntegralNumber()
                || !input.chargerId().canConvertToLong() || input.chargerId().longValue() < 1) {
            throw badRequest("Podaj dodatni identyfikator ładowarki (chargerId).");
        }
        if (input.hours() == null || !input.hours().isIntegralNumber() || !input.hours().canConvertToInt()) {
            throw badRequest("Czas rezerwacji musi wynosić od 1 do 6 pełnych godzin.");
        }

        Instant start = parseInstant(input.start(), "start");
        UUID existingOwner = readOwner(token);
        UUID owner = existingOwner == null ? UUID.randomUUID() : existingOwner;
        Reservation saved = reservationService.create(input.chargerId().longValue(), owner, start, input.hours().intValue());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore());
        if (existingOwner == null) {
            response.header(HttpHeaders.SET_COOKIE, ownerCookie(owner, request));
        }
        return response.body(ReservationResponse.from(saved));
    }

    @GetMapping(RESERVATIONS_PATH)
    public ResponseEntity<List<ReservationResponse>> listOwned(
            @CookieValue(name = OWNER_COOKIE, required = false) String token, HttpServletRequest request) {
        UUID owner = readOwner(token);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (owner == null) {
            return response.header(HttpHeaders.SET_COOKIE, ownerCookie(UUID.randomUUID(), request)).body(List.of());
        }
        return response.body(reservationService.findOwned(owner).stream().map(ReservationResponse::from).toList());
    }

    @DeleteMapping(RESERVATIONS_PATH + "/{id}")
    public ResponseEntity<Void> cancel(@PathVariable long id,
            @CookieValue(name = OWNER_COOKIE, required = false) String token) {
        reservationService.cancel(id, readOwner(token));
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

    @ExceptionHandler(ReservationException.class)
    ProblemDetail handleReservationError(ReservationException error) {
        HttpStatus status = switch (error.reason()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ProblemDetail.forStatusAndDetail(status, error.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleRequestError(ResponseStatusException error) {
        return error.getBody();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleInvalidBody() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Podaj poprawny JSON z polami chargerId, start i hours.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleInvalidId() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Identyfikator musi być liczbą całkowitą.");
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

    private static UUID readOwner(String token) {
        if (token == null || token.length() != 36) {
            return null;
        }
        try {
            UUID owner = UUID.fromString(token);
            return owner.version() == 4 && owner.toString().equals(token) ? owner : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String ownerCookie(UUID owner, HttpServletRequest request) {
        return ResponseCookie.from(OWNER_COOKIE, owner.toString())
                .path(RESERVATIONS_PATH)
                .httpOnly(true)
                .secure(request.isSecure() || !LOCAL_HOSTS.contains(request.getServerName()))
                .sameSite("Lax")
                .maxAge(Duration.ofDays(365))
                .build().toString();
    }

    public record CreateReservationRequest(JsonNode chargerId, String start, JsonNode hours) {
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
