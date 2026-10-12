package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.MockVisitorGenerator;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** /admin/inject-mock-data: replaces only simulated visitors with a realistic history. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class MockDataInjectionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2); // a Friday
    private static final Set<String> COUNTED = Set.of("APPROVED", "CHECKED-IN", "CHECKED-OUT");

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    // --- The generator ---

    @Test
    void everyDayGetsARealisticHeadcountOfSingleVisitors() {
        List<Visitor> visitors = MockVisitorGenerator.generate(TODAY.minusMonths(6), TODAY, Set.of(), new HashSet<>(), new Random(7));

        // What the trend chart and the AI both see: people (and rows) per day among arrived visitors
        Map<LocalDate, Long> perDay = visitors.stream()
            .filter(v -> COUNTED.contains(v.getStatus()))
            .collect(Collectors.groupingBy(Visitor::getVisitDate, Collectors.counting()));

        for (LocalDate date = TODAY.minusMonths(6); date.isBefore(TODAY); date = date.plusDays(1)) {
            long arrivals = perDay.getOrDefault(date, 0L);
            if (isWeekend(date)) {
                assertThat(arrivals).as("weekend " + date).isBetween(50L, 120L);
            } else {
                assertThat(arrivals).as("weekday " + date).isBetween(15L, 40L);
            }
        }
        assertThat(perDay).doesNotContainKey(TODAY);   // today is left for live check-ins
        assertThat(perDay.keySet()).allMatch(d -> !d.isBefore(TODAY.minusMonths(6)) && d.isBefore(TODAY));
        assertThat(visitors).allMatch(v -> v.getGroupSize() == 1);
    }

    @Test
    void everyMockVisitorIsMarkedAndHasAUniqueReferenceId() {
        Set<String> taken = new HashSet<>(Set.of("PAF-REAL01"));
        List<Visitor> visitors = MockVisitorGenerator.generate(TODAY.minusMonths(3), TODAY, Set.of(), taken, new Random(7));

        assertThat(visitors).isNotEmpty().allMatch(v -> v.getEmail().endsWith(MockVisitorGenerator.MOCK_EMAIL_DOMAIN));
        assertThat(visitors).extracting(Visitor::getEmail).doesNotHaveDuplicates();
        assertThat(visitors).extracting(Visitor::getReferenceId).doesNotHaveDuplicates().doesNotContain("PAF-REAL01").allMatch(r -> r.matches("PAF-[A-HJ-NP-Z2-9]{6}"));
        assertThat(visitors).allMatch(v -> v.getName() != null && v.getPurpose() != null && v.getContactNumber() != null && v.getTimeWindow() == null);
    }

    @Test
    void arrivalsFallInOpeningHoursWithAMorningAndAnAfternoonPeak() {
        List<Visitor> arrived = MockVisitorGenerator.generate(TODAY.minusMonths(6), TODAY, Set.of(), new HashSet<>(), new Random(7)).stream()
            .filter(v -> "CHECKED-OUT".equals(v.getStatus())).toList();

        assertThat(arrived).allSatisfy(v -> {
            assertThat(v.getCheckInTime().toLocalDate()).isEqualTo(v.getVisitDate());
            assertThat(v.getCheckInTime().getHour()).isBetween(8, 16);
            assertThat(v.getCheckOutTime()).isAfter(v.getCheckInTime()).isBeforeOrEqualTo(v.getVisitDate().atTime(17, 30));
        });
        Map<Integer, Long> perHour = arrived.stream().collect(Collectors.groupingBy(v -> v.getCheckInTime().getHour(), Collectors.counting()));
        assertThat(perHour.get(10)).isGreaterThan(perHour.get(8)).isGreaterThan(perHour.get(12));
        assertThat(perHour.get(14)).isGreaterThan(perHour.get(12)).isGreaterThan(perHour.get(16));
    }

    @Test
    void onlyTheLastThirtyDaysAreVisibleInTheVisitorTablesAndFunnel() {
        List<Visitor> visitors = MockVisitorGenerator.generate(TODAY.minusMonths(6), TODAY, Set.of(), new HashSet<>(), new Random(7));
        LocalDate visibleFrom = TODAY.minusDays(29);

        List<Visitor> older = visitors.stream().filter(v -> v.getVisitDate().isBefore(visibleFrom)).toList();
        List<Visitor> recent = visitors.stream().filter(v -> !v.getVisitDate().isBefore(visibleFrom)).toList();

        // Older history is hidden the same way archival rows always were: it feeds the charts and the AI only
        assertThat(older).isNotEmpty().allMatch(v -> "Historical Baseline".equals(v.getPurpose()) && "CHECKED-OUT".equals(v.getStatus()) && v.getRating() == null);
        assertThat(recent).isNotEmpty().noneMatch(v -> "Historical Baseline".equals(v.getPurpose()));
        assertThat(recent).extracting(Visitor::getStatus).contains("CHECKED-OUT", "REJECTED", "CANCELLED_BY_VISITOR");
        assertThat(recent).filteredOn(v -> v.getRating() != null).isNotEmpty().allMatch(v -> v.getRating() >= 2 && v.getRating() <= 5 && v.getFeedback() != null);
        long arrived = recent.stream().filter(v -> "CHECKED-OUT".equals(v.getStatus())).count();
        assertThat(arrived * 100 / recent.size()).isBetween(85L, 95L);
    }

    @Test
    void closedDatesGetNoVisitors() {
        LocalDate closed = TODAY.minusDays(5);
        List<Visitor> visitors = MockVisitorGenerator.generate(TODAY.minusMonths(3), TODAY, Set.of(closed), new HashSet<>(), new Random(7));

        assertThat(visitors).noneMatch(v -> closed.equals(v.getVisitDate()));
    }

    // --- The endpoint ---

    @Test
    @SuppressWarnings("unchecked")
    void injectClearsOldMockRowsThenSavesTheNewSetAndRetrainsTheAi() throws Exception {
        Visitor real = new Visitor();
        real.setReferenceId("PAF-REAL01");
        when(repo.findAll()).thenReturn(List.of(real, new Visitor()));
        when(aiService.triggerAiRetrain()).thenReturn(true);

        mvc.perform(get("/admin/inject-mock-data"))
            .andExpect(redirectedUrl("/admin"))
            .andExpect(flash().attribute("success", "Realistic museum mock data successfully injected."));

        InOrder order = inOrder(repo, aiService);
        order.verify(repo).deleteMockVisitors();
        ArgumentCaptor<Iterable<Visitor>> saved = ArgumentCaptor.forClass(Iterable.class);
        order.verify(repo).saveAll(saved.capture());
        order.verify(aiService).triggerAiRetrain();

        List<Visitor> visitors = new ArrayList<>();
        saved.getValue().forEach(visitors::add);
        LocalDate today = LocalDate.now();
        assertThat(visitors).isNotEmpty().allMatch(v -> v.getEmail().endsWith(MockVisitorGenerator.MOCK_EMAIL_DOMAIN));
        assertThat(visitors).extracting(Visitor::getVisitDate).allMatch(d -> !d.isBefore(today.minusMonths(6)) && d.isBefore(today));
        assertThat(visitors).extracting(Visitor::getVisitDate).contains(today.minusMonths(6), today.minusDays(1));
        assertThat(visitors).extracting(Visitor::getReferenceId).doesNotContain("PAF-REAL01");
        // Real bookings are never removed: nothing but the mock-only delete is called
        verify(repo, never()).deleteAll();
        verify(repo, never()).delete(real);
    }

    @Test
    @SuppressWarnings("unchecked")
    void monthsCanBeThreeToSix() throws Exception {
        LocalDate today = LocalDate.now();
        for (Object[] c : new Object[][] {{"3", 3}, {"1", 3}, {"4", 4}, {"12", 6}}) {
            org.mockito.Mockito.clearInvocations(repo);
            mvc.perform(get("/admin/inject-mock-data").param("months", (String) c[0])).andExpect(status().is3xxRedirection());
            ArgumentCaptor<Iterable<Visitor>> saved = ArgumentCaptor.forClass(Iterable.class);
            verify(repo).saveAll(saved.capture());
            LocalDate earliest = today;
            for (Visitor v : saved.getValue()) {
                if (v.getVisitDate().isBefore(earliest)) earliest = v.getVisitDate();
            }
            assertThat(earliest).as("months=" + c[0]).isEqualTo(today.minusMonths((int) c[1]));
        }
    }

    @Test
    void theMessageSaysSoWhenTheAiServiceIsOffline() throws Exception {
        when(aiService.triggerAiRetrain()).thenReturn(false);

        mvc.perform(get("/admin/inject-mock-data"))
            .andExpect(flash().attribute("success", "Realistic museum mock data successfully injected."
                + " The AI service did not respond, so the forecast is unchanged until AeroVisit-AI is restarted."));
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotInjectMockData() throws Exception {
        mvc.perform(get("/admin/inject-mock-data")).andExpect(status().isForbidden());
        verify(repo, never()).deleteMockVisitors();
        verify(repo, never()).saveAll(anyIterable());
    }
}
