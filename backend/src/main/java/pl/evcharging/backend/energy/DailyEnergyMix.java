package pl.evcharging.backend.energy;

import java.time.LocalDate;
import java.util.Map;

public record DailyEnergyMix(
        LocalDate date,
        Map<String, Double> averageMix,
        Double cleanShare,
        int availableSlots,
        int expectedSlots,
        boolean complete
) {
}
