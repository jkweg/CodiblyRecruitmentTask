package pl.evcharging.backend.energy;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CarbonIntensityClientTest {

    @Test
    void readsGenerationMixForRequestedRange() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CarbonIntensityClient client =
                new CarbonIntensityClient(builder, "https://api.carbonintensity.org.uk");

        server.expect(request -> assertEquals(
                "/generation/2026-11-27T00:00Z/2026-11-27T00:30Z",
                request.getURI().getPath()
        )).andRespond(withSuccess("""
                {
                  "data": [
                    {
                      "from": "2026-11-27T00:00Z",
                      "to": "2026-11-27T00:30Z",
                      "generationmix": [
                        { "fuel": "wind", "perc": 42.5 }
                      ]
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        List<GenerationSlot> slots = client.fetchGeneration(
                Instant.parse("2026-11-27T00:00:00Z"),
                Instant.parse("2026-11-27T00:30:00Z")
        );

        assertEquals(List.of(new GenerationSlot(
                "2026-11-27T00:00Z",
                "2026-11-27T00:30Z",
                List.of(new FuelShare("wind", 42.5))
        )), slots);
        server.verify();
    }
}
