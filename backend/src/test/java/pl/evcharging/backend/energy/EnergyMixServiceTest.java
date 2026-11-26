package pl.evcharging.backend.energy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnergyMixServiceTest {

    private final CarbonIntensityClient client = mock(CarbonIntensityClient.class);
    private final EnergyMixService service = new EnergyMixService(client);

    @Test
    void averagesAvailableSlotsAndReportsPartialAndEmptyDays() {
        LocalDate firstDay = LocalDate.of(2026, 11, 26);
        Instant from = Instant.parse("2026-11-26T00:00:00Z");
        Instant to = Instant.parse("2026-11-29T00:00:00Z");
        when(client.fetchGeneration(from, to)).thenReturn(List.of(
                slot("2026-11-25T23:30Z", "2026-11-26T00:00Z", 100, 0, 0),
                slot("2026-11-26T00:00Z", "2026-11-26T00:30Z", 30, 20, 50),
                slot("2026-11-26T00:00Z", "2026-11-26T00:30Z", 30, 20, 50),
                slot("2026-11-26T01:00Z", "2026-11-26T01:30Z", 10, 40, 50),
                slot("2026-11-26T01:30Z", "2026-11-26T02:30Z", 100, 0, 0),
                slot("2026-11-27T23:30Z", "2026-11-28T00:00Z", 60, 20, 20),
                slot("2026-11-29T00:00Z", "2026-11-29T00:30Z", 100, 0, 0)
        ));

        List<DailyEnergyMix> days = service.getDailyMix(firstDay);

        assertEquals(3, days.size());
        DailyEnergyMix today = days.get(0);
        assertEquals(firstDay, today.date());
        assertEquals(Map.of("wind", 20.0, "nuclear", 30.0, "gas", 50.0), today.averageMix());
        assertEquals(50.0, today.cleanShare().doubleValue());
        assertEquals(2, today.availableSlots());
        assertEquals(48, today.expectedSlots());
        assertFalse(today.complete());

        DailyEnergyMix tomorrow = days.get(1);
        assertEquals(firstDay.plusDays(1), tomorrow.date());
        assertEquals(1, tomorrow.availableSlots());
        assertEquals(80.0, tomorrow.cleanShare().doubleValue());

        DailyEnergyMix dayAfterTomorrow = days.get(2);
        assertEquals(firstDay.plusDays(2), dayAfterTomorrow.date());
        assertEquals(0, dayAfterTomorrow.availableSlots());
        assertTrue(dayAfterTomorrow.averageMix().isEmpty());
        assertNull(dayAfterTomorrow.cleanShare());
        assertFalse(dayAfterTomorrow.complete());
    }

    @Test
    void reportsAComplete46SlotDayWhenLondonMovesToSummerTime() {
        LocalDate firstDay = LocalDate.of(2026, 3, 29);
        Instant from = Instant.parse("2026-03-29T00:00:00Z");
        Instant nextDay = Instant.parse("2026-03-29T23:00:00Z");
        Instant to = Instant.parse("2026-03-31T23:00:00Z");
        List<GenerationSlot> slots = new ArrayList<>();
        for (Instant start = from; start.isBefore(nextDay); start = start.plus(30, ChronoUnit.MINUTES)) {
            slots.add(slot(start.toString(), start.plus(30, ChronoUnit.MINUTES).toString(), 40, 20, 40));
        }
        when(client.fetchGeneration(from, to)).thenReturn(slots);

        DailyEnergyMix day = service.getDailyMix(firstDay).getFirst();

        assertEquals(46, day.availableSlots());
        assertEquals(46, day.expectedSlots());
        assertTrue(day.complete());
    }

    private static GenerationSlot slot(String from, String to, double wind, double nuclear, double gas) {
        return new GenerationSlot(from, to, List.of(
                new FuelShare("wind", wind), new FuelShare("nuclear", nuclear), new FuelShare("gas", gas)
        ));
    }
}
