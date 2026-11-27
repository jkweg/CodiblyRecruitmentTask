package pl.evcharging.backend.energy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.SortedMap;

import static pl.evcharging.backend.energy.ForecastSlots.SLOT_DURATION;

final class ChargingWindowFinder {

    private ChargingWindowFinder() {
    }

    static Optional<ChargingWindow> findBest(SortedMap<Instant, ForecastSlots.Slot> slots, int hours) {
        int requiredSlots = hours * 2;
        BigDecimal bestTotal = null;
        ChargingWindow best = null;
        for (Instant start : slots.keySet()) {
            BigDecimal total = BigDecimal.ZERO;
            Instant cursor = start;
            int count = 0;
            while (count < requiredSlots) {
                ForecastSlots.Slot slot = slots.get(cursor);
                if (slot == null) {
                    break;
                }
                total = total.add(slot.cleanShare());
                cursor = cursor.plus(SLOT_DURATION);
                count++;
            }
            // Starts are sorted and a tie leaves the earlier window in place.
            if (count == requiredSlots && (bestTotal == null || total.compareTo(bestTotal) > 0)) {
                bestTotal = total;
                best = new ChargingWindow(start, cursor, total.doubleValue() / requiredSlots);
            }
        }
        return Optional.ofNullable(best);
    }
}
