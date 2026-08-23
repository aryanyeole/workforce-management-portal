package com.aryanyeole.wmp.expense.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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
 * Business-rule coverage that doesn't fit RouteAuthorizationIT's role x
 * route table: the DRAFT -> SUBMITTED -> APPROVED|REJECTED state machine,
 * self-approval rejection, and 404-not-403 ownership scoping. Authorization
 * (which roles may call which routes) stays in RouteAuthorizationIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseLifecycleIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String EMAIL_DOMAIN = "@wmp-expensetest.dev";

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

    private String employeeAToken;
    private String employeeBToken;
    private String managerToken;
    private Long categoryId;
    private Long managerUserAccountId;

    @BeforeAll
    void setUp() throws Exception {
        Department department = departmentRepository.findByName("Expense Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Expense Test Dept");
            return departmentRepository.save(created);
        });

        ExpenseCategory category = expenseCategoryRepository.findByName("Lifecycle Test Category").orElseGet(() -> {
            ExpenseCategory created = new ExpenseCategory();
            created.setName("Lifecycle Test Category");
            return expenseCategoryRepository.save(created);
        });
        categoryId = category.getId();

        Employee manager = employee(department, "manager", null);
        Employee employeeA = employee(department, "employee-a", manager);
        Employee employeeB = employee(department, "employee-b", null);

        managerUserAccountId = ensureUserAccount(emailFor("manager"), RoleCode.MANAGER, manager).getId();
        ensureUserAccount(emailFor("employee-a"), RoleCode.EMPLOYEE, employeeA);
        ensureUserAccount(emailFor("employee-b"), RoleCode.EMPLOYEE, employeeB);

        managerToken = login(emailFor("manager"));
        employeeAToken = login(emailFor("employee-a"));
        employeeBToken = login(emailFor("employee-b"));
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

    private Long createDraft(String asToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateExpenseRequest(categoryId, 1500, "USD", "test expense"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ExpenseResponse.class).id();
    }

    private void submit(String asToken, Long id) throws Exception {
        mockMvc.perform(post("/api/v1/expenses/" + id + "/submit").header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk());
    }

    @Test
    void submitThenApprove_setsSubmittedAtApprovedAtAndApprover() throws Exception {
        Long id = createDraft(employeeAToken);
        submit(employeeAToken, id);

        mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.submittedAt").exists())
                .andExpect(jsonPath("$.approvedAt").exists())
                .andExpect(jsonPath("$.approverId").value(managerUserAccountId));
    }

    @Test
    void approvingWithoutSubmitting_conflicts409NamingBothStates() throws Exception {
        Long id = createDraft(employeeAToken);

        mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("DRAFT")))
                .andExpect(jsonPath("$.detail", containsString("APPROVED")));
    }

    @Test
    void managerCannotApproveTheirOwnSubmittedReport() throws Exception {
        Long id = createDraft(managerToken);
        submit(managerToken, id);

        mockMvc.perform(post("/api/v1/expenses/" + id + "/approve")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("own")));
    }

    @Test
    void unrelatedEmployeeGets404NotForbiddenForSomeoneElsesReport() throws Exception {
        Long id = createDraft(employeeAToken);

        mockMvc.perform(get("/api/v1/expenses/" + id).header("Authorization", "Bearer " + employeeBToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + employeeBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateExpenseRequest(null, 2000L, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchingASubmittedReport_conflicts409() throws Exception {
        Long id = createDraft(employeeAToken);
        submit(employeeAToken, id);

        mockMvc.perform(patch("/api/v1/expenses/" + id)
                        .header("Authorization", "Bearer " + employeeAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateExpenseRequest(null, 2000L, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("DRAFT")));
    }
}
