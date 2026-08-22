package com.aryanyeole.wmp.auth.api;

import com.aryanyeole.wmp.auth.domain.RoleCode;

/**
 * Response shape shared by login and refresh. Never carries password_hash or
 * any other entity field — built explicitly by AuthService, not mapped.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        RoleCode role,
        Long employeeId) {
}
