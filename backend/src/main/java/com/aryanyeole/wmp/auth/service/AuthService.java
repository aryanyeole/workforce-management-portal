package com.aryanyeole.wmp.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.auth.api.AuthResponse;
import com.aryanyeole.wmp.auth.api.LoginRequest;
import com.aryanyeole.wmp.auth.api.RefreshRequest;
import com.aryanyeole.wmp.auth.domain.UserAccount;
import com.aryanyeole.wmp.auth.repository.UserAccountRepository;
import com.aryanyeole.wmp.common.config.JwtProperties;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.security.JwtService;
import com.aryanyeole.wmp.common.web.UnauthorizedException;

import io.jsonwebtoken.JwtException;

/**
 * Login and token refresh. No role checks live here — that is
 * RouteAuthorizationFilter's job once a token exists.
 */
@Service
public class AuthService {

    /**
     * Identical message for "no such account" and "wrong password" so a
     * failed login never reveals which one was wrong.
     */
    private static final String LOGIN_FAILURE_MESSAGE = "Invalid email or password";
    private static final String REFRESH_FAILURE_MESSAGE = "Invalid or expired refresh token";
    private static final String TOKEN_TYPE = "Bearer";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(UserAccountRepository userAccountRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        JwtProperties jwtProperties) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Read-only transaction, not just a boundary convention: employee and
     * role are lazy associations, and open-in-view is off, so touching them
     * in toPrincipal() after the repository call returns would otherwise
     * throw LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        UserAccount account = userAccountRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException(LOGIN_FAILURE_MESSAGE));

        if (!account.isActive() || !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new UnauthorizedException(LOGIN_FAILURE_MESSAGE);
        }

        return issueTokens(toPrincipal(account));
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        AuthPrincipal principal;
        try {
            principal = jwtService.parseRefreshToken(request.refreshToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException(REFRESH_FAILURE_MESSAGE);
        }

        // Re-check the account rather than trusting the token's claims: a
        // refresh token issued before the account was disabled must not keep
        // minting fresh access tokens for its remaining 7-day lifetime.
        UserAccount account = userAccountRepository.findById(principal.userAccountId())
                .filter(UserAccount::isActive)
                .orElseThrow(() -> new UnauthorizedException(REFRESH_FAILURE_MESSAGE));

        return issueTokens(toPrincipal(account));
    }

    private AuthResponse issueTokens(AuthPrincipal principal) {
        String accessToken = jwtService.issueAccessToken(principal);
        String refreshToken = jwtService.issueRefreshToken(principal);
        return new AuthResponse(
                accessToken,
                refreshToken,
                TOKEN_TYPE,
                jwtProperties.accessTokenTtl().toSeconds(),
                principal.role(),
                principal.employeeId());
    }

    private AuthPrincipal toPrincipal(UserAccount account) {
        Long employeeId = account.getEmployee() == null ? null : account.getEmployee().getId();
        return new AuthPrincipal(account.getId(), employeeId, account.getEmail(), account.getRole().getCode());
    }
}
