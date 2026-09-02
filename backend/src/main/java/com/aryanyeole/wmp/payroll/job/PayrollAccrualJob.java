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
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aryanyeole.wmp.common.logging.CorrelationId;
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
 * Phase 8 (see docs/incidents/2026-08-payroll-500s.md) deliberately shipped
 * this upsert with every 5th employee's connection never closed, to
 * reproduce and diagnose pool exhaustion under real traffic. That defect
 * is fixed here (Task 4): {@link #upsert} acquires its connection with
 * try-with-resources, so every employee's connection is returned to the
 * pool regardless of outcome. The regression test (PayrollAccrualJobLeakIT)
 * asserts on Hikari's own pool state after a run completes, not on this
 * class's source text, and was verified to fail against the leaky version
 * before this fix landed.
 *
 * {@link #run()} tags every log line it produces with a fresh per-run ID
 * under the same MDC key request-scoped correlation IDs use (see
 * {@link com.aryanyeole.wmp.common.logging.CorrelationId}) — there's no
 * inbound request to correlate a scheduled run against, so the run gets an
 * identity of its own instead.
 */
@Component
public class PayrollAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(PayrollAccrualJob.class);

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
     * on-demand entry point.
     */
    @Scheduled(cron = "${wmp.payroll.accrual.cron:0 0 2 * * *}")
    public void runScheduled() {
        run();
    }

    /**
     * On-demand entry point — exposed via POST /actuator/payroll-accrual
     * (PayrollAccrualEndpoint) so an operator (or a test) can run it without
     * waiting for a cron window.
     */
    public AccrualRunResult run() {
        // No inbound request to correlate this against — a scheduled run has
        // no caller. A fresh ID per run under the same MDC key
        // CorrelationIdFilter uses for requests is what makes a Phase
        // 8-shaped incident traceable after the fact: this run's own
        // structured log lines share one ID from start to finish, bounding
        // its execution window in the log stream, and any request that
        // failed while this run had the connection pool starved shows up
        // right alongside it with its own (different) correlation ID and an
        // overlapping timestamp — there is no single ID linking the two,
        // because a job run and a request it happens to starve are
        // genuinely different logical operations; timestamp proximity
        // between two clearly-bounded, independently-searchable IDs is what
        // makes the link visible, not a shared identifier pretending they're
        // one operation.
        // Saved and restored, not just removed in the finally below: run()
        // has two callers on two different kinds of thread. The scheduled
        // trigger's thread has nothing else in MDC, so remove/restore are
        // equivalent there — but PayrollAccrualEndpoint's on-demand trigger
        // calls this synchronously from inside an HTTP request thread that
        // CorrelationIdFilter already tagged with that *request's* own ID.
        // An unconditional remove would erase that request's ID from MDC
        // for whatever runs on this thread after run() returns and before
        // the filter's own cleanup — restoring it here means the on-demand
        // path's own request logs stay correctly correlated after this
        // method hands back control.
        String previousId = MDC.get(CorrelationId.MDC_KEY);
        String jobId = "accrual-" + UUID.randomUUID();
        MDC.put(CorrelationId.MDC_KEY, jobId);
        try {
            LocalDate today = LocalDate.now();
            LocalDate periodStart = today.withDayOfMonth(1);
            LocalDate periodEnd = today.withDayOfMonth(today.lengthOfMonth());
            int daysElapsed = today.getDayOfMonth();
            int daysInPeriod = today.lengthOfMonth();

            List<Employee> activeEmployees = employeeRepository.findByEmploymentStatusAndDeletedAtIsNull(
                    EmploymentStatus.ACTIVE);

            log.info("Payroll accrual run starting: period={}..{}, activeEmployees={}",
                    periodStart, periodEnd, activeEmployees.size());

            int processed = 0;
            for (Employee employee : activeEmployees) {
                BigDecimal accrued = estimateAccrued(employee.getId(), daysElapsed, daysInPeriod);
                upsert(employee.getId(), periodStart, periodEnd, accrued);
                processed++;
            }

            log.info("Payroll accrual run complete: period={}..{}, employeesProcessed={}",
                    periodStart, periodEnd, processed);
            return new AccrualRunResult(periodStart, periodEnd, processed);
        } finally {
            if (previousId != null) {
                MDC.put(CorrelationId.MDC_KEY, previousId);
            } else {
                MDC.remove(CorrelationId.MDC_KEY);
            }
        }
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

    private void upsert(Long employeeId, LocalDate periodStart, LocalDate periodEnd, BigDecimal accrued) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            bind(statement, employeeId, periodStart, periodEnd, accrued);
            statement.executeUpdate();
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

    /** Thrown by the upsert path on a genuine SQL failure. */
    static final class PayrollAccrualUpsertException extends RuntimeException {
        PayrollAccrualUpsertException(Long employeeId, SQLException cause) {
            super("Payroll accrual upsert failed for employee " + employeeId, cause);
        }
    }

    public record AccrualRunResult(LocalDate periodStart, LocalDate periodEnd, int employeesProcessed) {
    }
}
