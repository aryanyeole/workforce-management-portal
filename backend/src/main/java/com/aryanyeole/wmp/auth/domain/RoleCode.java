package com.aryanyeole.wmp.auth.domain;

/**
 * Mirrors the CHECK constraint on roles.code. Fixed set defined by the
 * platform, not user-managed — see ROADMAP Phase 2 for the permission map
 * that keys off this.
 */
public enum RoleCode {
    EMPLOYEE,
    MANAGER,
    PAYROLL_ADMIN,
    HR_ADMIN,
    SYSTEM
}
