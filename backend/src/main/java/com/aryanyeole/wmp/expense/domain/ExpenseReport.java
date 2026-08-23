package com.aryanyeole.wmp.expense.domain;

import com.aryanyeole.wmp.auth.domain.UserAccount;
import com.aryanyeole.wmp.common.domain.BaseEntity;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "expense_reports")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ExpenseCategory category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "USD";

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private ExpenseStatus status = ExpenseStatus.DRAFT;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private UserAccount approver;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Soft delete (V2 migration) — null means not deleted. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
