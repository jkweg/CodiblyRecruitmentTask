package pl.evcharging.backend.energy;

import java.time.Instant;

public record ChargingWindow(Instant start, Instant end, double averageCleanShare) {
}
