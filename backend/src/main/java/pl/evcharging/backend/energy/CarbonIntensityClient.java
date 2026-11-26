package pl.evcharging.backend.energy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class CarbonIntensityClient {

    private static final DateTimeFormatter API_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'Z'")
                    .withZone(ZoneOffset.UTC);

    private final RestClient restClient;

    public CarbonIntensityClient(
            RestClient.Builder builder,
            @Value("${energy.carbon-intensity.base-url}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<GenerationSlot> fetchGeneration(Instant from, Instant to) {
        // The upstream endpoint rejects timestamps with percent-encoded colons.
        String path = "/generation/%s/%s".formatted(
                API_TIMESTAMP_FORMAT.format(from), API_TIMESTAMP_FORMAT.format(to));
        GenerationResponse response = restClient.get()
                .uri(path)
                .retrieve()
                .body(GenerationResponse.class);

        if (response == null || response.data() == null) {
            throw new RestClientException("Brak pola data w odpowiedzi Carbon Intensity API");
        }

        return response.data();
    }
}
