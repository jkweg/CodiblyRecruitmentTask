package pl.evcharging.backend.energy;

import java.util.List;

public record GenerationResponse(List<GenerationSlot> data) {
}
