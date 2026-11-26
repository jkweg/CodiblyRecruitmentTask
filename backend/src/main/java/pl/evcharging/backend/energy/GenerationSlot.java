package pl.evcharging.backend.energy;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GenerationSlot(
        String from,
        String to,
        @JsonProperty("generationmix") List<FuelShare> generationMix
) {
}
