package com.aryanyeole.wmp.payroll.service;

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
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.payroll.api.CreatePayrollItemRequest;
import com.aryanyeole.wmp.payroll.api.CreatePayrollRunRequest;
import com.aryanyeole.wmp.payroll.api.PayrollRunResponse;
import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;
import com.aryanyeole.wmp.payroll.repository.PayrollRunRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

/**
 * Payroll-run counterpart to ExpenseSubmitConcurrencyIT — same
 * read-check-then-write shape in PayrollService.submit/decide (Phase 10
 * Task 0's step 1 scoping), same fix (PayrollRunRepository.
 * compareAndSetStatus), same deterministic reproduction technique: RACER_COUNT
 * real threads calling PayrollService.submit for the SAME draft run,
 * released together by a CyclicBarrier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayrollSubmitConcurrencyIT extends AbstractIntegrationTest {

    private static final int RACER_COUNT = 10;
    private static final String EMAIL_DOMAIN = "@wmp-payrollsubmitrace.dev";
    private static final String TEST_PASSWORD = "TestPass123!";

    @Autowired
    private PayrollService payrollService;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

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
    private PasswordEncoder passwordEncoder;

    private AuthPrincipal principal;
    private Long employeeId;

    @BeforeAll
    void setUp() {
        Department department = departmentRepository.findByName("Payroll Submit Race Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Payroll Submit Race Test Dept");
            return departmentRepository.save(created);
        });

        String adminEmail = "racer" + EMAIL_DOMAIN;
        Employee adminEmployee = employeeRepository.findByEmail(adminEmail).orElseGet(() -> {
            Employee created = new Employee();
            created.setDepartment(department);
            created.setFirstName("Racer");
            created.setLastName("Admin");
            created.setEmail(adminEmail);
            created.setHireDate(LocalDate.of(2024, 1, 1));
            return employeeRepository.save(created);
        });

        String payeeEmail = "payee" + EMAIL_DOMAIN;
        Employee payee = employeeRepository.findByEmail(payeeEmail).orElseGet(() -> {
            Employee created = new Employee();
            created.setDepartment(department);
            created.setFirstName("Payee");
            created.setLastName("Tester");
            created.setEmail(payeeEmail);
            created.setHireDate(LocalDate.of(2024, 1, 1));
            return employeeRepository.save(created);
        });
        employeeId = payee.getId();

        UserAccount account = userAccountRepository.findByEmail(adminEmail).orElseGet(() -> {
            Role role = roleRepository.findByCode(RoleCode.PAYROLL_ADMIN).orElseThrow();
            UserAccount created = new UserAccount();
            created.setEmail(adminEmail);
            created.setRole(role);
            created.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
            created.setActive(true);
            created.setEmployee(adminEmployee);
            return userAccountRepository.save(created);
        });

        principal = new AuthPrincipal(account.getId(), adminEmployee.getId(), adminEmail, RoleCode.PAYROLL_ADMIN);
    }

    @Test
    void concurrentSubmitsOfTheSameDraftRun_exactlyOneWins() throws Exception {
        PayrollRunResponse run = payrollService.createRun(
                new CreatePayrollRunRequest(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 15)));
        payrollService.createItem(run.id(), new CreatePayrollItemRequest(employeeId, 5000L, 0L, 0L));
        Long id = run.id();

        CyclicBarrier barrier = new CyclicBarrier(RACER_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(RACER_COUNT);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACER_COUNT; i++) {
            racers.add(() -> {
                barrier.await();
                return payrollService.submit(principal, id);
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
                .as("exactly one of %d concurrent submits of the same draft run should win", RACER_COUNT)
                .isEqualTo(1);
        assertThat(conflictCount)
                .as("every other racer should lose with a ConflictException (409), not a silent success or a 500")
                .isEqualTo(RACER_COUNT - 1);

        assertThat(payrollRunRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(PayrollRunStatus.SUBMITTED);

        long submittedEventCount = approvalEventRepository.findAll().stream()
                .filter(e -> e.getEntityType() == ApprovalEntityType.PAYROLL_RUN)
                .filter(e -> e.getEntityId().equals(id))
                .filter(e -> e.getAction() == ApprovalAction.SUBMITTED)
                .count();
        assertThat(submittedEventCount)
                .as("exactly one SUBMITTED approval_events row for this run")
                .isEqualTo(1);
    }
}
