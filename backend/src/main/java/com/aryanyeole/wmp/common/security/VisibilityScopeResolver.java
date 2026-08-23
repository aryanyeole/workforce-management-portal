package com.aryanyeole.wmp.common.security;

import org.springframework.stereotype.Component;

/**
 * Maps a principal's role to the read-visibility scope used by GET/listing
 * endpoints. See VisibilityScope for why mutation endpoints don't use this.
 */
@Component
public class VisibilityScopeResolver {

    public VisibilityScope resolve(AuthPrincipal principal) {
        return switch (principal.role()) {
            case EMPLOYEE -> new VisibilityScope.Self(principal.employeeId());
            case MANAGER -> new VisibilityScope.SelfAndManagedTeam(principal.employeeId());
            case PAYROLL_ADMIN, HR_ADMIN, SYSTEM -> new VisibilityScope.Unrestricted();
        };
    }
}
