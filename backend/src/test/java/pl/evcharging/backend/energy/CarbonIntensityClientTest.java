package pl.evcharging.backend.energy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class CarbonIntensityClientTest {

    private static final Instant FROM = Instant.parse("2026-11-27T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-27T00:30:00Z");
    private static final String GENERATION = """
            {"data":[{"from":"2026-11-27T00:00Z","to":"2026-11-27T00:30Z","generationmix":[{"fuel":"wind","perc":42.5}]}]}
            """;

    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch releaseResponses = new CountDownLatch(1);
    private final ExecutorService serverThreads = Executors.newCachedThreadPool();
    private HttpServer server;
    private CarbonIntensityClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stopServer() {
        releaseResponses.countDown();
        if (client != null) client.shutdown();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void readsGenerationMixForRequestedRange() {
        AtomicReference<String> rawPath = new AtomicReference<>();
        server.createContext("/generation/", exchange -> {
            try (exchange) {
                rawPath.set(exchange.getRequestURI().getRawPath());
                respond(exchange, 200, GENERATION);
            }
        });

        assertEquals(List.of(new GenerationSlot("2026-11-27T00:00Z", "2026-11-27T00:30Z",
                List.of(new FuelShare("wind", 42.5)))), newClient(Duration.ofSeconds(5)).fetchGeneration(FROM, TO));
        assertEquals("/generation/2026-11-27T00:00Z/2026-11-27T00:30Z", rawPath.get());
    }

    @Test
    void retriesTimedOutRequestOnceAndReturnsSecondResponse(CapturedOutput output) {
        server.createContext("/generation/", exchange -> {
            try (exchange) {
                if (calls.incrementAndGet() == 1) {
                    waitForRelease();
                } else {
                    respond(exchange, 200, GENERATION);
                }
            }
        });

        assertEquals(1, newClient(Duration.ofMillis(600)).fetchGeneration(FROM, TO).size());
        assertEquals(2, calls.get());
        assertTrue(output.getAll().matches("(?s).*operation=generation attempt=1 elapsedMs=\\d+ cause=TIMEOUT.*retry=true.*"));
    }

    @Test
    void stalledBodiesUseTwoFiveSecondAttemptsAndEndInBadGateway(CapturedOutput output) throws Exception {
        server.createContext("/generation/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                waitForRelease();
            }
        });
        var mvc = MockMvcBuilders.standaloneSetup(new EnergyController(
                new EnergyMixService(newClient(Duration.ofSeconds(5))))).build();

        long started = System.nanoTime();
        mvc.perform(get("/api/energy/mix"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Nie udało się pobrać danych z Carbon Intensity API."));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertEquals(2, calls.get());
        assertTrue(elapsed.compareTo(Duration.ofSeconds(9)) >= 0
                && elapsed.compareTo(Duration.ofSeconds(13)) < 0, elapsed::toString);
        System.out.println("Two-attempt Carbon Intensity transport check: " + elapsed.toMillis() + " ms");
        assertTrue(output.getAll().matches("(?s).*operation=generation attempt=2 elapsedMs=\\d+ cause=TIMEOUT.*retry=false.*"));
    }

    @Test
    void reportsHttpStatusWithNoRetryOrResponseContentsInLog(CapturedOutput output) {
        server.createContext("/generation/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                exchange.getResponseHeaders().add("X-Test", "private-header");
                exchange.sendResponseHeaders(400, 1024);
                exchange.getResponseBody().write("private-body".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                waitForRelease();
            }
        });

        var error = assertThrows(CarbonIntensityException.class,
                () -> newClient(Duration.ofMillis(600)).fetchGeneration(FROM, TO));
        assertEquals(CarbonIntensityException.Category.HTTP_STATUS, error.category());
        assertEquals(400, error.httpStatus());
        assertNull(error.getCause());
        assertEquals(1, calls.get());
        assertTrue(output.getAll().contains("cause=HTTP_STATUS"));
        assertTrue(output.getAll().contains("httpStatus=400 retry=false"));
        assertFalse(output.getAll().contains("private-header"));
        assertFalse(output.getAll().contains("private-body"));
    }

    @Test
    void malformedJsonIsNotRetried(CapturedOutput output) {
        server.createContext("/generation/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                respond(exchange, 200, "{broken-json}");
            }
        });

        var error = assertThrows(CarbonIntensityException.class,
                () -> newClient(Duration.ofSeconds(5)).fetchGeneration(FROM, TO));
        assertEquals(CarbonIntensityException.Category.INVALID_DATA, error.category());
        assertNull(error.httpStatus());
        assertInstanceOf(JacksonException.class, error.getCause());
        assertEquals(1, calls.get());
        assertTrue(output.getAll().contains("cause=INVALID_DATA"));
    }

    @Test
    void legitimateEmptyForecastStillReturnsNoContent() throws Exception {
        server.createContext("/generation/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                respond(exchange, 200, "{\"data\":[]}");
            }
        });
        var mvc = MockMvcBuilders.standaloneSetup(new EnergyController(
                new EnergyMixService(newClient(Duration.ofSeconds(5))))).build();

        mvc.perform(get("/api/energy/best-window").param("hours", "2")).andExpect(status().isNoContent());
        assertEquals(1, calls.get());
    }

    @Test
    void classifiesWrappedTransportFailuresBySafeType() {
        assertCategory(CarbonIntensityException.Category.TIMEOUT, new HttpTimeoutException("timeout"));
        assertCategory(CarbonIntensityException.Category.DNS, new UnknownHostException("address"));
        assertCategory(CarbonIntensityException.Category.TLS, new SSLHandshakeException("handshake"));
        assertCategory(CarbonIntensityException.Category.TRANSPORT, new SocketException("connection reset"));
    }

    private static void assertCategory(CarbonIntensityException.Category expected, Exception cause) {
        IOException wrapped = new IOException(cause);
        CarbonIntensityException error = CarbonIntensityException.transport(wrapped);
        assertEquals(expected, error.category());
        assertSame(wrapped, error.getCause());
        assertNull(error.httpStatus());
    }

    private CarbonIntensityClient newClient(Duration timeout) {
        client = new CarbonIntensityClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(3), timeout);
        return client;
    }

    private void waitForRelease() {
        try {
            releaseResponses.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
