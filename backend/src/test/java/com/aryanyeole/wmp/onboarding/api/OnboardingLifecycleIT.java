package com.aryanyeole.wmp.onboarding.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.onboarding.domain.OnboardingTask;
import com.aryanyeole.wmp.onboarding.domain.OnboardingTaskStatus;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.onboarding.repository.OnboardingTaskRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Business-rule coverage that doesn't fit RouteAuthorizationIT's role x
 * route table: employee status-transition 409s, 404-not-403 ownership
 * scoping, the task field-level 403, and document upload rejections.
 *
 * @Transactional: every @Test method (and the @BeforeEach fixtures it
 * shares) runs in one transaction that's rolled back afterward, so this
 * class's fixtures never leak into the shared database other IT classes
 * also use — per the Phase 4 requirement not to let onboarding fixtures
 * leak into other IT classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class OnboardingLifecycleIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String EMAIL_DOMAIN = "@wmp-onboardingtest.dev";

    @TempDir
    static Path documentStorageDir;

    @DynamicPropertySource
    static void documentStorageProps(DynamicPropertyRegistry registry) {
        registry.add("wmp.onboarding.document-storage.base-path", () -> documentStorageDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private OnboardingTaskRepository onboardingTaskRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Employee managerEmployee;
    private Employee hrAdminEmployee;
    private Employee employeeA;
    private Employee employeeB;
    private String managerToken;
    private String hrAdminToken;
    private String employeeAToken;

    @BeforeEach
    void setUp() throws Exception {
        Department department = new Department();
        department.setName("Onboarding Test Dept " + System.nanoTime());
        department = departmentRepository.save(department);

        managerEmployee = newEmployee("manager", department, null);
        hrAdminEmployee = newEmployee("hr-admin", department, null);
        employeeA = newEmployee("employee-a", department, managerEmployee);
        employeeB = newEmployee("employee-b", department, null);

        managerToken = loginAs(managerEmployee, RoleCode.MANAGER);
        hrAdminToken = loginAs(hrAdminEmployee, RoleCode.HR_ADMIN);
        employeeAToken = loginAs(employeeA, RoleCode.EMPLOYEE);
    }

    private Employee newEmployee(String slug, Department department, Employee manager) {
        Employee employee = new Employee();
        employee.setDepartment(department);
        employee.setManager(manager);
        employee.setFirstName(slug);
        employee.setLastName("Tester");
        employee.setEmail(slug + "-" + System.nanoTime() + EMAIL_DOMAIN);
        employee.setHireDate(LocalDate.of(2024, 1, 1));
        return employeeRepository.save(employee);
    }

    private String loginAs(Employee employee, RoleCode roleCode) throws Exception {
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        UserAccount account = new UserAccount();
        account.setEmail(employee.getEmail());
        account.setRole(role);
        account.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        account.setActive(true);
        account.setEmployee(employee);
        userAccountRepository.save(account);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(employee.getEmail(), TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    @Test
    void illegalStatusTransition_conflicts409NamingBothStates() throws Exception {
        // employeeA starts PENDING; PENDING -> TERMINATED is not a legal transition.
        // Only HR_ADMIN may PATCH employees at all (see routeAuthorizationEnforced tests),
        // so that's the caller needed to reach EmployeeTransitions' 409.
        mockMvc.perform(patch("/api/v1/onboarding/employees/" + employeeA.getId())
                        .header("Authorization", "Bearer " + hrAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentStatus":"TERMINATED"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("PENDING")))
                .andExpect(jsonPath("$.detail", containsString("TERMINATED")));
    }

    @Test
    void legalStatusTransition_succeeds() throws Exception {
        mockMvc.perform(patch("/api/v1/onboarding/employees/" + employeeA.getId())
                        .header("Authorization", "Bearer " + hrAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentStatus":"ACTIVE"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"));
    }

    @Test
    void managerCannotPatchEmployees_hrAdminOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/onboarding/employees/" + employeeA.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employmentStatus":"ACTIVE"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void unrelatedEmployeeGets404NotForbiddenForAnothersRecord() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/employees/" + employeeB.getId())
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerSeesTheirDirectReport() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/employees/" + employeeA.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employeeA.getId()));
    }

    @Test
    void managerSeesTasksForTheirOwnReport_butNotForAnUnrelatedEmployee() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/employees/" + employeeA.getId() + "/tasks")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/onboarding/employees/" + employeeB.getId() + "/tasks")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeCannotPatchTaskTitle_onlyStatus() throws Exception {
        OnboardingTask task = new OnboardingTask();
        task.setEmployee(employeeA);
        task.setTitle("Submit tax forms");
        task.setStatus(OnboardingTaskStatus.PENDING);
        task = onboardingTaskRepository.save(task);

        mockMvc.perform(patch("/api/v1/onboarding/tasks/" + task.getId())
                        .header("Authorization", "Bearer " + employeeAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Something else"}"""))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/onboarding/tasks/" + task.getId())
                        .header("Authorization", "Bearer " + employeeAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void documentUploadRejectsUnsupportedContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.exe", "application/x-msdownload", "not a real exe".getBytes());

        mockMvc.perform(multipart("/api/v1/onboarding/employees/" + employeeA.getId() + "/documents")
                        .file(file)
                        .param("documentType", "ID_SCAN")
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void documentUploadRejectsOversizedFile() throws Exception {
        // 11MB: over the configured 10MB app limit but under Spring's own 20MB outer limit,
        // so it's our validation (400) that fires, not Spring's MaxUploadSizeExceededException.
        byte[] oversized = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", oversized);

        mockMvc.perform(multipart("/api/v1/onboarding/employees/" + employeeA.getId() + "/documents")
                        .file(file)
                        .param("documentType", "ID_SCAN")
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("maximum")));
    }

    @Test
    void documentUploadSucceedsAndListReturnsMetadataOnly() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "id-card.pdf", "application/pdf", "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/onboarding/employees/" + employeeA.getId() + "/documents")
                        .file(file)
                        .param("documentType", "ID_SCAN")
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("id-card.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(get("/api/v1/onboarding/employees/" + employeeA.getId() + "/documents")
                        .header("Authorization", "Bearer " + employeeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentType").value("ID_SCAN"))
                .andExpect(jsonPath("$[0].fileName").value("id-card.pdf"));
    }
}
