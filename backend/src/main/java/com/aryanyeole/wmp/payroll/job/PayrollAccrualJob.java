package com.aryanyeole.wmp.payroll.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import com.aryanyeole.wmp.payroll.repository.PayrollItemRepository;

/**
 * Nightly payroll accrual: for every currently-active employee, estimate
 * how much pay has accrued so far in the current calendar-month period —
 * prorated from their most recent payroll item's net pay — and upsert a
 * summary row into payroll_accruals (see V8 migration).
 *
 * The upsert is written against a raw {@link java.sql.Connection} rather
 * than through Spring Data — a believable real-world choice, since an
 * atomic "insert, or update if a row for this employee+period already
 * exists" is awkward to express in JPA/HQL but is one PreparedStatement
 * with Postgres's {@code INSERT ... ON CONFLICT DO UPDATE}.
 *
 * // INTENTIONAL LEAK — see docs/incidents/payroll-submit-500s.md (Phase 8).
 * Every {@value #LEAK_EVERY_NTH}th employee's connection is obtained via
 * {@link #upsertLeaky} instead of {@link #upsertClean}: no
 * try-with-resources, no finally block — the Connection (and the
 * PreparedStatement opened on it) is simply never closed, so it is never
 * returned to the Hikari pool. This is left in deliberately, for Phase
 * 8's reproduction of pool exhaustion; do not "fix" it outside that
 * phase's own Task 4.
 */
@Component
public class PayrollAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(PayrollAccrualJob.class);

    /** Deliberately not every iteration — see the class javadoc's INTENTIONAL LEAK note. */
    private static final int LEAK_EVERY_NTH = 5;

    private static final String UPSERT_SQL = """
            INSERT INTO payroll_accruals (employee_id, period_start, period_end, accrued_amount, computed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (employee_id, period_start, period_end)
            DO UPDATE SET accrued_amount = EXCLUDED.accrued_amount, computed_at = EXCLUDED.computed_at
            """;

    private final DataSource dataSource;
    private final EmployeeRepository employeeRepository;
    private final PayrollItemRepository payrollItemRepository;

    public PayrollAccrualJob(DataSource dataSource,
                              EmployeeRepository employeeRepository,
                              PayrollItemRepository payrollItemRepository) {
        this.dataSource = dataSource;
        this.employeeRepository = employeeRepository;
        this.payrollItemRepository = payrollItemRepository;
    }

    /**
     * Cron-triggered entry point. Configurable (default: 2 AM daily —
     * chosen so it never fires mid-run in a short-lived test JVM without
     * needing to disable scheduling for tests). See {@link #run()} for the
     * on-demand entry point used by Phase 8's reproduction.
     */
    @Scheduled(cron = "${wmp.payroll.accrual.cron:0 0 2 * * *}")
    public void runScheduled() {
        run();
    }

    /**
     * On-demand entry point — exposed via POST /api/v1/payroll/accrual/run
     * (PayrollAccrualController) so Phase 8's reproduction doesn't have to
     * wait for a cron window.
     */
    public AccrualRunResult run() {
        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.withDayOfMonth(1);
        LocalDate periodEnd = today.withDayOfMonth(today.lengthOfMonth());
        int daysElapsed = today.getDayOfMonth();
        int daysInPeriod = today.lengthOfMonth();

        List<Employee> activeEmployees = employeeRepository.findByEmploymentStatusAndDeletedAtIsNull(
                EmploymentStatus.ACTIVE);

        int processed = 0;
        for (Employee employee : activeEmployees) {
            BigDecimal accrued = estimateAccrued(employee.getId(), daysElapsed, daysInPeriod);

            processed++;
            if (processed % LEAK_EVERY_NTH == 0) {
                upsertLeaky(employee.getId(), periodStart, periodEnd, accrued);
            } else {
                upsertClean(employee.getId(), periodStart, periodEnd, accrued);
            }
        }

        log.info("Payroll accrual run complete: period={}..{}, employeesProcessed={}",
                periodStart, periodEnd, processed);
        return new AccrualRunResult(periodStart, periodEnd, processed);
    }

    /** Prorated from the employee's most recent payroll item; zero if they have none yet. */
    private BigDecimal estimateAccrued(Long employeeId, int daysElapsed, int daysInPeriod) {
        BigDecimal lastNetPay = payrollItemRepository
                .findByEmployeeIdOrderByPayrollRun_PeriodStartDesc(employeeId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(PayrollItem::getNetPay)
                .orElse(BigDecimal.ZERO);

        BigDecimal dailyRate = lastNetPay.divide(BigDecimal.valueOf(daysInPeriod), 2, RoundingMode.HALF_UP);
        return dailyRate.multiply(BigDecimal.valueOf(daysElapsed));
    }

    private void upsertClean(Long employeeId, LocalDate periodStart, LocalDate periodEnd, BigDecimal accrued) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            bind(statement, employeeId, periodStart, periodEnd, accrued);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PayrollAccrualUpsertException(employeeId, e);
        }
    }

    // INTENTIONAL LEAK — see docs/incidents/payroll-submit-500s.md (Phase 8).
    // No try-with-resources and no finally: the Connection returned by
    // getConnection() is never closed on this path, so it never goes back
    // to the Hikari pool. Kept exactly this way for Phase 8's
    // reproduction — do not add resource management here outside that
    // phase's Task 4.
    private void upsertLeaky(Long employeeId, LocalDate periodStart, LocalDate periodEnd, BigDecimal accrued) {
        try {
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(UPSERT_SQL);
            bind(statement, employeeId, periodStart, periodEnd, accrued);
            statement.executeUpdate();
            // connection and statement deliberately never closed here.
        } catch (SQLException e) {
            throw new PayrollAccrualUpsertException(employeeId, e);
        }
    }

    private void bind(PreparedStatement statement, Long employeeId, LocalDate periodStart, LocalDate periodEnd,
                       BigDecimal accrued) throws SQLException {
        statement.setLong(1, employeeId);
        statement.setObject(2, periodStart, Types.DATE);
        statement.setObject(3, periodEnd, Types.DATE);
        statement.setBigDecimal(4, accrued);
        statement.setTimestamp(5, Timestamp.from(Instant.now()));
    }

    /** Thrown by both the clean and leaky upsert paths on a genuine SQL failure — not part of the leak itself. */
    static final class PayrollAccrualUpsertException extends RuntimeException {
        PayrollAccrualUpsertException(Long employeeId, SQLException cause) {
            super("Payroll accrual upsert failed for employee " + employeeId, cause);
        }
    }

    public record AccrualRunResult(LocalDate periodStart, LocalDate periodEnd, int employeesProcessed) {
    }
}
