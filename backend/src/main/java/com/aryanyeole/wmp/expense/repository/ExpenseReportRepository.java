package com.aryanyeole.wmp.expense.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

public interface ExpenseReportRepository
        extends JpaRepository<ExpenseReport, Long>, JpaSpecificationExecutor<ExpenseReport> {

    /**
     * Compare-and-swap on status: only writes if the row's status is still
     * exactly {@code expected} at UPDATE time. Returns the number of rows
     * changed (0 or 1) — the caller's own signal for "did I win the race,"
     * closing the read-check-then-write window a plain
     * {@code report.setStatus(...)} + Hibernate's own dirty-checking flush
     * leaves open (see ExpenseService.decide/submit and Phase 10 Task 0's
     * commit message for the race this exists to close).
     *
     * Deliberately NOT {@code clearAutomatically = true}: that would detach
     * every entity already loaded in this transaction, and ExpenseMapper.
     * toResponse reads lazy (FetchType.LAZY) associations like
     * category.getName() that would throw LazyInitializationException once
     * detached. The caller re-sets the same field on its own (still
     * attached) entity right after a successful call here; Hibernate's
     * normal flush then issues one harmless, idempotent extra UPDATE of the
     * same value — accepted as a small, understood cost rather than
     * chasing a fully write-once path.
     */
    @Modifying
    @Query("UPDATE ExpenseReport e SET e.status = :next WHERE e.id = :id AND e.status = :expected")
    int compareAndSetStatus(@Param("id") Long id, @Param("expected") ExpenseStatus expected, @Param("next") ExpenseStatus next);
}
