package com.paf.aerovisit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paf.aerovisit.controller.GuardOperationController;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Scalability: with Open Session in View off, a request gives its pooled DB connection back after each query, so slow
 * outside calls (KYC on Python, SMTP) never hold one. Full application on an in-memory H2 database (never the real one),
 * using the real application.properties for everything else. Also a smoke test of every page and the main writes with
 * OSIV off, and the gate-email thread pool.
 */
@SpringBootTest(classes = {AerovisitApplication.class, ConnectionPoolScalabilityTest.SlowCallProbe.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:poolcheck;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
class ConnectionPoolScalabilityTest {

    // Stands in for /save: one quick query, then a slow wait like the KYC call or the SMTP send
    @TestConfiguration
    @Import(SlowCallProbe.Probe.class)
    static class SlowCallProbe {
        @RestController
        static class Probe {
            @Autowired VisitorRepository repo;

            @GetMapping("/admin/test-slow-call")
            String slowCall() throws InterruptedException {
                repo.count();
                Thread.sleep(1500);
                return "ok";
            }
        }
    }

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final LocalDate DAY = LocalDate.now().plusDays(5);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    @Autowired private MockMvc mvc;
    @Autowired private DataSource dataSource;
    @Autowired private Environment env;
    @Autowired private VisitorRepository repo;
    @Autowired private BookingSlotRepository slots;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailService emailService;                // no real SMTP
    @MockitoBean private PredictiveAdvisorService aiService;       // no Python

    private final List<Path> qrFiles = new ArrayList<>();

    @BeforeEach
    void seed() {
        // ids far above any real visitor's, so an approval's uploads/QR_<id>.png can never overwrite a real QR code
        jdbc.execute("ALTER TABLE visitor ALTER COLUMN id RESTART WITH 900000001");
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        slots.save(slot);
    }

    @AfterEach
    void cleanUp() throws Exception {
        repo.deleteAll();
        slots.deleteAll();
        for (Path p : qrFiles) Files.deleteIfExists(p);
    }

    private Visitor visitor(String name, String status) {
        Visitor v = new Visitor();
        v.setName(name);
        v.setFullName(name);
        v.setEmail(name.toLowerCase().replace(' ', '.') + "@example.com");
        v.setPurpose("Educational Tour");
        v.setStatus(status);
        v.setVisitDate(DAY);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(1);
        return repo.save(v);
    }

    // --- 1. Pool + Open Session in View ---

    @Test
    void openSessionInViewIsOffAndThePoolIsConfigured() {
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getPoolName()).isEqualTo("AeroVisitPool");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(hikari.getLeakDetectionThreshold()).isEqualTo(30_000);
    }

    @Test
    void requestsWaitingOnASlowCallHoldNoDatabaseConnection() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(callers.submit(() -> mvc.perform(get("/admin/test-slow-call").with(DIRECTOR)).andReturn().getResponse().getStatus()));
            }
            Thread.sleep(800); // all eight have run their query and are now "waiting on Python"
            int active = ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
            // With OSIV on (Spring's default) this was 8 of the pool's 10: ten slow bookings would have starved the app
            assertThat(active).isZero();
            for (Future<Integer> call : calls) assertThat(call.get()).isEqualTo(200);
        } finally {
            callers.shutdownNow();
        }
    }

    // --- 2. Smoke test with OSIV off: every page renders from real rows (no lazy loading anywhere) ---

    @Test
    void everyPageStillRendersWithOpenSessionInViewOff() throws Exception {
        visitor("Pending Person", "PENDING");
        visitor("Approved Person", "APPROVED");
        visitor("Rejected Person", "REJECTED");

        for (String page : new String[] {"/admin", "/admin?filter=pending", "/admin?filter=all", "/admin?range=week",
                "/admin/analytics", "/guard/dashboard", "/admin/staff", "/admin/calendar", "/admin/scanner",
                "/admin/blacklist", "/admin/settings", "/admin/content", "/admin/api/pending-count", "/api/notifications",
                "/api/zones"}) {
            assertThat(mvc.perform(get(page).with(DIRECTOR)).andReturn().getResponse().getStatus()).as(page).isEqualTo(200);
        }
        for (String page : new String[] {"/", "/manage-booking"}) {
            assertThat(mvc.perform(get(page)).andReturn().getResponse().getStatus()).as(page).isEqualTo(200);
        }
    }

    // --- 3. Writes with OSIV off: each loaded row is saved explicitly, so every change still reaches the database ---

    @Test
    void approveRejectAndEmergencyClosureStillSaveWithOpenSessionInViewOff() throws Exception {
        Visitor toApprove = visitor("Approve Me", "PENDING");
        Visitor toReject = visitor("Reject Me", "PENDING");
        Path qr = Paths.get("uploads", "QR_" + toApprove.getId() + ".png");
        assertThat(qr).doesNotExist();
        qrFiles.add(qr);

        mvc.perform(post("/admin/approve/" + toApprove.getId()).with(csrf()).with(DIRECTOR)).andExpect(status().is3xxRedirection());
        assertThat(repo.findById(toApprove.getId()).orElseThrow().getStatus()).isEqualTo("APPROVED"); // through the slot lock

        mvc.perform(post("/admin/reject-visitor").with(csrf()).with(DIRECTOR)
                .param("id", String.valueOf(toReject.getId())).param("presetReason", "Security clearance failed"))
            .andExpect(status().is3xxRedirection());
        assertThat(repo.findById(toReject.getId()).orElseThrow().getStatus()).isEqualTo("REJECTED");

        Visitor onClosedDay = visitor("Closure Victim", "APPROVED");
        mvc.perform(post("/admin/slots/closure/execute").with(csrf()).with(DIRECTOR)
                .param("closureDate", DAY.toString()).param("reason", "Typhoon signal no. 3"))
            .andExpect(status().is3xxRedirection());
        assertThat(repo.findById(onClosedDay.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    // --- 4. Gate emails run on their own small pool, not the JVM-wide common pool ---

    @Test
    void gateEmailsRunOnTheirOwnThreads() throws Exception {
        AtomicReference<String> thread = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ReflectionTestUtils.invokeMethod(GuardOperationController.class, "sendInBackground", (Runnable) () -> {
            thread.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertThat(done.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).isEqualTo("gate-mail");
    }
}
