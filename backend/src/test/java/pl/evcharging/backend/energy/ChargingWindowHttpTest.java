package pl.evcharging.backend.energy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChargingWindowHttpTest {
    private static final String PATH = "/api/energy/best-window";
    private final EnergyMixService service = mock(EnergyMixService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EnergyController(service)).build();

    @Test
    void returnsWindow() throws Exception {
        Instant start = Instant.parse("2026-11-28T00:00:00Z");
        when(service.findBestWindow(1)).thenReturn(Optional.of(new ChargingWindow(start, start.plusSeconds(3600), 75)));
        mvc.perform(get(PATH).param("hours", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value(start.toString()))
                .andExpect(jsonPath("$.end").value("2026-11-28T01:00:00Z"))
                .andExpect(jsonPath("$.averageCleanShare").value(75.0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "1.5", "-1", "0", "7", "1000000000000"})
    void rejectsInvalidHours(String hours) throws Exception {
        var request = get(PATH);
        if (hours != null) {
            request.param("hours", hours);
        }
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parametr hours musi być liczbą całkowitą od 1 do 6."));
        verifyNoInteractions(service);
    }

    @Test
    void returnsNoContentWhenForecastCannotSupplyAWindow() throws Exception {
        when(service.findBestWindow(6)).thenReturn(Optional.empty());
        mvc.perform(get(PATH).param("hours", "6")).andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void returnsBadGatewayWhenProviderFails() throws Exception {
        when(service.findBestWindow(2)).thenThrow(CarbonIntensityException.httpStatus(503));
        mvc.perform(get(PATH).param("hours", "2")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value("Nie udało się pobrać danych z Carbon Intensity API."));
    }
}
