package pl.evcharging.backend.energy;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static pl.evcharging.backend.energy.ForecastSlots.SLOT_DURATION;

@Service
public class EnergyMixService {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final int DAYS = 3;

    private final CarbonIntensityClient client;
    private final Clock clock;

    public EnergyMixService(CarbonIntensityClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public List<DailyEnergyMix> getDailyMix() {
        LocalDate today = londonToday();
        Map<LocalDate, List<ForecastSlots.Slot>> slotsByDay = new HashMap<>();
        loadSlots(today, today.plusDays(DAYS)).forEach((start, slot) -> {
            LocalDate day = start.atZone(LONDON).toLocalDate();
            slotsByDay.computeIfAbsent(day, ignored -> new ArrayList<>()).add(slot);
        });

        List<DailyEnergyMix> result = new ArrayList<>();
        for (int offset = 0; offset < DAYS; offset++) {
            LocalDate day = today.plusDays(offset);
            result.add(summarizeDay(day, slotsByDay.getOrDefault(day, List.of())));
        }
        return result;
    }

    public Optional<ChargingWindow> findBestWindow(int hours) {
        if (hours < 1 || hours > 6) {
            throw new IllegalArgumentException("Czas ładowania musi wynosić od 1 do 6 pełnych godzin.");
        }
        LocalDate today = londonToday();
        return ChargingWindowFinder.findBest(loadSlots(today.plusDays(1), today.plusDays(DAYS)), hours);
    }

    private SortedMap<Instant, ForecastSlots.Slot> loadSlots(LocalDate firstDay, LocalDate endExclusive) {
        Instant from = startOfDay(firstDay);
        Instant to = startOfDay(endExclusive);
        return ForecastSlots.prepare(client.fetchGeneration(from, to), from, to);
    }

    private static DailyEnergyMix summarizeDay(LocalDate day, List<ForecastSlots.Slot> slots) {
        int expectedSlots = (int) Duration.between(startOfDay(day), startOfDay(day.plusDays(1)))
                .dividedBy(SLOT_DURATION);
        int availableSlots = slots.size();
        if (availableSlots == 0) {
            return new DailyEnergyMix(day, Map.of(), null, 0, expectedSlots, false);
        }

        Map<String, Double> averageMix = new TreeMap<>();
        BigDecimal totalCleanShare = BigDecimal.ZERO;
        for (ForecastSlots.Slot slot : slots) {
            for (FuelShare share : slot.mix()) {
                averageMix.merge(share.fuel(), share.perc(), Double::sum);
            }
            totalCleanShare = totalCleanShare.add(slot.cleanShare());
        }
        averageMix.replaceAll((fuel, total) -> total / availableSlots);

        double cleanShare = totalCleanShare.doubleValue() / availableSlots;
        return new DailyEnergyMix(day, averageMix, cleanShare, availableSlots, expectedSlots,
                availableSlots == expectedSlots);
    }

    private LocalDate londonToday() {
        return LocalDate.ofInstant(clock.instant(), LONDON);
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(LONDON).toInstant();
    }
}
