package com.aryanyeole.wmp.expense.repository;

import java.time.Instant;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import com.aryanyeole.wmp.common.repository.VisibilityScopeSpecifications;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

/** Composable query predicates for ExpenseReport — see ExpenseService for how these combine. */
public final class ExpenseSpecifications {

    private ExpenseSpecifications() {
    }

    public static Specification<ExpenseReport> visibleTo(VisibilityScope scope) {
        return VisibilityScopeSpecifications.forEmployeePath(scope, root -> root.get("employee"));
    }

    public static Specification<ExpenseReport> hasId(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /** Every normal query excludes soft-deleted rows — see V2 migration. */
    public static Specification<ExpenseReport> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<ExpenseReport> hasStatus(ExpenseStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Keyset predicate for (submitted_at DESC, id DESC): rows strictly
     * "after" the cursor in that order.
     *
     * This is NOT the boolean expansion `submittedAt < cursorAt OR
     * (submittedAt = cursorAt AND id < cursorId)` it started as. That form
     * is logically equivalent but measured to compile to a Filter, not an
     * Index Cond: EXPLAIN showed Postgres scanning from the start of the
     * index and discarding every preceding row one at a time (~20k rows
     * "Rows Removed by Filter" at a deep cursor), because the planner does
     * not recognize an OR-of-ANDs as a seekable range on the index's
     * columns, only genuine row-value comparison syntax
     * `(a, b) < (x, y)` gets that treatment. JPA's CriteriaBuilder has no
     * row-value constructor, so this uses Hibernate's `sql(...)` escape
     * hatch to emit that literal comparison — verified this compiles to an
     * Index Cond with "Rows Removed by Filter" in the single digits at the
     * same deep cursor, not the thousands (see docs/measurements.md, Phase
     * 6 Task C).
     *
     * submittedAt is passed as its ISO-8601 string form, cast with
     * `::timestamptz` in the SQL text, rather than as a literal Instant.
     * cb.literal(Instant) renders as a bare `timestamp '...'` (no zone);
     * casting THAT to timestamptz reinterprets the already-zoneless value
     * using the session's local timezone rather than UTC, silently
     * shifting the boundary and letting the cursor's own row leak back
     * into the "before" side. A string literal ending in "Z" cast directly
     * to timestamptz is parsed as UTC regardless of session timezone —
     * verified by confirming the boundary row is excluded at a cursor
     * pointing exactly at it (see docs/measurements.md, Phase 6 Task C).
     */
    public static Specification<ExpenseReport> beforeCursor(Instant submittedAt, Long id) {
        return (root, query, cb) -> ((HibernateCriteriaBuilder) cb).isTrue(
                ((HibernateCriteriaBuilder) cb).sql(
                        "(?, ?) < (?::timestamptz, ?)", Boolean.class,
                        root.get("submittedAt"), root.get("id"),
                        cb.literal(submittedAt.toString()), cb.literal(id)));
    }
}
