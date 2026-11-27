package pl.evcharging.backend.reservation;

final class ReservationException extends RuntimeException {

    enum Reason { INVALID_INPUT, NOT_FOUND, CONFLICT }

    private final Reason reason;

    ReservationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
