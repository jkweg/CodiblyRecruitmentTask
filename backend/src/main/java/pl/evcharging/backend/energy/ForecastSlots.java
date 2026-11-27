package pl.evcharging.backend.energy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

final class ForecastSlots {

    static final Duration SLOT_DURATION = Duration.ofMinutes(30);
    private static final Set<String> CLEAN_FUELS = Set.of("biomass", "nuclear", "hydro", "wind", "solar");
    private static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);
    // The API reports each fuel separately, so rounding can make their sum slightly exceed 100%.
    private static final BigDecimal MAX_ROUNDED_MIX_PERCENTAGE = BigDecimal.valueOf(100.5);

    private ForecastSlots() {
    }

    static SortedMap<Instant, Slot> prepare(List<GenerationSlot> forecast, Instant from, Instant to) {
        SortedMap<Instant, Slot> slots = new TreeMap<>();
        for (GenerationSlot slot : forecast) {
            if (slot == null || slot.from() == null || slot.to() == null
                    || slot.generationMix() == null || slot.generationMix().isEmpty()) {
                continue;
            }

            try {
                Instant start = OffsetDateTime.parse(slot.from()).toInstant();
                Instant end = OffsetDateTime.parse(slot.to()).toInstant();
                if (start.isBefore(from) || end.isAfter(to)
                        || start.getNano() != 0 || start.getEpochSecond() % SLOT_DURATION.toSeconds() != 0
                        || !end.equals(start.plus(SLOT_DURATION))) {
                    continue;
                }
                List<FuelShare> mix = slot.generationMix();
                BigDecimal cleanShare = validCleanShare(mix);
                if (cleanShare != null) {
                    slots.putIfAbsent(start, new Slot(mix, cleanShare));
                }
            } catch (DateTimeParseException ignored) {
                // A malformed upstream slot leaves a gap in that day's coverage.
            }
        }
        return slots;
    }

    private static BigDecimal validCleanShare(List<FuelShare> mix) {
        Set<String> fuels = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cleanShare = BigDecimal.ZERO;
        for (FuelShare share : mix) {
            if (!isValidShare(share) || !fuels.add(share.fuel())) {
                return null;
            }
            BigDecimal percentage = BigDecimal.valueOf(share.perc());
            total = total.add(percentage);
            if (CLEAN_FUELS.contains(share.fuel())) {
                cleanShare = cleanShare.add(percentage);
            }
        }
        return total.compareTo(MAX_ROUNDED_MIX_PERCENTAGE) <= 0 ? cleanShare.min(MAX_PERCENTAGE) : null;
    }

    private static boolean isValidShare(FuelShare share) {
        return share != null && share.fuel() != null && !share.fuel().isBlank()
                && share.perc() != null && Double.isFinite(share.perc())
                && share.perc() >= 0 && share.perc() <= 100;
    }

    record Slot(List<FuelShare> mix, BigDecimal cleanShare) {
    }
}
