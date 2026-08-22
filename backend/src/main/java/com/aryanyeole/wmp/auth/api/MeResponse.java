package com.aryanyeole.wmp.auth.api;

import com.aryanyeole.wmp.auth.domain.RoleCode;

public record MeResponse(
        Long userAccountId,
        Long employeeId,
        String email,
        RoleCode role) {
}
