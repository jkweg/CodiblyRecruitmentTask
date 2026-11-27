package pl.evcharging.backend.energy;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class CarbonIntensityClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CarbonIntensityClient.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final DateTimeFormatter API_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'Z'")
                    .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final Duration attemptTimeout;

    public CarbonIntensityClient(
            ObjectMapper objectMapper,
            @Value("${energy.carbon-intensity.base-url}") String baseUrl,
            @Value("${energy.carbon-intensity.connect-timeout}") Duration connectTimeout,
            @Value("${energy.carbon-intensity.attempt-timeout}") Duration attemptTimeout
    ) {
        if (attemptTimeout.isZero() || attemptTimeout.isNegative()) {
            throw new IllegalArgumentException("Carbon Intensity attempt timeout must be positive");
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.objectMapper = objectMapper;
        this.baseUri = URI.create(baseUrl);
        this.attemptTimeout = attemptTimeout;
    }

    public List<GenerationSlot> fetchGeneration(Instant from, Instant to) {
        // The upstream endpoint rejects timestamps with percent-encoded colons.
        String path = "/generation/%s/%s".formatted(
                API_TIMESTAMP_FORMAT.format(from), API_TIMESTAMP_FORMAT.format(to));
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int attempt = 1; ; attempt++) {
            long started = System.nanoTime();
            try {
                return readGeneration(request);
            } catch (CarbonIntensityException error) {
                boolean retry = error.category() == CarbonIntensityException.Category.TIMEOUT && attempt < MAX_ATTEMPTS;
                LOGGER.warn("Carbon Intensity API operation=generation attempt={} elapsedMs={} cause={} rootType={} httpStatus={} retry={}",
                        attempt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        error.category(), rootType(error), error.httpStatus(), retry);
                if (!retry) {
                    throw error;
                }
            }
        }
    }

    private List<GenerationSlot> readGeneration(HttpRequest request) {
        var outcome = new CompletableFuture<HttpResponse<byte[]>>();
        var exchange = httpClient.sendAsync(request, response -> {
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return HttpResponse.BodySubscribers.ofByteArray();
            }
            // Classify an HTTP error as soon as the headers arrive. Its body is irrelevant.
            outcome.completeExceptionally(CarbonIntensityException.httpStatus(status));
            return HttpResponse.BodySubscribers.replacing(new byte[0]);
        });
        exchange.whenComplete((response, error) -> {
            if (error == null) {
                outcome.complete(response);
            } else {
                outcome.completeExceptionally(error);
            }
        });
        HttpResponse<byte[]> result;
        try {
            // Waiting for the byte array covers headers and body within the same deadline.
            result = outcome.get(attemptTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            exchange.cancel(true);
            throw CarbonIntensityException.transport(error);
        } catch (InterruptedException error) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw CarbonIntensityException.transport(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof CarbonIntensityException responseError) {
                exchange.cancel(true);
                throw responseError;
            }
            throw CarbonIntensityException.transport(error.getCause());
        }

        GenerationResponse response;
        try {
            response = objectMapper.readValue(result.body(), GenerationResponse.class);
        } catch (JacksonException error) {
            throw CarbonIntensityException.invalidData(error);
        }
        if (response == null || response.data() == null) {
            throw CarbonIntensityException.invalidData(null);
        }
        return response.data();
    }

    private static String rootType(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }

    @PreDestroy
    void shutdown() {
        httpClient.shutdown();
    }
}
