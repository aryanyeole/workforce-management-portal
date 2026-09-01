package com.aryanyeole.wmp.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
 * EmployeeService.update's employmentStatus branch (Phase 10 Task 0b, fixed
 * via EmployeeRepository.compareAndSetStatus).
 *
 * Redesigned (Phase 10 Task 0c) after the original same-target version
 * (both racers requesting ACTIVE) flaked in CI: "expected: 1 but was: 2".
 * That was not a regression -- confirmed by a throwaway diagnostic (not
 * committed) that forced the exact interleaving deterministically via a
 * CountDownLatch rather than hoping for it: a racer whose own read happens
 * after another's commit sees current == target and takes
 * EmployeeTransitions' own deliberate idempotency no-op, producing a
 * truthful second 200. A same-target race literally cannot distinguish
 * fixed from unfixed, because no 200 is misleading when every racer asked
 * for the state the row ends up in -- an "at least one success" assertion
 * (considered and rejected) would stay green against the unguarded,
 * pre-fix code for exactly the same reason.
 *
 * The tests below race DIFFERENT targets instead (ACTIVE -> ON_LEAVE vs.
 * ACTIVE -> TERMINATED, neither reachable from the other from the shared
 * ACTIVE start), which is what actually distinguishes the fix from its
 * absence. Determinism doesn't come from CyclicBarrier release timing
 * alone -- a racer whose read merely happens late enough to observe the
 * OTHER racer's already-committed status can still legally continue from
 * there (ON_LEAVE -> TERMINATED is real), reproducing an equally
 * ambiguous result. Instead, a @MockitoSpyBean on EmployeeService gates
 * each racer's first call to requireVisible on a second, inner
 * CyclicBarrier -- forcing both racers' actual reads to happen together,
 * while the row is still ACTIVE, before either has written -- so the
 * write step is a genuine, unambiguous two-way race decided by Postgres's
 * row lock, not by scheduling luck. No production code changed to add
 * this seam; the gate lives entirely in the spy stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeUpdateConcurrencyIT extends AbstractIntegrationTest {

    private static final String EMAIL_DOMAIN = "@wmp-employeeupdaterace.dev";

    @MockitoSpyBean
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

    private Employee newEmployee(String slug, EmploymentStatus startingStatus) {
        Employee employee = new Employee();
        employee.setDepartment(department);
        employee.setFirstName(slug);
        employee.setLastName("Tester");
        employee.setEmail(slug + "-" + System.nanoTime() + EMAIL_DOMAIN);
        employee.setHireDate(LocalDate.of(2024, 1, 1));
        employee.setEmploymentStatus(startingStatus);
        return employeeRepository.save(employee);
    }

    /**
     * Gates every racer's FIRST call to requireVisible on a `parties`-way
     * CyclicBarrier before letting it run for real, so all racers' actual
     * reads happen together rather than at whatever moment their thread
     * happens to be scheduled. Only the first call per thread is gated --
     * a racer that loses its compare-and-swap calls requireVisible a
     * second time (the re-check in compareAndSetStatusOrConflict), which
     * must run unimpeded or a thread that already finished leaves the
     * barrier permanently short a party.
     */
    private void gateFirstReadOnBarrier(int parties) {
        CyclicBarrier readBarrier = new CyclicBarrier(parties);
        ThreadLocal<Boolean> hasReadOnce = new ThreadLocal<>();
        Mockito.doAnswer(invocation -> {
            if (hasReadOnce.get() == null) {
                hasReadOnce.set(true);
                readBarrier.await(10, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(employeeService).requireVisible(Mockito.any(), Mockito.anyLong());
    }

    private record Outcome(boolean succeeded, EmploymentStatus resultStatus) {
    }

    private static Outcome outcomeOf(Future<EmployeeResponse> future) throws Exception {
        try {
            EmployeeResponse response = future.get(30, TimeUnit.SECONDS);
            return new Outcome(true, response.employmentStatus());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ConflictException) {
                return new Outcome(false, null);
            }
            throw e;
        }
    }

    @Test
    void concurrentDifferentTargetTransitions_exactlyOneWinsAndFinalStateMatchesIt() throws Exception {
        Employee employee = newEmployee("racer-diff-targets", EmploymentStatus.ACTIVE);
        Long id = employee.getId();

        gateFirstReadOnBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<EmployeeResponse> toOnLeave = executor.submit(() -> employeeService.update(principal, id,
                new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.ON_LEAVE)));
        Future<EmployeeResponse> toTerminated = executor.submit(() -> employeeService.update(principal, id,
                new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.TERMINATED)));

        Outcome onLeave = outcomeOf(toOnLeave);
        Outcome terminated = outcomeOf(toTerminated);
        executor.shutdown();

        assertThat(onLeave.succeeded() ^ terminated.succeeded())
                .as("exactly one of the two different-target racers should win")
                .isTrue();

        EmploymentStatus finalStatus = employeeRepository.findById(id).orElseThrow().getEmploymentStatus();
        if (onLeave.succeeded()) {
            assertThat(onLeave.resultStatus()).isEqualTo(EmploymentStatus.ON_LEAVE);
            assertThat(finalStatus)
                    .as("the winner's own claimed target must be the row's real final state")
                    .isEqualTo(EmploymentStatus.ON_LEAVE);
        } else {
            assertThat(terminated.resultStatus()).isEqualTo(EmploymentStatus.TERMINATED);
            assertThat(finalStatus)
                    .as("the winner's own claimed target must be the row's real final state")
                    .isEqualTo(EmploymentStatus.TERMINATED);
        }
    }

    /**
     * Requirement (Task 0b): when the employmentStatus compare-and-swap
     * loses, NO other field from that same PATCH may persist either.
     * Redesigned onto different targets for the same reason as the test
     * above -- with a shared target, "exactly one winner" can't be told
     * apart from the idempotent no-op case, so this couldn't actually
     * catch the bug it was written for either.
     */
    @Test
    void losingRaceLeavesOtherPatchedFieldsUntouched() throws Exception {
        Employee employee = newEmployee("racer-with-firstname", EmploymentStatus.ACTIVE);
        Long id = employee.getId();
        String originalFirstName = employee.getFirstName();

        String firstNameOnLeave = "RacerOnLeave-" + System.nanoTime();
        String firstNameTerminated = "RacerTerminated-" + System.nanoTime();

        gateFirstReadOnBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<EmployeeResponse> toOnLeave = executor.submit(() -> employeeService.update(principal, id,
                new UpdateEmployeeRequest(firstNameOnLeave, null, null, null, null, EmploymentStatus.ON_LEAVE)));
        Future<EmployeeResponse> toTerminated = executor.submit(() -> employeeService.update(principal, id,
                new UpdateEmployeeRequest(firstNameTerminated, null, null, null, null, EmploymentStatus.TERMINATED)));

        Outcome onLeave = outcomeOf(toOnLeave);
        Outcome terminated = outcomeOf(toTerminated);
        executor.shutdown();

        assertThat(onLeave.succeeded() ^ terminated.succeeded())
                .as("exactly one of the two different-target racers should win")
                .isTrue();

        Employee finalState = employeeRepository.findById(id).orElseThrow();
        if (onLeave.succeeded()) {
            assertThat(finalState.getEmploymentStatus()).isEqualTo(EmploymentStatus.ON_LEAVE);
            assertThat(finalState.getFirstName())
                    .as("the winner's own firstName should be the one persisted")
                    .isEqualTo(firstNameOnLeave)
                    .as("the loser's firstName must NOT have persisted, even though it was in the same request as employmentStatus")
                    .isNotEqualTo(firstNameTerminated);
        } else {
            assertThat(finalState.getEmploymentStatus()).isEqualTo(EmploymentStatus.TERMINATED);
            assertThat(finalState.getFirstName())
                    .as("the winner's own firstName should be the one persisted")
                    .isEqualTo(firstNameTerminated)
                    .as("the loser's firstName must NOT have persisted, even though it was in the same request as employmentStatus")
                    .isNotEqualTo(firstNameOnLeave);
        }
        assertThat(finalState.getFirstName())
                .as("the winner really did apply -- this isn't a no-op")
                .isNotEqualTo(originalFirstName);
    }

    /**
     * No concurrency at all -- confirms two cases the tests above must NOT
     * be confused with, since both are the exact code path a same-target
     * or late-reading racer takes: (1) a second, sequential call to a
     * target the row has already reached succeeds idempotently
     * (EmployeeTransitions' own current==target no-op) -- this is the
     * precise mechanism that produced CI's "expected: 1 but was: 2" on
     * the original same-target version of this test, and it is correct,
     * not a bug; (2) a legitimate continuation across the ACTIVE <->
     * ON_LEAVE cycle, made only after the prior call fully returns, is
     * sequential, not concurrent, and its 200 is truthful. Neither may
     * ever be flagged as a race failure. A genuinely illegal transition
     * must still 409.
     */
    @Test
    void sequentialCallsAfterAnotherCompletes_succeedTruthfullyNotFlaggedAsFailures() {
        Employee employee = newEmployee("racer-sequential", EmploymentStatus.PENDING);
        Long id = employee.getId();

        EmployeeResponse first = employeeService.update(principal, id,
                new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.ACTIVE));
        assertThat(first.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);

        // Same target, made only after the first call fully returned: the
        // idempotent no-op case.
        EmployeeResponse repeat = employeeService.update(principal, id,
                new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.ACTIVE));
        assertThat(repeat.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);

        // Legitimate continuation across the ACTIVE <-> ON_LEAVE cycle.
        EmployeeResponse continuation = employeeService.update(principal, id,
                new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.ON_LEAVE));
        assertThat(continuation.employmentStatus()).isEqualTo(EmploymentStatus.ON_LEAVE);

        // A genuinely illegal transition from the new state must still 409.
        assertThatThrownBy(() -> employeeService.update(principal, id,
                new UpdateEmployeeRequest(null, null, null, null, null, EmploymentStatus.PENDING)))
                .isInstanceOf(ConflictException.class);
    }
}
