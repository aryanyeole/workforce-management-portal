package com.aryanyeole.wmp.common.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security's Authentication wrapper around our AuthPrincipal.
 *
 * The ROLE_ prefixed authority exists only so that Spring's own machinery and
 * any future @PreAuthorize usage stay coherent; route authorization in this
 * application is decided by PermissionRegistry, not by these authorities.
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthPrincipal principal;

    public JwtAuthenticationToken(AuthPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthPrincipal getPrincipal() {
        return principal;
    }
}