package com.aryanyeole.wmp.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.aryanyeole.wmp.auth.domain.RoleCode;
import com.aryanyeole.wmp.common.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private static final String CLAIM_EMPLOYEE_ID = "employeeId";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
    }

    public String issueAccessToken(AuthPrincipal principal) {
        return issue(principal, "access", properties.accessTokenTtl());
    }

    public String issueRefreshToken(AuthPrincipal principal) {
        return issue(principal, "refresh", properties.refreshTokenTtl());
    }

    private String issue(AuthPrincipal principal, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(principal.userAccountId()))
                .claim(CLAIM_EMPLOYEE_ID, principal.employeeId())
                .claim(CLAIM_EMAIL, principal.email())
                .claim(CLAIM_ROLE, principal.role().name())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature, issuer, and expiry, then rebuilds the principal.
     *
     * @throws JwtException if the token is malformed, expired, wrongly signed,
     *                      or is a refresh token presented as an access token
     */
    public AuthPrincipal parseAccessToken(String token) {
        return parse(token, "access", "Refresh token presented where access token required");
    }

    /**
     * Mirrors {@link #parseAccessToken(String)} but requires typ=refresh, so an
     * access token cannot be replayed against the refresh endpoint either.
     *
     * @throws JwtException if the token is malformed, expired, wrongly signed,
     *                      or is an access token presented as a refresh token
     */
    public AuthPrincipal parseRefreshToken(String token) {
        return parse(token, "refresh", "Access token presented where refresh token required");
    }

    private AuthPrincipal parse(String token, String expectedType, String typeMismatchMessage) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token);

        Claims claims = jws.getPayload();

        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException(typeMismatchMessage);
        }

        Number employeeId = claims.get(CLAIM_EMPLOYEE_ID, Number.class);

        return new AuthPrincipal(
                Long.valueOf(claims.getSubject()),
                employeeId == null ? null : employeeId.longValue(),
                claims.get(CLAIM_EMAIL, String.class),
                RoleCode.valueOf(claims.get(CLAIM_ROLE, String.class)));
    }
}