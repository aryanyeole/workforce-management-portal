package com.aryanyeole.wmp.common.security;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The single authorization enforcement point for the API.
 *
 * Every protected route is decided here against PermissionRegistry. Controllers
 * carry no security annotations and services carry no role checks; row-level
 * ownership is enforced separately by query scoping in the repository layer.
 *
 * Unregistered routes are denied. Forgetting to declare a new endpoint fails
 * loudly in development rather than shipping an open endpoint.
 */
@Component
public class RouteAuthorizationFilter extends OncePerRequestFilter {

    private final PermissionRegistry registry;

    public RouteAuthorizationFilter(PermissionRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (registry.isPublic(path) || HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken token)) {
            SecurityResponses.write(response, 401, "Unauthorized", "Authentication required");
            return;
        }

        AuthPrincipal principal = token.getPrincipal();
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        Optional<RoutePermission> rule = registry.resolve(method, path);

        if (rule.isEmpty()) {
            logger.warn("Denied unregistered route: " + method + " " + path);
            SecurityResponses.write(response, 403, "Forbidden", "No permission rule for this route");
            return;
        }

        if (!rule.get().allowedRoles().contains(principal.role())) {
            SecurityResponses.write(response, 403, "Forbidden",
                    "Role " + principal.role() + " may not perform this action");
            return;
        }

        filterChain.doFilter(request, response);
    }
}