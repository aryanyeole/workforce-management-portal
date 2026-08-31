package com.aryanyeole.wmp.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.aryanyeole.wmp.auth.domain.RoleCode;
import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.onboarding.api.EmployeeResponse;
import com.aryanyeole.wmp.onboarding.api.UpdateEmployeeRequest;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

/**
 * Regression test for the read-check-then-write race in
 * EmployeeService.update's employmentStatus branch — flagged but not fixed
 * during Phase 10 Task 0 (docs: same bug class as
 * ExpenseSubmitConcurrencyIT/PayrollSubmitConcurrencyIT, different failure
 * mode: no approval_events table involved here, so the symptom is a
 * misleading 200 to the loser rather than a duplicate row). Fixed in Phase
 * 10 Task 0b via EmployeeRepository.compareAndSetStatus.
 *
 * Same determinism technique as the other two: real threads, released
 * together by a CyclicBarrier, no sleeps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeUpdateConcurrencyIT extends AbstractIntegrationTest {

    private static final int RACER_COUNT = 10;
    private static final String EMAIL_DOMAIN = "@wmp-employeeupdaterace.dev";

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    private AuthPrincipal principal;
    private Department department;

    @BeforeAll
    void setUp() {
        department = departmentRepository.findByName("Employee Update Race Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Employee Update Race Test Dept");
            return departmentRepository.save(created);
        });
        // HR_ADMIN is Unrestricted (VisibilityScopeResolver) -- no employee
        // record of its own is needed to exercise requireVisible.
        principal = new AuthPrincipal(1L, null, "racer" + EMAIL_DOMAIN, RoleCode.HR_ADMIN);
    }

    private Employee newPendingEmployee(String slug) {
        Employee employee = new Employee();
        employee.setDepartment(department);
        employee.setFirstName(slug);
        employee.setLastName("Tester");
        employee.setEmail(slug + "-" + System.nanoTime() + EMAIL_DOMAIN);
        employee.setHireDate(LocalDate.of(2024, 1, 1));
        // Entity default is already PENDING; set explicitly so the fixture
        // doesn't depend on that default silently changing later.
        employee.setEmploymentStatus(EmploymentStatus.PENDING);
        return employeeRepository.save(employee);
    }

    @Test
    void concurrentEmploymentStatusTransitions_exactlyOneWins() throws Exception {
        Employee employee = newPendingEmployee("racer-status-only");
        Long id = employee.getId();

        CyclicBarrier barrier = new CyclicBarrier(RACER_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(RACER_COUNT);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACER_COUNT; i++) {
            racers.add(() -> {
                barrier.await();
                return employeeService.update(principal, id,
                        new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.ACTIVE));
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
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ConflictException) {
                    conflictCount++;
                } else {
                    throw new AssertionError("Unexpected failure from a racer", e.getCause());
                }
            }
        }

        assertThat(successCount)
                .as("exactly one of %d concurrent PENDING->ACTIVE updates should win", RACER_COUNT)
                .isEqualTo(1);
        assertThat(conflictCount)
                .as("every other racer should lose with a ConflictException (409), not a silent 200")
                .isEqualTo(RACER_COUNT - 1);

        assertThat(employeeRepository.findById(id).orElseThrow().getEmploymentStatus())
                .isEqualTo(EmploymentStatus.ACTIVE);
    }

    /**
     * Requirement 2: when the employmentStatus compare-and-swap loses, NO
     * other field from that same PATCH may persist either -- not just that
     * the transaction boundary probably covers it. Two racers each patch
     * employmentStatus (same target, so exactly one is a genuine race loser
     * regardless of scheduling) AND a distinct, individually-identifiable
     * firstName in the same request. Whichever racer's own success/failure
     * result comes back determines what its firstName SHOULD be if (and
     * only if) it won; the test asserts the loser's firstName never reached
     * the database, regardless of which of the two actually won.
     */
    @Test
    void losingRaceLeavesOtherPatchedFieldsUntouched() throws Exception {
        Employee employee = newPendingEmployee("racer-with-firstname");
        Long id = employee.getId();
        String originalFirstName = employee.getFirstName();

        String firstNameA = "RacerA-" + System.nanoTime();
        String firstNameB = "RacerB-" + System.nanoTime();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<EmployeeResponse> racerA = () -> {
            barrier.await();
            return employeeService.update(principal, id,
                    new UpdateEmployeeRequest(firstNameA, null, null, null, null, EmploymentStatus.ACTIVE));
        };
        Callable<EmployeeResponse> racerB = () -> {
            barrier.await();
            return employeeService.update(principal, id,
                    new UpdateEmployeeRequest(firstNameB, null, null, null, null, EmploymentStatus.ACTIVE));
        };

        Future<EmployeeResponse> futureA = executor.submit(racerA);
        Future<EmployeeResponse> futureB = executor.submit(racerB);

        EmployeeResponse resultA = getResultOrNull(futureA);
        EmployeeResponse resultB = getResultOrNull(futureB);
        executor.shutdown();

        // Exactly one of the two must have won (the fix guarantees this --
        // see the other @Test in this class for the dedicated assertion).
        boolean aWon = resultA != null;
        boolean bWon = resultB != null;
        assertThat(aWon ^ bWon).as("exactly one of the two racers should win").isTrue();

        String winningFirstName = aWon ? firstNameA : firstNameB;
        String losingFirstName = aWon ? firstNameB : firstNameA;

        Employee finalState = employeeRepository.findById(id).orElseThrow();
        assertThat(finalState.getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(finalState.getFirstName())
                .as("the winner's own firstName should be the one persisted")
                .isEqualTo(winningFirstName);
        assertThat(finalState.getFirstName())
                .as("the loser's firstName must NOT have persisted, even though it was in the same request as employmentStatus")
                .isNotEqualTo(losingFirstName)
                .isNotEqualTo(originalFirstName); // sanity: the winner really did apply, this isn't a no-op
    }

    private static <T> T getResultOrNull(Future<T> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ConflictException) {
                return null;
            }
            throw new AssertionError("Unexpected failure from a racer", e.getCause());
        }
    }
}
