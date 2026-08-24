package com.aryanyeole.wmp.expense.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Hand-built CriteriaQuery for the pending-approvals queue's keyset page,
 * bypassing JpaSpecificationExecutor.findAll(Specification, Pageable):
 * that overload returns Page&lt;T&gt;, which always issues a second COUNT(*)
 * query to populate totalElements — exactly the per-request cost keyset
 * pagination exists to avoid. This still composes the same
 * ExpenseSpecifications used everywhere else (VisibilityScope included),
 * just invoked directly against a manually-built query instead of through
 * that convenience method.
 */
@Repository
public class ExpenseApprovalsKeysetRepository {

    private final EntityManager entityManager;

    public ExpenseApprovalsKeysetRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Fetches up to (limit + 1) rows ordered by (submitted_at DESC, id
     * DESC) so the caller can tell whether more rows remain — a
     * one-larger-than-requested fetch, not a second query — without ever
     * counting the full matching set.
     *
     * Takes the raw (cursorSubmittedAt, cursorId) pair rather than the
     * service layer's ApprovalsCursor type — both null together for the
     * first page — so this class deals only in query terms, not in the
     * "cursor" concept (encoding/decoding is ExpenseService's concern).
     */
    public List<ExpenseReport> findPage(Specification<ExpenseReport> approverScope,
                                         Instant cursorSubmittedAt, Long cursorId, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ExpenseReport> query = cb.createQuery(ExpenseReport.class);
        Root<ExpenseReport> root = query.from(ExpenseReport.class);

        Specification<ExpenseReport> spec = Specification
                .where(ExpenseSpecifications.notDeleted())
                .and(ExpenseSpecifications.hasStatus(ExpenseStatus.SUBMITTED))
                .and(approverScope);
        if (cursorSubmittedAt != null) {
            spec = spec.and(ExpenseSpecifications.beforeCursor(cursorSubmittedAt, cursorId));
        }

        Predicate predicate = spec.toPredicate(root, query, cb);
        query.where(predicate);
        query.orderBy(cb.desc(root.get("submittedAt")), cb.desc(root.get("id")));

        return entityManager.createQuery(query)
                .setMaxResults(limit + 1)
                .getResultList();
    }
}
