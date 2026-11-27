package pl.evcharging.backend.reservation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationHttpTest {
    private static final Instant START = Instant.parse("2026-12-01T10:00:00Z");
    private final ReservationService service = mock(ReservationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReservationController(service))
            .setControllerAdvice(new ReservationExceptionHandler())
            .build();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\"2\"", "1.0", "1.5", "true", "0", "-1", "9223372036854775808"})
    void rejectsInvalidChargerIds(String chargerId) throws Exception {
        create(chargerId, "1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Podaj dodatni identyfikator ładowarki (chargerId)."));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\"2\"", "1.0", "1.5", "false", "2147483648", "9223372036854775808"})
    void rejectsNonIntegralOrUnrepresentableHours(String hours) throws Exception {
        create("1", hours).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Czas rezerwacji musi wynosić od 1 do 6 pełnych godzin."));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6})
    void passesTheDurationBoundariesAndLargestChargerIdToTheService(int hours) throws Exception {
        Instant end = START.plus(hours, ChronoUnit.HOURS);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(42L);
        when(reservation.getChargerId()).thenReturn(Long.MAX_VALUE);
        when(reservation.getStartsAt()).thenReturn(START);
        when(reservation.getEndsAt()).thenReturn(end);
        when(service.create(eq(Long.MAX_VALUE), any(), eq(START), eq(hours))).thenReturn(reservation);

        create(Long.toString(Long.MAX_VALUE), Integer.toString(hours)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.end").value(end.toString()));
    }

    private ResultActions create(String chargerId, String hours) throws Exception {
        String chargerField = chargerId == null ? "" : "\"chargerId\":" + chargerId + ",";
        String hoursField = hours == null ? "" : ",\"hours\":" + hours;
        return mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{%s\"start\":\"2026-12-01T11:00:00+01:00\"%s}".formatted(chargerField, hoursField)));
    }
}
