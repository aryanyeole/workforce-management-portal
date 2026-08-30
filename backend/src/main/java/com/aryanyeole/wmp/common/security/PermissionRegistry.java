package com.aryanyeole.wmp.common.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.aryanyeole.wmp.auth.domain.RoleCode;

import jakarta.annotation.PostConstruct;

/**
 * The single source of truth for route-level authorization.
 *
 * Every protected endpoint in the application is declared here exactly once.
 * Controllers carry no security annotations; services carry no role checks.
 * Adding an endpoint without adding it here means it is denied by default.
 */
@Component
public class PermissionRegistry {

    private static final Set<RoleCode> ALL_STAFF = Set.of(
            RoleCode.EMPLOYEE, RoleCode.MANAGER, RoleCode.PAYROLL_ADMIN, RoleCode.HR_ADMIN);

    private static final Set<RoleCode> ALL_ROLES = Set.of(RoleCode.values());

    /**
     * Onboarding is HR-flavored, not payroll-flavored: PAYROLL_ADMIN and
     * SYSTEM have no declared involvement in any onboarding route, unlike
     * ALL_STAFF which includes PAYROLL_ADMIN for expense.
     */
    private static final Set<RoleCode> ONBOARDING_STAFF = Set.of(
            RoleCode.EMPLOYEE, RoleCode.MANAGER, RoleCode.HR_ADMIN);

    private final PathPatternParser parser = PathPatternParser.defaultInstance;
    private final List<CompiledRule> rules = new java.util.ArrayList<>();

    private record CompiledRule(HttpMethod method, PathPattern pattern, RoutePermission permission) {
    }

    @PostConstruct
    void compile() {
        declare().forEach(p -> rules.add(
                new CompiledRule(p.method(), parser.parse(p.pattern()), p)));
        publicPatterns = PUBLIC_PATTERNS.stream().map(parser::parse).toList();
    }

    /**
     * Resolves the rule governing a request, or empty if no rule matches
     * (which callers must treat as deny).
     */
    public Optional<RoutePermission> resolve(HttpMethod method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return rules.stream()
                .filter(r -> r.method().equals(method))
                .filter(r -> r.pattern().matches(container))
                .findFirst()
                .map(CompiledRule::permission);
    }
    
    private static final List<String> PUBLIC_PATTERNS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/actuator/health/**",
            // Operational/scrape endpoints (Phase 7), not business routes —
            // no JWT-bearing client (Prometheus, a developer's browser) is
            // expected to authenticate against these. Deliberately public
            // rather than bypassing RouteAuthorizationFilter entirely, so
            // this stays the one place that decides what's open.
            "/actuator",
            "/actuator/metrics",
            "/actuator/metrics/**",
            "/actuator/prometheus",
            // Write-capable, unlike the read-only ones above — deliberately
            // NOT gated by a role check here. Its actual access control is
            // Spring Boot's own endpoint exposure (management.endpoints.web
            // .exposure.include), which only lists it under the "dev"
            // profile (see application-dev.yml) — see PayrollAccrualEndpoint's
            // javadoc for why that's this project's stand-in for the
            // separate management port/network policy a real deployment
            // would use instead.
            "/actuator/payroll-accrual",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html");

    private List<PathPattern> publicPatterns;

    public boolean isPublic(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return publicPatterns.stream().anyMatch(p -> p.matches(container));
    }

    private List<RoutePermission> declare() {
        return List.of(
                // ---- Auth ----
                // login/refresh are unauthenticated (see PUBLIC_PATTERNS); /me
                // requires a valid token but is open to every role, SYSTEM included.
                new RoutePermission(HttpMethod.GET, "/api/v1/auth/me", ALL_ROLES, false),

                // ---- Expense ----
                new RoutePermission(HttpMethod.POST, "/api/v1/expenses", ALL_STAFF, false),
                new RoutePermission(HttpMethod.GET, "/api/v1/expenses", ALL_STAFF, true),
                new RoutePermission(HttpMethod.GET, "/api/v1/expenses/categories", ALL_STAFF, false),
                new RoutePermission(HttpMethod.GET, "/api/v1/expenses/approvals",
                        Set.of(RoleCode.MANAGER, RoleCode.PAYROLL_ADMIN), true),
                new RoutePermission(HttpMethod.GET, "/api/v1/expenses/{id}", ALL_STAFF, true),
                new RoutePermission(HttpMethod.PATCH, "/api/v1/expenses/{id}", ALL_STAFF, true),
                new RoutePermission(HttpMethod.DELETE, "/api/v1/expenses/{id}", ALL_STAFF, true),
                new RoutePermission(HttpMethod.POST, "/api/v1/expenses/{id}/submit", ALL_STAFF, true),
                new RoutePermission(HttpMethod.POST, "/api/v1/expenses/{id}/approve",
                        Set.of(RoleCode.MANAGER, RoleCode.PAYROLL_ADMIN), true),
                new RoutePermission(HttpMethod.POST, "/api/v1/expenses/{id}/reject",
                        Set.of(RoleCode.MANAGER, RoleCode.PAYROLL_ADMIN), true),

                // ---- Onboarding ----
                new RoutePermission(HttpMethod.POST, "/api/v1/onboarding/employees",
                        Set.of(RoleCode.HR_ADMIN), false),
                new RoutePermission(HttpMethod.GET, "/api/v1/onboarding/employees", ONBOARDING_STAFF, true),
                new RoutePermission(HttpMethod.GET, "/api/v1/onboarding/employees/{id}", ONBOARDING_STAFF, true),
                new RoutePermission(HttpMethod.PATCH, "/api/v1/onboarding/employees/{id}",
                        Set.of(RoleCode.HR_ADMIN), true),
                new RoutePermission(HttpMethod.DELETE, "/api/v1/onboarding/employees/{id}",
                        Set.of(RoleCode.HR_ADMIN), true),
                new RoutePermission(HttpMethod.GET, "/api/v1/onboarding/employees/{id}/tasks",
                        ONBOARDING_STAFF, true),
                new RoutePermission(HttpMethod.POST, "/api/v1/onboarding/employees/{id}/tasks",
                        Set.of(RoleCode.HR_ADMIN, RoleCode.MANAGER), true),
                new RoutePermission(HttpMethod.POST, "/api/v1/onboarding/employees/{id}/documents",
                        ONBOARDING_STAFF, true),
                new RoutePermission(HttpMethod.GET, "/api/v1/onboarding/employees/{id}/documents",
                        ONBOARDING_STAFF, true),
                new RoutePermission(HttpMethod.PATCH, "/api/v1/onboarding/tasks/{taskId}",
                        ONBOARDING_STAFF, true),

                // ---- Payroll ----
                // Runs/items are org-wide, not employee-owned (see PayrollService):
                // PAYROLL_ADMIN is the only role admitted, and ownershipScoped is
                // false throughout since no further row-level scoping ever applies.
                new RoutePermission(HttpMethod.POST, "/api/v1/payroll/runs", Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.GET, "/api/v1/payroll/runs", Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.GET, "/api/v1/payroll/runs/{id}", Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.GET, "/api/v1/payroll/runs/{id}/items",
                        Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.POST, "/api/v1/payroll/runs/{id}/items",
                        Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.POST, "/api/v1/payroll/runs/{id}/submit",
                        Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.POST, "/api/v1/payroll/runs/{id}/approve",
                        Set.of(RoleCode.PAYROLL_ADMIN), false),
                new RoutePermission(HttpMethod.POST, "/api/v1/payroll/runs/{id}/reject",
                        Set.of(RoleCode.PAYROLL_ADMIN), false),

                // Payslips ARE employee-owned — ALL_STAFF (SYSTEM excluded), row-scoped.
                new RoutePermission(HttpMethod.GET, "/api/v1/payroll/employees/{employeeId}/payslips",
                        ALL_STAFF, true),
                new RoutePermission(HttpMethod.GET, "/api/v1/payroll/summary",
                        Set.of(RoleCode.PAYROLL_ADMIN, RoleCode.HR_ADMIN), false));
    }
}