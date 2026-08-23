package com.aryanyeole.wmp.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
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
import com.aryanyeole.wmp.common.config.JwtProperties;
import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * Proves RouteAuthorizationFilter + PermissionRegistry actually gate the API,
 * against a real Postgres container.
 *
 * The role x route table (routeCases) is deliberately data-driven: adding a
 * new PermissionRegistry rule and a matching test case is a one-line change.
 * Onboarding/payroll controllers don't exist yet (Phases 4-5), so for those
 * routes a "non-forbidden" case may resolve as 404 rather than 200 — the
 * point of this suite is 403-vs-not-403, not full response correctness.
 * Expense-specific business rules (state machine 409s, self-approval,
 * 404-not-403 ownership scoping) are covered in ExpenseLifecycleIT instead
 * of here, to keep this table about authorization, not business logic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteAuthorizationIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "TestPass123!";
    private static final String TEST_EMAIL_DOMAIN = "@wmp-routetest.dev";

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

    @Autowired
    private JwtProperties jwtProperties;

    private final Map<RoleCode, String> accessTokens = new EnumMap<>(RoleCode.class);

    @BeforeAll
    void seedAccountsAndLogIn() throws Exception {
        Department department = departmentRepository.findByName("Route Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Route Test Dept");
            return departmentRepository.save(created);
        });

        for (RoleCode role : List.of(RoleCode.EMPLOYEE, RoleCode.MANAGER, RoleCode.PAYROLL_ADMIN, RoleCode.HR_ADMIN)) {
            Employee employee = employeeRepository.findByEmail(emailFor(role)).orElseGet(() -> {
                Employee created = new Employee();
                created.setDepartment(department);
                created.setFirstName(role.name());
                created.setLastName("Tester");
                created.setEmail(emailFor(role));
                created.setHireDate(LocalDate.of(2024, 1, 1));
                return employeeRepository.save(created);
            });
            ensureUserAccount(emailFor(role), role, employee);
        }
        ensureUserAccount(emailFor(RoleCode.SYSTEM), RoleCode.SYSTEM, null);

        for (RoleCode role : RoleCode.values()) {
            accessTokens.put(role, login(emailFor(role)));
        }
    }

    private void ensureUserAccount(String email, RoleCode roleCode, Employee employee) {
        userAccountRepository.findByEmail(email).orElseGet(() -> {
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

    private static String emailFor(RoleCode role) {
        return role.name().toLowerCase() + TEST_EMAIL_DOMAIN;
    }

    private String login(String email) throws Exception {
        return readAuthResponse(performLogin(email)).accessToken();
    }

    private MvcResult performLogin(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private AuthResponse readAuthResponse(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    private String expiredAccessToken() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret()));
        Instant expiry = Instant.now().minus(Duration.ofHours(1));
        return Jwts.builder()
                .issuer(jwtProperties.issuer())
                .subject("999999")
                .claim("email", "expired" + TEST_EMAIL_DOMAIN)
                .claim("role", RoleCode.EMPLOYEE.name())
                .claim("typ", "access")
                .issuedAt(Date.from(expiry.minus(Duration.ofMinutes(5))))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    // ---- Login: every role gets 200 and a usable token ----

    @ParameterizedTest
    @EnumSource(RoleCode.class)
    void loginSucceedsForEveryRole(RoleCode role) throws Exception {
        AuthResponse response = readAuthResponse(performLogin(emailFor(role)));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.role()).isEqualTo(role);
    }

    // ---- Role x route matrix, driven by PermissionRegistry's declarations ----

    record RouteCase(RoleCode role, HttpMethod method, String path, boolean expectedForbidden) {
    }

    static Stream<RouteCase> routeCases() {
        return Stream.of(
                // ALL_STAFF write/read routes: SYSTEM is the only role excluded
                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.POST, "/api/v1/expenses", false),
                new RouteCase(RoleCode.MANAGER, HttpMethod.POST, "/api/v1/expenses", false),
                new RouteCase(RoleCode.PAYROLL_ADMIN, HttpMethod.POST, "/api/v1/expenses", false),
                new RouteCase(RoleCode.HR_ADMIN, HttpMethod.POST, "/api/v1/expenses", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.POST, "/api/v1/expenses", true),

                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.GET, "/api/v1/expenses", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.GET, "/api/v1/expenses", true),

                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.GET, "/api/v1/expenses/categories", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.GET, "/api/v1/expenses/categories", true),

                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.GET, "/api/v1/expenses/1", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.GET, "/api/v1/expenses/1", true),

                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.PATCH, "/api/v1/expenses/1", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.PATCH, "/api/v1/expenses/1", true),

                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.DELETE, "/api/v1/expenses/1", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.DELETE, "/api/v1/expenses/1", true),

                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.POST, "/api/v1/expenses/1/submit", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.POST, "/api/v1/expenses/1/submit", true),

                // MANAGER/PAYROLL_ADMIN-only routes: everyone else is forbidden
                new RouteCase(RoleCode.MANAGER, HttpMethod.GET, "/api/v1/expenses/approvals", false),
                new RouteCase(RoleCode.PAYROLL_ADMIN, HttpMethod.GET, "/api/v1/expenses/approvals", false),
                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.GET, "/api/v1/expenses/approvals", true),
                new RouteCase(RoleCode.HR_ADMIN, HttpMethod.GET, "/api/v1/expenses/approvals", true),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.GET, "/api/v1/expenses/approvals", true),

                new RouteCase(RoleCode.MANAGER, HttpMethod.POST, "/api/v1/expenses/1/approve", false),
                new RouteCase(RoleCode.PAYROLL_ADMIN, HttpMethod.POST, "/api/v1/expenses/1/approve", false),
                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.POST, "/api/v1/expenses/1/approve", true),
                new RouteCase(RoleCode.HR_ADMIN, HttpMethod.POST, "/api/v1/expenses/1/approve", true),

                new RouteCase(RoleCode.MANAGER, HttpMethod.POST, "/api/v1/expenses/1/reject", false),
                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.POST, "/api/v1/expenses/1/reject", true),

                // /auth/me: every role, SYSTEM included, is allowed
                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.GET, "/api/v1/auth/me", false),
                new RouteCase(RoleCode.MANAGER, HttpMethod.GET, "/api/v1/auth/me", false),
                new RouteCase(RoleCode.PAYROLL_ADMIN, HttpMethod.GET, "/api/v1/auth/me", false),
                new RouteCase(RoleCode.HR_ADMIN, HttpMethod.GET, "/api/v1/auth/me", false),
                new RouteCase(RoleCode.SYSTEM, HttpMethod.GET, "/api/v1/auth/me", false),

                // unregistered route: deny by default regardless of role
                new RouteCase(RoleCode.EMPLOYEE, HttpMethod.GET, "/api/v1/does-not-exist", true),
                new RouteCase(RoleCode.HR_ADMIN, HttpMethod.GET, "/api/v1/does-not-exist", true));
    }

    @ParameterizedTest
    @MethodSource("routeCases")
    void enforcesPermissionRegistry(RouteCase testCase) throws Exception {
        int status = mockMvc.perform(request(testCase.method(), testCase.path())
                        .header("Authorization", "Bearer " + accessTokens.get(testCase.role())))
                .andReturn()
                .getResponse()
                .getStatus();

        if (testCase.expectedForbidden()) {
            assertThat(status).isEqualTo(403);
        } else {
            assertThat(status).isNotEqualTo(403);
        }
    }

    // ---- Token edge cases, outside the role matrix ----

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/expenses/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/expenses/categories")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/expenses/categories")
                        .header("Authorization", "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        AuthResponse loginResponse = readAuthResponse(performLogin(emailFor(RoleCode.EMPLOYEE)));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + loginResponse.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPublicWithNoToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
