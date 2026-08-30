package com.aryanyeole.wmp.payroll.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * Regression test for the Phase 8 connection leak
 * (docs/incidents/2026-08-payroll-500s.md): asserts on Hikari's own pool
 * state after a real job run, not on PayrollAccrualJob's source text —
 * this must fail against the leaky version and pass against the fixed one.
 *
 * Determinism without a sleep-and-hope wait: {@link PayrollAccrualJob#run()}
 * is called directly and synchronously (no HTTP layer, no scheduler
 * thread), so by the time it returns, every connection it was ever going
 * to open has either been returned to the pool (fixed version) or is still
 * checked out (leaky version) — there is nothing to wait for. The fixture
 * creates EMPLOYEE_COUNT (15) ACTIVE employees itself, guaranteeing the job
 * processes at least that many regardless of what other IT classes'
 * fixtures also created as ACTIVE employees against the same shared
 * Testcontainers Postgres (more active employees only means more, not
 * fewer, leaked connections on the broken version — never breaks this
 * test's assertion either way).
 *
 * {@code @ActiveProfiles("leak-test")} pulls in application-leak-test.yml
 * (maximum-pool-size=3), giving this class its own isolated Spring
 * context/HikariDataSource — same singleton Testcontainers Postgres
 * (AbstractIntegrationTest), just a separate, deliberately tiny pool, so a
 * fully-exhausted pool here can't affect any other IT class's shared
 * context. 15 employees at the job's (now-removed) 1-in-5 leak rate would
 * have leaked 3 connections — exactly this pool's capacity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("leak-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayrollAccrualJobLeakIT extends AbstractIntegrationTest {

    private static final int EMPLOYEE_COUNT = 15;
    private static final String EMAIL_DOMAIN = "@wmp-leaktest.dev";

    @Autowired
    private PayrollAccrualJob payrollAccrualJob;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @BeforeAll
    void setUp() {
        Department department = departmentRepository.findByName("Leak Test Dept").orElseGet(() -> {
            Department created = new Department();
            created.setName("Leak Test Dept");
            return departmentRepository.save(created);
        });

        for (int i = 0; i < EMPLOYEE_COUNT; i++) {
            String email = "leak-test-" + i + EMAIL_DOMAIN;
            if (employeeRepository.findByEmail(email).isPresent()) {
                continue;
            }
            Employee employee = new Employee();
            employee.setDepartment(department);
            employee.setFirstName("leak-test-" + i);
            employee.setLastName("Employee");
            employee.setEmail(email);
            employee.setHireDate(LocalDate.of(2024, 1, 1));
            employee.setEmploymentStatus(EmploymentStatus.ACTIVE);
            employeeRepository.save(employee);
        }
    }

    @Test
    void jobDoesNotLeakConnections() {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();

        // Sanity: nothing else is holding a connection before the job runs.
        assertThat(pool.getActiveConnections())
                .as("pool should be clean before the job runs")
                .isZero();

        payrollAccrualJob.run();

        assertThat(pool.getActiveConnections())
                .as("no connection should still be checked out once the job has returned")
                .isZero();
    }
}
