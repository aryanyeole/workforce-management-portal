package com.aryanyeole.wmp.common.security;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication only: turns a Bearer token into a populated SecurityContext.
 * Makes no authorization decision — a request with no token passes through
 * unauthenticated and is rejected (or not) by RouteAuthorizationFilter.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);

        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthPrincipal principal = jwtService.parseAccessToken(header.substring(PREFIX.length()));
            SecurityContextHolder.getContext()
                    .setAuthentication(new JwtAuthenticationToken(principal));
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            SecurityResponses.write(response, 401, "Unauthorized", "Invalid or expired token");
            return;
        }

        filterChain.doFilter(request, response);
    }
}