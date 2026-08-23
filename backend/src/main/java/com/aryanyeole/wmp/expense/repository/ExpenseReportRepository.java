package com.aryanyeole.wmp.expense.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

public interface ExpenseReportRepository
        extends JpaRepository<ExpenseReport, Long>, JpaSpecificationExecutor<ExpenseReport> {

    /**
     * Pending-approval queue for the calling approver, newest submission
     * first. approverScope is the caller's VisibilityScope already turned
     * into a Specification (ExpenseSpecifications.visibleTo) — not
     * duplicated here so this stays a thin composition, not a second place
     * that decides who sees what.
     *
     * INTENTIONAL: classic LIMIT/OFFSET pagination via Pageable. ROADMAP.md
     * Phase 6 seeds 50k+ expense reports, measures this query at depth
     * (page 1/100/500/1000), and only then replaces it with keyset
     * pagination (opaque cursor over (submitted_at, id)). Do not switch to
     * keyset, and do not add a composite or covering index, before that
     * phase — the before/after comparison is the deliverable.
     */
    default Page<ExpenseReport> findPendingApprovals(Specification<ExpenseReport> approverScope, Pageable pageable) {
        Specification<ExpenseReport> spec = Specification
                .where(ExpenseSpecifications.notDeleted())
                .and(ExpenseSpecifications.hasStatus(ExpenseStatus.SUBMITTED))
                .and(approverScope);
        return findAll(spec, pageable);
    }
}
