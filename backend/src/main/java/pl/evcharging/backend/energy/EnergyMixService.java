package pl.evcharging.backend.energy;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class EnergyMixService {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final Duration SLOT_DURATION = Duration.ofMinutes(30);
    private static final int DAYS = 3;
    private static final Set<String> CLEAN_FUELS = Set.of("biomass", "nuclear", "hydro", "wind", "solar");

    private final CarbonIntensityClient client;

    public EnergyMixService(CarbonIntensityClient client) {
        this.client = client;
    }

    public List<DailyEnergyMix> getDailyMix() {
        return getDailyMix(LocalDate.now(LONDON));
    }

    List<DailyEnergyMix> getDailyMix(LocalDate firstDay) {
        Instant from = startOfDay(firstDay);
        Instant to = startOfDay(firstDay.plusDays(DAYS));
        Map<LocalDate, List<GenerationSlot>> slotsByDay = new HashMap<>();

        usableSlots(from, to).forEach((start, slot) -> {
            LocalDate day = start.atZone(LONDON).toLocalDate();
            slotsByDay.computeIfAbsent(day, ignored -> new ArrayList<>()).add(slot);
        });

        List<DailyEnergyMix> result = new ArrayList<>();
        for (int offset = 0; offset < DAYS; offset++) {
            LocalDate day = firstDay.plusDays(offset);
            result.add(summarizeDay(day, slotsByDay.getOrDefault(day, List.of())));
        }
        return result;
    }

    private Map<Instant, GenerationSlot> usableSlots(Instant from, Instant to) {
        Map<Instant, GenerationSlot> slots = new TreeMap<>();
        for (GenerationSlot slot : client.fetchGeneration(from, to)) {
            if (slot == null || slot.from() == null || slot.to() == null
                    || slot.generationMix() == null || slot.generationMix().isEmpty()) {
                continue;
            }

            try {
                Instant start = OffsetDateTime.parse(slot.from()).toInstant();
                Instant end = OffsetDateTime.parse(slot.to()).toInstant();
                if (start.isBefore(from) || !start.isBefore(to)
                        || start.getNano() != 0 || start.getEpochSecond() % SLOT_DURATION.toSeconds() != 0
                        || !end.equals(start.plus(SLOT_DURATION))) {
                    continue;
                }
                if (slot.generationMix().stream().allMatch(EnergyMixService::isValidShare)) {
                    slots.putIfAbsent(start, slot);
                }
            } catch (DateTimeParseException ignored) {
                // A malformed upstream slot leaves a gap in that day's coverage.
            }
        }
        return slots;
    }

    private static boolean isValidShare(FuelShare share) {
        return share != null && share.fuel() != null && !share.fuel().isBlank()
                && share.perc() != null && Double.isFinite(share.perc())
                && share.perc() >= 0 && share.perc() <= 100;
    }

    private static DailyEnergyMix summarizeDay(LocalDate day, List<GenerationSlot> slots) {
        int expectedSlots = (int) Duration.between(startOfDay(day), startOfDay(day.plusDays(1)))
                .dividedBy(SLOT_DURATION);
        int availableSlots = slots.size();
        if (availableSlots == 0) {
            return new DailyEnergyMix(day, Map.of(), null, 0, expectedSlots, false);
        }

        Map<String, Double> averageMix = new TreeMap<>();
        for (GenerationSlot slot : slots) {
            for (FuelShare share : slot.generationMix()) {
                averageMix.merge(share.fuel(), share.perc(), Double::sum);
            }
        }
        averageMix.replaceAll((fuel, total) -> total / availableSlots);

        double cleanShare = 0;
        for (String fuel : CLEAN_FUELS) {
            cleanShare += averageMix.getOrDefault(fuel, 0.0);
        }
        return new DailyEnergyMix(day, averageMix, cleanShare, availableSlots, expectedSlots,
                availableSlots == expectedSlots);
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(LONDON).toInstant();
    }
}
