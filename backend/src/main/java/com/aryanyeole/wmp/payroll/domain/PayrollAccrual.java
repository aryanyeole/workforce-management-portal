package com.aryanyeole.wmp.payroll.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.aryanyeole.wmp.common.domain.BaseEntity;
import com.aryanyeole.wmp.onboarding.domain.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per employee per period — see V8 migration. Read-only from the
 * application's point of view; every write goes through
 * PayrollAccrualJob's raw JDBC upsert, never through this entity's
 * repository (see the job for why). Exists mainly so the regression test
 * (Phase 8 Task 4) and any future read endpoint can query the result via
 * JPA without hand-rolling SQL themselves.
 */
@Entity
@Table(name = "payroll_accruals")
@Getter
@Setter
@NoArgsConstructor
public class PayrollAccrual extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "accrued_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal accruedAmount;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
