package com.aryanyeole.wmp.expense.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.aryanyeole.wmp.auth.domain.Role;
import com.aryanyeole.wmp.auth.domain.RoleCode;
import com.aryanyeole.wmp.auth.domain.UserAccount;
import com.aryanyeole.wmp.auth.repository.RoleRepository;
import com.aryanyeole.wmp.auth.repository.UserAccountRepository;
import com.aryanyeole.wmp.common.domain.ApprovalAction;
import com.aryanyeole.wmp.common.domain.ApprovalEntityType;
import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.ApprovalEventRepository;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.expense.api.CreateExpenseRequest;
import com.aryanyeole.wmp.expense.api.ExpenseResponse;
import com.aryanyeole.wmp.expense.domain.ExpenseCategory;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;
import com.aryanyeole.wmp.expense.repository.ExpenseCategoryRepository;
import com.aryanyeole.wmp.expense.repository.ExpenseReportRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

/**
 * Regression test for the concurrent-submit race found during Phase 9 Task
 * 4 (a rapid double-click on Submit let two requests both pass the
 * DRAFT->SUBMITTED check before either committed, producing two
 * approval_events rows for one transition). Fixed in Phase 10 Task 0 via
 * ExpenseReportRepository.compareAndSetStatus.
 *
 * Determinism without sleep-and-hope: RACER_COUNT real threads all call
 * ExpenseService.submit for the SAME draft, released simultaneously by a
 * CyclicBarrier so their reads genuinely overlap rather than relying on
 * being "fast enough" to happen to collide — see the class-level note in
 * the commit message for why 2 racers alone would be a flakier test than
 * this. The fix's own correctness doesn't depend on timing at all (a
 * conditional UPDATE is atomic regardless of how many requests race), so a
 * pass here after the fix is a genuine guarantee, not a lucky non-collision.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseSubmitConcurrencyIT extends AbstractIntegrationTest {

    private static final int RACER_COUNT = 10;
    private static final String EMAIL_DOMAIN = "@wmp-submitrace.dev";
    private static final String TEST_PASSWORD = "TestPass123!";

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private ExpenseReportRepository expenseReportRepository;

    @Autowired
    private ApprovalEventRepository approvalEventRepository;

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

    private AuthPrincipal principal;
    private Long categoryId;

    @BeforeAll
    void setUp() {
        Department department = departmentRepository.findByName("Submit Race Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Submit Race Test Dept");
            return departmentRepository.save(created);
        });

        ExpenseCategory category = expenseCategoryRepository.findByName("Submit Race Test Category").orElseGet(() -> {
            ExpenseCategory created = new ExpenseCategory();
            created.setName("Submit Race Test Category");
            return expenseCategoryRepository.save(created);
        });
        categoryId = category.getId();

        String email = "racer" + EMAIL_DOMAIN;
        Employee employee = employeeRepository.findByEmail(email).orElseGet(() -> {
            Employee created = new Employee();
            created.setDepartment(department);
            created.setFirstName("Racer");
            created.setLastName("Tester");
            created.setEmail(email);
            created.setHireDate(LocalDate.of(2024, 1, 1));
            return employeeRepository.save(created);
        });

        UserAccount account = userAccountRepository.findByEmail(email).orElseGet(() -> {
            Role role = roleRepository.findByCode(RoleCode.EMPLOYEE).orElseThrow();
            UserAccount created = new UserAccount();
            created.setEmail(email);
            created.setRole(role);
            created.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            created.setActive(true);
            created.setEmployee(employee);
            return userAccountRepository.save(created);
        });

        principal = new AuthPrincipal(account.getId(), employee.getId(), email, RoleCode.EMPLOYEE);
    }

    @Test
    void concurrentSubmitsOfTheSameDraft_exactlyOneWins() throws Exception {
        ExpenseResponse draft = expenseService.create(principal,
                new CreateExpenseRequest(categoryId, 500, "USD", "concurrent submit race fixture"));
        Long id = draft.id();

        CyclicBarrier barrier = new CyclicBarrier(RACER_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(RACER_COUNT);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACER_COUNT; i++) {
            racers.add(() -> {
                barrier.await(); // all RACER_COUNT threads release together
                return expenseService.submit(principal, id);
            });
        }

        List<Future<Object>> futures = executor.invokeAll(racers, 30, TimeUnit.SECONDS);
        executor.shutdown();

        int successCount = 0;
        int conflictCount = 0;
        for (Future<Object> future : futures) {
            try {
                future.get();
                successCount++;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ConflictException) {
                    conflictCount++;
                } else {
                    throw new AssertionError("Unexpected failure from a racer", e.getCause());
                }
            }
        }

        assertThat(successCount)
                .as("exactly one of %d concurrent submits of the same draft should win", RACER_COUNT)
                .isEqualTo(1);
        assertThat(conflictCount)
                .as("every other racer should lose with a ConflictException (409), not a silent success or a 500")
                .isEqualTo(RACER_COUNT - 1);

        assertThat(expenseReportRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(ExpenseStatus.SUBMITTED);

        long submittedEventCount = approvalEventRepository.findAll().stream()
                .filter(e -> e.getEntityType() == ApprovalEntityType.EXPENSE_REPORT)
                .filter(e -> e.getEntityId().equals(id))
                .filter(e -> e.getAction() == ApprovalAction.SUBMITTED)
                .count();
        assertThat(submittedEventCount)
                .as("exactly one SUBMITTED approval_events row for this report -- this is what actually broke before the fix")
                .isEqualTo(1);
    }
}
