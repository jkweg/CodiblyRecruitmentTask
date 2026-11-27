package pl.evcharging.backend.charger;

import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChargerHttpTest {
    private final ChargerRepository repository = mock(ChargerRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChargerController(repository)).build();

    @Test
    void returnsExactlyThePublicChargerFields() throws Exception {
        Charger charger = mock(Charger.class);
        when(charger.getId()).thenReturn(1L);
        when(charger.getName()).thenReturn("Ładowarka A");
        when(charger.getLocation()).thenReturn("Kraków");
        when(repository.findAll()).thenReturn(List.of(charger));

        mvc.perform(get("/api/chargers")).andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":1,"name":"Ładowarka A","location":"Kraków"}]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsAnEmptyArrayWhenThereAreNoChargers() throws Exception {
        when(repository.findAll()).thenReturn(List.of());

        mvc.perform(get("/api/chargers")).andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }
}
