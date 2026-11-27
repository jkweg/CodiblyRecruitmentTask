package pl.evcharging.backend.energy;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChargingWindowTest {
    private final CarbonIntensityClient client = mock(CarbonIntensityClient.class);
    private final LocalDate today = LocalDate.of(2026, 11, 27);
    private final EnergyMixService service = serviceOn(today);
    private final Instant from = Instant.parse("2026-11-28T00:00:00Z");
    private final Instant to = Instant.parse("2026-11-30T00:00:00Z");

    @Test
    void selectsHighestAverageAcrossMidnightInPartialUnorderedForecast() {
        Instant midnight = from.plus(Duration.ofDays(1));
        GenerationSlot peak = new GenerationSlot(midnight.toString(), midnight.plusSeconds(1800).toString(),
                List.of(new FuelShare("biomass", 10.0), new FuelShare("hydro", 10.0),
                        new FuelShare("wind", 20.0), new FuelShare("nuclear", 20.0),
                        new FuelShare("solar", 20.0), new FuelShare("gas", 20.0)));
        when(client.fetchGeneration(from, to)).thenReturn(List.of(peak, slot(from, 10),
                slot(midnight.minusSeconds(1800), 60), slot(from.plusSeconds(1800), 10), peak));
        assertEquals(new ChargingWindow(midnight.minusSeconds(1800), midnight.plusSeconds(1800), 70),
                service.findBestWindow(1).orElseThrow());
    }

    @Test
    void exactDecimalTieGoesToEarliestStartIncludingLowerBoundary() {
        when(client.fetchGeneration(from, to)).thenReturn(List.of(slot(from, 0.3), slot(from.plusSeconds(1800), 0),
                slot(from.plusSeconds(7200), 0.1), slot(from.plusSeconds(9000), 0.2)));
        assertEquals(new ChargingWindow(from, from.plusSeconds(3600), 0.15),
                service.findBestWindow(1).orElseThrow());
    }

    @Test
    void acceptsSixHoursEndingExactlyAtUpperBoundary() {
        List<GenerationSlot> slots = new ArrayList<>();
        for (int i = 12; i > 0; i--) {
            slots.add(slot(to.minusSeconds(i * 1800L), 25));
        }
        slots.add(slot(to, 100));
        when(client.fetchGeneration(from, to)).thenReturn(slots);
        assertEquals(new ChargingWindow(to.minusSeconds(21600), to, 25),
                service.findBestWindow(6).orElseThrow());
    }

    @Test
    void missingInvalidDuplicateAndOutOfRangeSlotsCannotCompleteAWindow() {
        when(client.fetchGeneration(from, to)).thenReturn(List.of(slot(from.minusSeconds(1800), 100),
                slot(from, 100), slot(from, 100), slot(from.plusSeconds(1800), -1),
                slot(from.plusSeconds(3600), 100), slot(to.minusSeconds(1800), 100), slot(to, 100)));
        assertTrue(service.findBestWindow(1).isEmpty());
    }

    @Test
    void duplicateFuelMakesSlotUnusableForWindowAndDailyMix() {
        Instant nextSlot = from.plusSeconds(1800);
        GenerationSlot duplicateFuel = new GenerationSlot(from.toString(), nextSlot.toString(),
                List.of(new FuelShare("wind", 45.0), new FuelShare("wind", 45.0)));
        List<GenerationSlot> forecast = List.of(duplicateFuel, slot(nextSlot, 25));
        when(client.fetchGeneration(from, to)).thenReturn(forecast);
        when(client.fetchGeneration(from, to.plus(Duration.ofDays(1)))).thenReturn(forecast);

        assertTrue(service.findBestWindow(1).isEmpty());
        DailyEnergyMix dailyMix = serviceOn(today.plusDays(1)).getDailyMix().getFirst();
        assertEquals(1, dailyMix.availableSlots());
        assertEquals(25.0, dailyMix.cleanShare().doubleValue());
    }

    @Test
    void excessiveTotalLeavesAGapWithoutDiscardingValidNearbySlots() {
        Instant afterGap = from.plusSeconds(3600);
        GenerationSlot impossibleMix = slot(from.plusSeconds(1800),
                new FuelShare("wind", 70.0), new FuelShare("nuclear", 60.0));
        List<GenerationSlot> forecast = List.of(
                slot(from, 90), impossibleMix, slot(afterGap, 20), slot(afterGap.plusSeconds(1800), 40));
        when(client.fetchGeneration(from, to)).thenReturn(forecast);
        when(client.fetchGeneration(from, to.plus(Duration.ofDays(1)))).thenReturn(forecast);

        assertEquals(new ChargingWindow(afterGap, afterGap.plusSeconds(3600), 30),
                service.findBestWindow(1).orElseThrow());
        DailyEnergyMix dailyMix = serviceOn(today.plusDays(1)).getDailyMix().getFirst();
        assertEquals(3, dailyMix.availableSlots());
        assertEquals(50.0, dailyMix.averageMix().get("wind").doubleValue());
        assertFalse(dailyMix.averageMix().containsKey("nuclear"));
        assertEquals(50.0, dailyMix.cleanShare().doubleValue());
    }

    @Test
    void acceptsRoundingAtBoundaryAndCapsCleanPercentage() {
        GenerationSlot atLimit = slot(from, new FuelShare("wind", 50.3), new FuelShare("nuclear", 50.2));
        GenerationSlot aboveLimit = slot(from.plusSeconds(3600),
                new FuelShare("wind", 50.3), new FuelShare("nuclear", 50.3));
        List<GenerationSlot> forecast = List.of(atLimit, slot(from.plusSeconds(1800), 100), aboveLimit);
        when(client.fetchGeneration(from, to)).thenReturn(forecast);
        when(client.fetchGeneration(from, to.plus(Duration.ofDays(1)))).thenReturn(forecast);

        assertEquals(new ChargingWindow(from, from.plusSeconds(3600), 100),
                service.findBestWindow(1).orElseThrow());
        DailyEnergyMix dailyMix = serviceOn(today.plusDays(1)).getDailyMix().getFirst();
        assertEquals(2, dailyMix.availableSlots());
        assertEquals(100.0, dailyMix.cleanShare().doubleValue());
    }

    @Test
    void measuresElapsedTimeThroughRepeatedLondonHour() {
        Instant horizon = Instant.parse("2026-10-24T23:00:00Z");
        Instant start = Instant.parse("2026-10-25T00:30:00Z");
        when(client.fetchGeneration(horizon, Instant.parse("2026-10-27T00:00:00Z")))
                .thenReturn(List.of(slot(start, 20), slot(start.plusSeconds(1800), 80)));
        assertEquals(new ChargingWindow(start, start.plusSeconds(3600), 50),
                serviceOn(LocalDate.of(2026, 10, 24)).findBestWindow(1).orElseThrow());
    }

    private EnergyMixService serviceOn(LocalDate day) {
        return new EnergyMixService(client, Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private GenerationSlot slot(Instant start, double wind) {
        return slot(start, new FuelShare("wind", wind));
    }

    private GenerationSlot slot(Instant start, FuelShare... shares) {
        return new GenerationSlot(start.toString(), start.plusSeconds(1800).toString(),
                List.of(shares));
    }
}
