package com.aryanyeole.wmp.expense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import com.aryanyeole.wmp.expense.repository.ExpenseCategoryRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Keyset-pagination-specific coverage for GET /api/v1/expenses/approvals
 * (Phase 6 Task C): full traversal exactly-once against a known total,
 * last-page nextCursor=null, malformed cursor -> 400, and a cursor still
 * behaving coherently when the page size changes mid-traversal.
 *
 * Route-level authorization (which roles may call this endpoint) stays in
 * RouteAuthorizationIT; row-level VisibilityScope ownership (self vs
 * managed-team vs unrestricted) is exercised implicitly here by scoping
 * the fixture entirely under one dedicated manager, so this class's
 * assertions are unaffected by any other test class's or dev-seed data's
 * rows sharing the same table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseApprovalsKeysetIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String EMAIL_DOMAIN = "@wmp-approvalstest.dev";
    private static final int EMPLOYEE_COUNT = 5;
    private static final int REPORTS_PER_EMPLOYEE = 5;
    private static final int TOTAL_REPORTS = EMPLOYEE_COUNT * REPORTS_PER_EMPLOYEE;

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

    private String managerToken;
    private final Set<Long> knownReportIds = new HashSet<>();

    @BeforeAll
    void setUp() throws Exception {
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

    private record ApprovalsPage(List<ExpenseResponse> content, String nextCursor) {
    }
}
