package pl.evcharging.backend.reservation;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class ReservationIntegrationTest {

    private static final String RESERVATIONS = "/api/reservations";
    private static final String OWNER_COOKIE = "codibly_reservation_owner";
    private static final String START = "2026-10-25T01:30:00+01:00";
    private static final String END = "2026-10-25T02:30:00Z";
    private static final String AVAILABILITY = "/api/chargers/{id}/availability";

    @Autowired
    private ReservationController controller;
    @Autowired
    private ReservationRepository reservations;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private Clock clock;
    private MockMvc mvc;
    private final List<UUID> testOwners = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void removeOwnFixtures() {
        for (UUID owner : testOwners) {
            jdbc.update("DELETE FROM reservations WHERE owner_id = ?", owner);
        }
    }

    @Test
    void persistsAndIsolatesOwnersThenReleasesCancelledSlot() throws Exception {
        Cookie firstOwner = newOwner();
        Cookie secondOwner = newOwner();
        assertNotEquals(firstOwner.getValue(), secondOwner.getValue());

        MvcResult created = create(firstOwner, 1, START, "2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.start").value("2026-10-25T00:30:00Z"))
                .andExpect(jsonPath("$.end").value(END))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").longValue();
        assertTrue(reservations.existsById(id));
        mvc.perform(get(RESERVATIONS).cookie(firstOwner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(RESERVATIONS).cookie(secondOwner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete(RESERVATIONS + "/{id}", id).cookie(secondOwner)).andExpect(status().isNotFound());
        create(secondOwner, 1, START, "2").andExpect(status().isConflict());
        availability(1, "2026-10-25T02:00:00Z", "2026-10-25T03:00:00Z")
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].start").value("2026-10-25T00:30:00Z"))
                .andExpect(jsonPath("$[0].end").value(END))
                .andExpect(jsonPath("$[0].ownerId").doesNotExist()).andExpect(jsonPath("$[0].id").doesNotExist());
        availability(1, END, "2026-10-25T03:00:00Z").andExpect(jsonPath("$.length()").value(0));
        availability(1, "2026-10-24T23:30:00Z", "2026-10-25T00:30:00Z").andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete(RESERVATIONS + "/{id}", id).cookie(firstOwner)).andExpect(status().isNoContent());
        mvc.perform(get(RESERVATIONS).cookie(firstOwner)).andExpect(jsonPath("$.length()").value(0));
        availability(1, "2026-10-25T02:00:00Z", "2026-10-25T03:00:00Z").andExpect(jsonPath("$.length()").value(0));
        create(secondOwner, 1, START, "2").andExpect(status().isCreated());
    }

    @Test
    void allowsAdjacentReservationsAndConcurrentUseOfDifferentChargers() throws Exception {
        Cookie owner = newOwner();
        create(owner, 1, "2026-10-26T10:00:00Z", "1").andExpect(status().isCreated());
        create(owner, 1, "2026-10-26T11:00:00Z", "1").andExpect(status().isCreated());
        create(owner, 2, "2026-10-26T10:00:00Z", "1").andExpect(status().isCreated());
        availability(1, "2026-10-26T10:30:00Z", "2026-10-26T11:30:00Z")
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createsAnOwnerWhenPostingWithoutACookieAndUsesSecureCookieOnHttps() throws Exception {
        Cookie otherOwner = newOwner();
        MvcResult result = mvc.perform(post(RESERVATIONS).secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chargerId":3,"start":"2026-10-27T10:00:00Z","hours":1,"ownerId":"%s"}
                                """.formatted(otherOwner.getValue())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andReturn();
        Cookie owner = result.getResponse().getCookie(OWNER_COOKIE);
        assertNotNull(owner);
        testOwners.add(UUID.fromString(owner.getValue()));
        assertNotEquals(otherOwner.getValue(), owner.getValue());
        mvc.perform(get(RESERVATIONS).cookie(otherOwner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(RESERVATIONS).cookie(owner)).andExpect(jsonPath("$.length()").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "7", "1.5", "\"2\"", "null"})
    void rejectsInvalidDurations(String duration) throws Exception {
        create(newOwner(), 1, START, duration).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Czas rezerwacji musi wynosić od 1 do 6 pełnych godzin."));
    }

    @Test
    void rejectsPastAndOffsetlessStartsAndInvalidCalendarRanges() throws Exception {
        Cookie owner = newOwner();
        create(owner, 1, "2026-10-23T10:00:00Z", "1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Początek rezerwacji nie może być w przeszłości."));
        create(owner, 1, "2026-10-25T01:30:00", "1").andExpect(status().isBadRequest());
        create(owner, 999, START, "1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Nie znaleziono ładowarki."));
        availability(1, END, END).andExpect(status().isBadRequest());
        availability(999, START, END).andExpect(status().isNotFound());
        mvc.perform(get(AVAILABILITY, 1).param("from", START)).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRequestsWaitOnTheSameChargerAndOnlyOneCommits() throws Exception {
        String start = "2026-10-28T10:00:00Z";
        Cookie firstOwner = newOwner();
        Cookie secondOwner = newOwner();

        try (ExecutorService threads = Executors.newFixedThreadPool(2);
             Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (PreparedStatement lock = blocker.prepareStatement("SELECT id FROM chargers WHERE id = 1 FOR UPDATE")) {
                    lock.execute();
                }
                Future<Integer> first = threads.submit(() -> create(firstOwner, 1, start, "2").andReturn().getResponse().getStatus());
                Future<Integer> second = threads.submit(() -> create(secondOwner, 1, start, "2").andReturn().getResponse().getStatus());
                assertTrue(bothRequestsWaitForLocks(blocker), "Both requests must wait in separate PostgreSQL transactions");
                blocker.commit();
                List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertTrue(statuses.containsAll(List.of(201, 409)), statuses.toString());
            } finally {
                blocker.rollback();
            }
        }
        assertEquals(1, reservations
                .findAllByChargerIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
                        1, Instant.parse("2026-10-28T12:00:00Z"), Instant.parse(start)).size());
    }

    private boolean bothRequestsWaitForLocks(Connection blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try (PreparedStatement query = blocker.prepareStatement("""
                SELECT count(DISTINCT waiting.pid)
                FROM pg_locks waiting JOIN pg_locks charger_table ON charger_table.pid = waiting.pid
                WHERE NOT waiting.granted AND waiting.locktype IN ('transactionid', 'tuple')
                  AND charger_table.relation = 'chargers'::regclass
                  AND charger_table.database = (SELECT oid FROM pg_database WHERE datname = current_database())
                """)) {
            while (System.nanoTime() < deadline) {
                try (ResultSet result = query.executeQuery()) {
                    if (result.next() && result.getInt(1) == 2) {
                        return true;
                    }
                }
                Thread.sleep(25);
            }
        }
        return false;
    }

    private Cookie newOwner() throws Exception {
        MvcResult result = mvc.perform(get(RESERVATIONS))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(0)).andReturn();
        Cookie owner = result.getResponse().getCookie(OWNER_COOKIE);
        assertNotNull(owner);
        assertTrue(owner.isHttpOnly());
        testOwners.add(UUID.fromString(owner.getValue()));
        return owner;
    }

    private ResultActions create(Cookie owner, long charger, String start, String hours) throws Exception {
        return mvc.perform(post(RESERVATIONS).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"chargerId":%d,"start":"%s","hours":%s}
                        """.formatted(charger, start, hours)));
    }

    private ResultActions availability(long charger, String from, String to) throws Exception {
        return mvc.perform(get(AVAILABILITY, charger).param("from", from).param("to", to));
    }
}
