package pl.evcharging.backend.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

final class ReservationOwnerCookie {

    static final String NAME = "codibly_reservation_owner";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private ReservationOwnerCookie() {
    }

    static UUID read(String token) {
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

    static String build(UUID owner, HttpServletRequest request) {
        return ResponseCookie.from(NAME, owner.toString())
                .path("/api/reservations")
                .httpOnly(true)
                .secure(request.isSecure() || !LOCAL_HOSTS.contains(request.getServerName()))
                .sameSite("Lax")
                .maxAge(Duration.ofDays(365))
                .build().toString();
    }
}
