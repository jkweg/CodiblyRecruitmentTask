package pl.evcharging.backend.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.exc.MismatchedInputException;

import static pl.evcharging.backend.reservation.CreateReservationRequest.INVALID_CHARGER_ID;
import static pl.evcharging.backend.reservation.CreateReservationRequest.INVALID_HOURS;

@RestControllerAdvice(assignableTypes = ReservationController.class)
class ReservationExceptionHandler {

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
    ProblemDetail handleInvalidBody(HttpMessageNotReadableException error) {
        String detail = "Podaj poprawny JSON z polami chargerId, start i hours.";
        if (error.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().getFirst().getPropertyName();
            if ("chargerId".equals(field)) detail = INVALID_CHARGER_ID;
            if ("hours".equals(field)) detail = INVALID_HOURS;
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleInvalidId() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Identyfikator musi być liczbą całkowitą.");
    }
}
