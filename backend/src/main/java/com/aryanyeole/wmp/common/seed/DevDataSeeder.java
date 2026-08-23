package com.aryanyeole.wmp.common.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

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
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;

/**
 * Populates a local "dev" database with enough data to log in as every role
 * and exercise the onboarding manager hierarchy. Only ever runs when
 * {@code spring.profiles.active} includes "dev" — never under "test" and
 * never by default, so it can never run against a shared or CI database.
 *
 * Idempotent: every insert is guarded by a lookup on the entity's natural
 * key (name or email), so re-running against an already-seeded database
 * inserts nothing new.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /** Same password for every seeded account. Dev-only — never used in prod. */
    static final String DEV_PASSWORD = "wmp-dev-2026!";

    private static final List<String> DEPARTMENTS = List.of(
            "Engineering", "Finance", "Sales", "Human Resources");

    private static final List<SeedEmployee> EMPLOYEES = List.of(
            // Top of each department's chart first, then their reports — the
            // seeder relies on managers already being persisted by the time
            // their reports are processed.
            new SeedEmployee("Grace", "Hopper", "grace.hopper@wmp.dev", "Engineering", null),
            new SeedEmployee("Alan", "Turing", "alan.turing@wmp.dev", "Engineering", "grace.hopper@wmp.dev"),
            new SeedEmployee("Ada", "Lovelace", "ada.lovelace@wmp.dev", "Engineering", "alan.turing@wmp.dev"),
            new SeedEmployee("Margaret", "Hamilton", "margaret.hamilton@wmp.dev", "Engineering", "alan.turing@wmp.dev"),
            new SeedEmployee("Katherine", "Johnson", "katherine.johnson@wmp.dev", "Finance", null),
            new SeedEmployee("Dorothy", "Vaughan", "dorothy.vaughan@wmp.dev", "Finance", "katherine.johnson@wmp.dev"),
            new SeedEmployee("Mary", "Jackson", "mary.jackson@wmp.dev", "Sales", null),
            new SeedEmployee("Radia", "Perlman", "radia.perlman@wmp.dev", "Sales", "mary.jackson@wmp.dev"),
            new SeedEmployee("Frances", "Allen", "frances.allen@wmp.dev", "Human Resources", null),
            new SeedEmployee("Barbara", "Liskov", "barbara.liskov@wmp.dev", "Human Resources", "frances.allen@wmp.dev"));

    private static final List<String> EXPENSE_CATEGORIES = List.of(
            "Travel", "Meals", "Office Supplies", "Software & Subscriptions", "Training & Education");

    /** role -> seeded employee whose email is used to log in as that role. SYSTEM has none. */
    private static final Map<RoleCode, String> ACCOUNTS = new LinkedHashMap<>();
    static {
        ACCOUNTS.put(RoleCode.EMPLOYEE, "ada.lovelace@wmp.dev");
        ACCOUNTS.put(RoleCode.MANAGER, "alan.turing@wmp.dev");
        ACCOUNTS.put(RoleCode.PAYROLL_ADMIN, "katherine.johnson@wmp.dev");
        ACCOUNTS.put(RoleCode.HR_ADMIN, "frances.allen@wmp.dev");
        ACCOUNTS.put(RoleCode.SYSTEM, null); // no employee record — service/scheduler principal
    }

    private record SeedEmployee(String firstName, String lastName, String email, String department, String managerEmail) {
    }

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(DepartmentRepository departmentRepository,
                          EmployeeRepository employeeRepository,
                          ExpenseCategoryRepository expenseCategoryRepository,
                          UserAccountRepository userAccountRepository,
                          RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder) {
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Department> departments = seedDepartments();
        Map<String, Employee> employees = seedEmployees(departments);
        seedExpenseCategories();
        List<String> logins = seedUserAccounts(employees);
        logCredentials(logins);
    }

    private Map<String, Department> seedDepartments() {
        Map<String, Department> byName = new LinkedHashMap<>();
        for (String name : DEPARTMENTS) {
            Department department = departmentRepository.findByName(name).orElseGet(() -> {
                Department created = new Department();
                created.setName(name);
                return departmentRepository.save(created);
            });
            byName.put(name, department);
        }
        return byName;
    }

    private Map<String, Employee> seedEmployees(Map<String, Department> departments) {
        Map<String, Employee> byEmail = new LinkedHashMap<>();
        for (SeedEmployee seed : EMPLOYEES) {
            Employee employee = employeeRepository.findByEmail(seed.email()).orElseGet(() -> {
                Employee created = new Employee();
                created.setDepartment(departments.get(seed.department()));
                created.setFirstName(seed.firstName());
                created.setLastName(seed.lastName());
                created.setEmail(seed.email());
                created.setHireDate(LocalDate.of(2022, 1, 10));
                // Seeded staff represent already-onboarded employees, not new
                // hires — Employee's own default is PENDING as of Phase 4.
                created.setEmploymentStatus(EmploymentStatus.ACTIVE);
                if (seed.managerEmail() != null) {
                    created.setManager(byEmail.get(seed.managerEmail()));
                }
                return employeeRepository.save(created);
            });
            byEmail.put(seed.email(), employee);
        }
        return byEmail;
    }

    private void seedExpenseCategories() {
        for (String name : EXPENSE_CATEGORIES) {
            expenseCategoryRepository.findByName(name).orElseGet(() -> {
                ExpenseCategory created = new ExpenseCategory();
                created.setName(name);
                return expenseCategoryRepository.save(created);
            });
        }
    }

    private List<String> seedUserAccounts(Map<String, Employee> employees) {
        List<String> logins = new ArrayList<>();
        for (Map.Entry<RoleCode, String> entry : ACCOUNTS.entrySet()) {
            RoleCode roleCode = entry.getKey();
            String employeeEmail = entry.getValue();
            String loginEmail = employeeEmail != null ? employeeEmail : "system@wmp.dev";

            userAccountRepository.findByEmail(loginEmail).orElseGet(() -> {
                Role role = roleRepository.findByCode(roleCode)
                        .orElseThrow(() -> new IllegalStateException(
                                "Role not found — expected V1__baseline.sql to have seeded it: " + roleCode));
                UserAccount account = new UserAccount();
                account.setEmail(loginEmail);
                account.setRole(role);
                account.setPasswordHash(passwordEncoder.encode(DEV_PASSWORD));
                account.setActive(true);
                if (employeeEmail != null) {
                    account.setEmployee(employees.get(employeeEmail));
                }
                return userAccountRepository.save(account);
            });
            logins.add(loginEmail + " (" + roleCode + ")");
        }
        return logins;
    }

    private void logCredentials(List<String> logins) {
        log.info("==================================================================");
        log.info("Dev data seeded. Password for every account below: {}", DEV_PASSWORD);
        logins.forEach(line -> log.info("  {}", line));
        log.info("==================================================================");
    }
}
