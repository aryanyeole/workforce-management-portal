package com.aryanyeole.wmp.expense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aryanyeole.wmp.auth.api.AuthResponse;
import com.aryanyeole.wmp.auth.api.LoginRequest;
import com.aryanyeole.wmp.auth.domain.Role;
import com.aryanyeole.wmp.auth.domain.RoleCode;
import com.aryanyeole.wmp.auth.domain.UserAccount;
import com.aryanyeole.wmp.auth.repository.RoleRepository;
import com.aryanyeole.wmp.auth.repository.UserAccountRepository;
import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.expense.domain.ExpenseCategory;
import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.repository.ExpenseCategoryRepository;
import com.aryanyeole.wmp.expense.repository.ExpenseReportRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Keyset-pagination-specific coverage for GET /api/v1/expenses/approvals
 * (Phase 6 Task C): full traversal exactly-once against a known total,
 * last-page nextCursor=null, malformed cursor -> 400, cursor coherence
 * when page size changes mid-traversal, explicit boundary-row exclusion,
 * tie-breaking on identical submitted_at across a page boundary, and that
 * the test environment's timezone is pinned (not accidentally UTC-masking
 * a class of bug this endpoint already had once — see PINNED_ZONE below).
 *
 * Route-level authorization (which roles may call this endpoint) stays in
 * RouteAuthorizationIT; row-level VisibilityScope ownership (self vs
 * managed-team vs unrestricted) is exercised implicitly here by scoping
 * the fixture entirely under one dedicated manager, so this class's
 * assertions are unaffected by any other test class's or dev-seed data's
 * rows sharing the same table.
 *
 * {@code @ActiveProfiles("test")} pulls in application-test.yml, which
 * pins every pooled connection's Postgres session timezone via
 * connection-init-sql. That activates a dedicated Spring context for just
 * this class (still the same static Testcontainers Postgres from
 * AbstractIntegrationTest, just its own Hikari pool/config) so the pin
 * cannot leak into other IT classes' shared context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseApprovalsKeysetIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String EMAIL_DOMAIN = "@wmp-approvalstest.dev";
    private static final int EMPLOYEE_COUNT = 5;
    private static final int REPORTS_PER_EMPLOYEE = 5;
    private static final int TOTAL_REPORTS = EMPLOYEE_COUNT * REPORTS_PER_EMPLOYEE;

    /** Must match application-test.yml's connection-init-sql target zone. */
    private static final String PINNED_ZONE = "Asia/Kolkata";
    private static final int TIE_REPORT_COUNT = 7;
    private static final Instant TIE_SUBMITTED_AT = Instant.parse("2020-06-15T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ExpenseCategoryRepository expenseCategoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExpenseReportRepository expenseReportRepository;

    private String managerToken;
    private final Set<Long> knownReportIds = new HashSet<>();

    // Dedicated, isolated fixture for the tie-breaking test, kept separate
    // from managerToken/knownReportIds/TOTAL_REPORTS above so it can't skew
    // the exact-count assertions the other tests make.
    private String tieManagerToken;
    private final Set<Long> tieReportIds = new HashSet<>();

    // Saved/restored around the whole class so a failure mid-run can't leave
    // other test classes running later in the same forked JVM on the wrong
    // default zone.
    private TimeZone originalDefaultTimeZone;

    @BeforeAll
    void setUp() throws Exception {
        originalDefaultTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(PINNED_ZONE));

        Department department = departmentRepository.findByName("Approvals Keyset Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Approvals Keyset Test Dept");
            return departmentRepository.save(created);
        });

        ExpenseCategory category = expenseCategoryRepository.findByName("Keyset Test Category").orElseGet(() -> {
            ExpenseCategory created = new ExpenseCategory();
            created.setName("Keyset Test Category");
            return expenseCategoryRepository.save(created);
        });
        Long categoryId = category.getId();

        Employee manager = employee(department, "keyset-manager", null);
        ensureUserAccount(emailFor("keyset-manager"), RoleCode.MANAGER, manager);
        managerToken = login(emailFor("keyset-manager"));

        for (int e = 0; e < EMPLOYEE_COUNT; e++) {
            String slug = "keyset-employee-" + e;
            Employee employee = employee(department, slug, manager);
            ensureUserAccount(emailFor(slug), RoleCode.EMPLOYEE, employee);
            String token = login(emailFor(slug));

            for (int r = 0; r < REPORTS_PER_EMPLOYEE; r++) {
                Long id = createDraft(token, categoryId);
                submit(token, id);
                knownReportIds.add(id);
            }
        }

        assertThat(knownReportIds).hasSize(TOTAL_REPORTS);

        setUpTieFixture(department, categoryId);
    }

    @AfterAll
    void tearDownTimeZone() {
        TimeZone.setDefault(originalDefaultTimeZone);
    }

    /**
     * A dedicated manager + employee, isolated from the main fixture above,
     * with TIE_REPORT_COUNT reports all forced (directly via the repository,
     * bypassing the service's Instant.now()) to the exact same submittedAt.
     * At page size 3 that's pages of 3/3/1 — the tied group straddles a
     * page boundary, exercising the id DESC tiebreak that a dataset of
     * distinct timestamps never touches.
     */
    private void setUpTieFixture(Department department, Long categoryId) throws Exception {
        Employee tieManager = employee(department, "tie-manager", null);
        ensureUserAccount(emailFor("tie-manager"), RoleCode.MANAGER, tieManager);
        tieManagerToken = login(emailFor("tie-manager"));

        Employee tieEmployee = employee(department, "tie-employee", tieManager);
        ensureUserAccount(emailFor("tie-employee"), RoleCode.EMPLOYEE, tieEmployee);
        String tieEmployeeToken = login(emailFor("tie-employee"));

        for (int r = 0; r < TIE_REPORT_COUNT; r++) {
            Long id = createDraft(tieEmployeeToken, categoryId);
            submit(tieEmployeeToken, id);
            tieReportIds.add(id);
        }

        for (Long id : tieReportIds) {
            ExpenseReport report = expenseReportRepository.findById(id).orElseThrow();
            report.setSubmittedAt(TIE_SUBMITTED_AT);
            expenseReportRepository.save(report);
        }

        assertThat(tieReportIds).hasSize(TIE_REPORT_COUNT);
    }

    private Employee employee(Department department, String slug, Employee manager) {
        return employeeRepository.findByEmail(emailFor(slug)).orElseGet(() -> {
            Employee created = new Employee();
            created.setDepartment(department);
            created.setFirstName(slug);
            created.setLastName("Tester");
            created.setEmail(emailFor(slug));
            created.setHireDate(LocalDate.of(2024, 1, 1));
            created.setManager(manager);
            return employeeRepository.save(created);
        });
    }

    private UserAccount ensureUserAccount(String email, RoleCode roleCode, Employee employee) {
        return userAccountRepository.findByEmail(email).orElseGet(() -> {
            Role role = roleRepository.findByCode(roleCode).orElseThrow();
            UserAccount account = new UserAccount();
            account.setEmail(email);
            account.setRole(role);
            account.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            account.setActive(true);
            account.setEmployee(employee);
            return userAccountRepository.save(account);
        });
    }

    private static String emailFor(String slug) {
        return slug + EMAIL_DOMAIN;
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private Long createDraft(String asToken, Long categoryId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateExpenseRequest(categoryId, 1000, "USD", "keyset test expense"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExpenseResponse.class).id();
    }

    private void submit(String asToken, Long id) throws Exception {
        mockMvc.perform(post("/api/v1/expenses/" + id + "/submit").header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk());
    }

    private ApprovalsPage fetchPage(String cursor, int size) throws Exception {
        String url = "/api/v1/expenses/approvals?size=" + size
                + (cursor == null ? "" : "&cursor=" + cursor);
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ApprovalsPage.class);
    }

    @Test
    void fullTraversalVisitsKnownTotalExactlyOnceWithNoDupsOrGaps() throws Exception {
        List<Long> visited = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            ApprovalsPage page = fetchPage(cursor, 6);
            page.content().forEach(r -> visited.add(r.id()));
            cursor = page.nextCursor();
            pages++;
            assertThat(pages).isLessThanOrEqualTo(TOTAL_REPORTS + 1); // safety bound, not an assertion of intent
        } while (cursor != null);

        assertThat(visited).hasSize(TOTAL_REPORTS);
        assertThat(new HashSet<>(visited)).as("no duplicates across pages").hasSize(TOTAL_REPORTS);
        assertThat(visited).as("every known report visited").containsExactlyInAnyOrderElementsOf(knownReportIds);
        // 25 reports at page size 6 -> 5 pages (6,6,6,6,1), never 1 (would mean everything came back on page 1).
        assertThat(pages).isEqualTo(5);
    }

    @Test
    void lastPageHasNullNextCursor() throws Exception {
        // A single page sized to fit every known report exhausts the result
        // set in one request, so its own nextCursor must be null.
        ApprovalsPage page = fetchPage(null, TOTAL_REPORTS);

        assertThat(page.content()).hasSize(TOTAL_REPORTS);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void malformedCursorReturns400ProblemDetailNotA500() throws Exception {
        mockMvc.perform(get("/api/v1/expenses/approvals?cursor=not-valid-base64!!!&size=10")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Malformed cursor"));
    }

    @Test
    void cursorStaysCoherentWhenPageSizeChangesMidTraversal() throws Exception {
        ApprovalsPage firstPage = fetchPage(null, 6);
        assertThat(firstPage.content()).hasSize(6);
        assertThat(firstPage.nextCursor()).isNotNull();

        // Continue with a different size than the first page used.
        ApprovalsPage secondPage = fetchPage(firstPage.nextCursor(), 3);
        assertThat(secondPage.content()).hasSize(3);

        List<Long> firstIds = firstPage.content().stream().map(ExpenseResponse::id).toList();
        List<Long> secondIds = secondPage.content().stream().map(ExpenseResponse::id).toList();
        Set<Long> combined = new HashSet<>(firstIds);
        combined.addAll(secondIds);

        assertThat(combined).as("no overlap between the two differently-sized pages").hasSize(9);
    }

    /**
     * Directly targets the Task C timezone bug: a cursor built from a row's
     * own (submittedAt, id) must exclude that row from the page it opens.
     * The bug produced a correct-looking 200 whose content included the
     * boundary row itself — a full-traversal count test can pass even with
     * that defect if it happens to also drop a different row elsewhere, so
     * this checks the specific row, not just the aggregate.
     */
    @Test
    void cursorsOwnBoundaryRowIsExcludedFromNextPage() throws Exception {
        ApprovalsPage firstPage = fetchPage(null, 5);
        Long boundaryId = firstPage.content().get(firstPage.content().size() - 1).id();
        assertThat(firstPage.nextCursor()).isNotNull();

        ApprovalsPage nextPage = fetchPage(firstPage.nextCursor(), 5);

        assertThat(nextPage.content())
                .extracting(ExpenseResponse::id)
                .as("the row the cursor was built from must not reappear on the page it opens")
                .doesNotContain(boundaryId);
    }

    /**
     * TIE_REPORT_COUNT reports share one identical submittedAt (forced
     * directly via the repository — see setUpTieFixture), so ordering
     * between them depends entirely on the id DESC tiebreak, not on
     * submitted_at. Page size 3 splits that group of 7 into 3/3/1: some
     * ties land together, some land split across a page boundary. Full
     * traversal must still visit each exactly once.
     */
    @Test
    void tiedSubmittedAtAcrossPageBoundaryStillVisitsEachExactlyOnce() throws Exception {
        List<Long> visited = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            String url = "/api/v1/expenses/approvals?size=3" + (cursor == null ? "" : "&cursor=" + cursor);
            MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + tieManagerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            ApprovalsPage page = objectMapper.readValue(result.getResponse().getContentAsString(), ApprovalsPage.class);

            page.content().forEach(r -> visited.add(r.id()));
            cursor = page.nextCursor();
            pages++;
            assertThat(pages).isLessThanOrEqualTo(TIE_REPORT_COUNT + 1); // safety bound
        } while (cursor != null);

        assertThat(visited).hasSize(TIE_REPORT_COUNT);
        assertThat(new HashSet<>(visited)).as("no duplicates across the tied group's page split").hasSize(TIE_REPORT_COUNT);
        assertThat(visited).as("every tied report visited").containsExactlyInAnyOrderElementsOf(tieReportIds);
        // 7 tied rows at page size 3 -> 3 pages (3,3,1); the split necessarily
        // falls inside the tied group since every row shares one timestamp.
        assertThat(pages).isEqualTo(3);
    }

    /**
     * Closes the gap the timezone bug exposed at the config level: the
     * original defect went uncaught by every existing test not because the
     * predicate was checked and found correct, but because nothing pinned
     * the test session to a *known* timezone — an accidentally-UTC session
     * would have masked the exact same defect (interpreting a zoneless
     * value "as if UTC" is only wrong when the session isn't UTC). Rather
     * than assert the ambient environment happens to be UTC (true on some
     * machines, false on others, so this would flip based on who runs the
     * suite), both the JVM default zone and the Postgres session are pinned
     * to a fixed, deliberately non-UTC, non-whole-hour zone in this class's
     * own setup/application-test.yml, and this test asserts that pin
     * actually took effect rather than being silently ignored.
     */
    @Test
    void appAndDbSessionTimezonesArePinnedToAKnownNonUtcZone() {
        assertThat(ZoneId.systemDefault())
                .as("JVM default zone must be pinned, not whatever the host machine happens to use")
                .isEqualTo(ZoneId.of(PINNED_ZONE));

        String dbSessionTimeZone = jdbcTemplate.queryForObject("select current_setting('timezone')", String.class);
        assertThat(dbSessionTimeZone)
                .as("DB session timezone must be pinned via connection-init-sql, not left to the connecting client's default")
                .isEqualTo(PINNED_ZONE);
    }

    private record ApprovalsPage(List<ExpenseResponse> content, String nextCursor) {
    }
}
