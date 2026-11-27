package pl.evcharging.backend.energy;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
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
            } catch (RestClientException error) {
                Failure category = classify(error);
                boolean retry = category == Failure.TIMEOUT && attempt < MAX_ATTEMPTS;
                Integer status = error instanceof RestClientResponseException responseError
                        ? responseError.getStatusCode().value() : null;
                LOGGER.warn("Carbon Intensity API operation=generation attempt={} elapsedMs={} cause={} rootType={} httpStatus={} retry={}",
                        attempt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        category, rootType(error), status, retry);
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
            outcome.completeExceptionally(new RestClientResponseException("Carbon Intensity HTTP error",
                    status, "", null, null, null));
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
            throw new RestClientException("Carbon Intensity request timed out", error);
        } catch (InterruptedException error) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new RestClientException("Carbon Intensity request interrupted", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RestClientResponseException responseError) {
                exchange.cancel(true);
                throw responseError;
            }
            throw new RestClientException("Carbon Intensity transport failed", error.getCause());
        }

        GenerationResponse response;
        try {
            response = objectMapper.readValue(result.body(), GenerationResponse.class);
        } catch (JacksonException error) {
            throw new RestClientException("Nieprawidłowy JSON z Carbon Intensity API", error);
        }
        if (response == null || response.data() == null) {
            throw new RestClientException("Brak pola data w odpowiedzi Carbon Intensity API");
        }
        return response.data();
    }

    static Failure classify(RestClientException error) {
        if (error instanceof RestClientResponseException) return Failure.HTTP_STATUS;
        if (hasCause(error, SSLException.class)) return Failure.TLS;
        if (hasCause(error, UnknownHostException.class) || hasCause(error, UnresolvedAddressException.class)) {
            return Failure.DNS;
        }
        if (hasCause(error, InterruptedException.class)) return Failure.TRANSPORT;
        if (hasCause(error, HttpTimeoutException.class) || hasCause(error, SocketTimeoutException.class)
                || hasCause(error, TimeoutException.class)) return Failure.TIMEOUT;
        if (hasCause(error, JacksonException.class)) return Failure.INVALID_DATA;
        if (hasCause(error, IOException.class)) return Failure.TRANSPORT;
        return Failure.INVALID_DATA;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
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

    enum Failure { TIMEOUT, DNS, TLS, TRANSPORT, HTTP_STATUS, INVALID_DATA }
}
