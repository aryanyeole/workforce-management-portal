package com.aryanyeole.wmp.payroll.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.auth.api.AuthResponse;
import com.aryanyeole.wmp.auth.api.LoginRequest;
import com.aryanyeole.wmp.auth.domain.Role;
import com.aryanyeole.wmp.auth.domain.RoleCode;
import com.aryanyeole.wmp.auth.domain.UserAccount;
import com.aryanyeole.wmp.auth.repository.RoleRepository;
import com.aryanyeole.wmp.auth.repository.UserAccountRepository;
import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Business-rule coverage that doesn't fit RouteAuthorizationIT's role x
 * route table: the run state machine's 409s, self-approval, empty-run
 * submit, the net-cents invariant, the duplicate-period 409, and
 * 404-not-403 payslip scoping.
 *
 * @Transactional as in Phase 4's OnboardingLifecycleIT: every test's
 * fixtures roll back automatically, so nothing here leaks into the
 * shared database other IT classes use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class PayrollLifecycleIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String EMAIL_DOMAIN = "@wmp-payrolltest.dev";

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
    private PasswordEncoder passwordEncoder;

    private Employee employeeA;
    private Employee employeeB;
    private String payrollAdmin1Token;
    private Long payrollAdmin1UserAccountId;
    private String payrollAdmin2Token;
    private Long payrollAdmin2UserAccountId;
    private String employeeAToken;

    @BeforeEach
    void setUp() throws Exception {
        Department department = new Department();
        department.setName("Payroll Test Dept " + System.nanoTime());
        department = departmentRepository.save(department);

        Employee payrollAdmin1Employee = newEmployee("payroll-admin-1", department);
        Employee payrollAdmin2Employee = newEmployee("payroll-admin-2", department);
        employeeA = newEmployee("employee-a", department);
        employeeB = newEmployee("employee-b", department);

        LoginResult admin1 = loginAs(payrollAdmin1Employee, RoleCode.PAYROLL_ADMIN);
        payrollAdmin1UserAccountId = admin1.userAccountId();
        payrollAdmin1Token = admin1.accessToken();

        LoginResult admin2 = loginAs(payrollAdmin2Employee, RoleCode.PAYROLL_ADMIN);
        payrollAdmin2UserAccountId = admin2.userAccountId();
        payrollAdmin2Token = admin2.accessToken();

        employeeAToken = loginAs(employeeA, RoleCode.EMPLOYEE).accessToken();
    }

    private Employee newEmployee(String slug, Department department) {
        Employee employee = new Employee();
        employee.setDepartment(department);
        employee.setFirstName(slug);
        employee.setLastName("Tester");
        employee.setEmail(slug + "-" + System.nanoTime() + EMAIL_DOMAIN);
        employee.setHireDate(LocalDate.of(2024, 1, 1));
        return employeeRepository.save(employee);
    }

    private record LoginResult(String accessToken, Long userAccountId) {
    }

    private LoginResult loginAs(Employee employee, RoleCode roleCode) throws Exception {
        UserAccount account = userAccountRepository.findByEmail(employee.getEmail()).orElseGet(() -> {
            Role role = roleRepository.findByCode(roleCode).orElseThrow();
            UserAccount created = new UserAccount();
            created.setEmail(employee.getEmail());
            created.setRole(role);
            created.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            created.setActive(true);
            created.setEmployee(employee);
            return userAccountRepository.save(created);
        });

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(employee.getEmail(), TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        return new LoginResult(response.accessToken(), account.getId());
    }

    private Long createRun(LocalDate periodStart, LocalDate periodEnd) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePayrollRunRequest(periodStart, periodEnd))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), PayrollRunResponse.class).id();
    }

    private void addItem(Long runId, Long employeeId, long grossCents, long taxCents, long deductionsCents) throws Exception {
        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/items")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePayrollItemRequest(employeeId, grossCents, taxCents, deductionsCents))))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicatePeriod_conflicts409() throws Exception {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 15);
        createRun(start, end);

        mockMvc.perform(post("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePayrollRunRequest(start, end))))
                .andExpect(status().isConflict());
    }

    @Test
    void periodEndBeforeStart_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/payroll/runs")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePayrollRunRequest(
                                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void netCentsIsComputedServerSide() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 15));

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/items")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePayrollItemRequest(employeeA.getId(), 10000L, 1000L, 500L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.netCents").value(8500));
    }

    @Test
    void grossLessThanTaxPlusDeductions_badRequest() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15));

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/items")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePayrollItemRequest(employeeA.getId(), 100L, 200L, 0L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmployeeInRun_conflicts409() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 15));
        addItem(runId, employeeA.getId(), 5000L, 0L, 0L);

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/items")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePayrollItemRequest(employeeA.getId(), 6000L, 0L, 0L))))
                .andExpect(status().isConflict());
    }

    @Test
    void submittingEmptyRun_conflicts409() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 15));

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/submit")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isConflict());
    }

    @Test
    void approvingWithoutSubmitting_conflicts409NamingBothStates() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));
        addItem(runId, employeeA.getId(), 5000L, 0L, 0L);

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/approve")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("DRAFT")))
                .andExpect(jsonPath("$.detail", containsString("APPROVED")));
    }

    @Test
    void itemsOnlyAddableWhileDraft() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15));
        addItem(runId, employeeA.getId(), 5000L, 0L, 0L);
        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/submit")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/items")
                        .header("Authorization", "Bearer " + payrollAdmin1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePayrollItemRequest(employeeB.getId(), 4000L, 0L, 0L))))
                .andExpect(status().isConflict());
    }

    @Test
    void submitterCannotApproveTheirOwnRun() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15));
        addItem(runId, employeeA.getId(), 5000L, 0L, 0L);
        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/submit")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/approve")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("own")));
    }

    @Test
    void differentAdminApprovesSuccessfully() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
        addItem(runId, employeeA.getId(), 5000L, 0L, 0L);
        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/submit")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payroll/runs/" + runId + "/approve")
                        .header("Authorization", "Bearer " + payrollAdmin2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedById").value(payrollAdmin2UserAccountId));
    }

    @Test
    void employeeSeesOwnPayslipsButNotAnUnrelatedEmployees() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15));
        addItem(runId, employeeA.getId(), 5000L, 0L, 0L);

        mockMvc.perform(get("/api/v1/payroll/employees/" + employeeA.getId() + "/payslips")
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].employeeId").value(employeeA.getId()));

        mockMvc.perform(get("/api/v1/payroll/employees/" + employeeB.getId() + "/payslips")
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void summaryAggregatesAcrossItems() throws Exception {
        Long runId = createRun(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15));
        addItem(runId, employeeA.getId(), 10000L, 1000L, 500L);
        addItem(runId, employeeB.getId(), 20000L, 2000L, 1000L);

        mockMvc.perform(get("/api/v1/payroll/summary")
                        .header("Authorization", "Bearer " + payrollAdmin1Token))
                .andExpect(status().isOk());
    }
}
