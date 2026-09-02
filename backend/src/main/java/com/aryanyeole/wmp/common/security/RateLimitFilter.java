package com.aryanyeole.wmp.common.security;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One target only: POST /api/v1/auth/login, keyed by client IP. This is the
 * one endpoint in the API that is both unauthenticated (so IP is the only
 * identity signal available at all) and defends against a threat —
 * credential stuffing / password guessing — that is specific to a
 * low-entropy secret. /auth/refresh is also unauthenticated but isn't given
 * the same treatment: a refresh token is a high-entropy opaque value, not
 * practically guessable, so rate-limiting it defends against nothing beyond
 * what limiting login already covers (you need a successful login first to
 * get one). Every authenticated endpoint is left alone entirely: reaching
 * any of them already requires a valid JWT, which requires passing through
 * this same limiter at login first — a second limiter in front of, say, the
 * approvals queue would be theatre, defending against a threat model
 * (authenticated abuse / cost control) this task was never about and that
 * calls for a different mechanism (a per-account quota, not an IP bucket)
 * if it's ever actually needed.
 *
 * Algorithm: token bucket, hand-rolled (TokenBucket below) rather than
 * Bucket4j — no new dependency, and the whole thing is small enough to stay
 * within this project's boring-and-explicit convention. Fixed window was
 * the other standard option and was passed over deliberately: it lets a
 * full window's worth of attempts land right at a window boundary and then
 * again immediately after, doubling the effective burst for free, which is
 * exactly the failure mode credential stuffing would exploit.
 *
 * State: one ConcurrentHashMap<String, TokenBucket>, this JVM only. Correct
 * for a single backend instance (today's actual deployment) and wrong the
 * moment there's more than one: each instance would run its own independent
 * bucket per IP, so N instances behind a load balancer effectively multiply
 * the real limit by N. A shared store (Redis, etc.) is what horizontal
 * scaling would need — not implemented, since this app only ever runs as
 * one instance. The map itself is also unbounded — an entry per distinct IP
 * ever seen, never evicted — a known, accepted simplification for a
 * portfolio-scale deployment, not something this filter tries to solve.
 *
 * Ordering: registered in SecurityConfig anchored immediately after
 * CorrelationIdFilter and before JwtAuthenticationFilter — correlation
 * first, so a 429 still carries an ID; authentication after, since the
 * whole point is rejecting a flood cheaply before doing any of the work
 * (JWT parsing, PermissionRegistry lookup) authentication or authorization
 * would otherwise spend on every one of those requests.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LIMITED_PATH = "/api/v1/auth/login";

    private final LoginRateLimitProperties properties;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(LoginRateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.enabled() || !isLimitedRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientIp(request);
        double refillPerNano = properties.refillPerMinute() / 60.0 / 1_000_000_000.0;
        TokenBucket bucket = buckets.computeIfAbsent(clientIp,
                ip -> new TokenBucket(properties.capacity(), refillPerNano));

        long now = System.nanoTime();
        if (bucket.tryConsume(now)) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = bucket.secondsUntilNextToken(now);
        SecurityResponses.writeTooManyRequests(response,
                "Too many login attempts. Try again in " + retryAfterSeconds + " seconds.", retryAfterSeconds);
    }

    private boolean isLimitedRequest(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && LIMITED_PATH.equals(request.getRequestURI());
    }

    /**
     * X-Real-IP over X-Forwarded-For: nginx (frontend/nginx.conf.template)
     * sets both on every proxied request, but with different trust
     * semantics. X-Real-IP is set with a plain proxy_set_header — nginx
     * *replaces* whatever the client sent with $remote_addr, its own view of
     * the TCP peer, so a spoofed inbound value never survives the hop and
     * the header is safe to read as-is. X-Forwarded-For instead uses
     * $proxy_add_x_forwarded_for, which *appends* $remote_addr to whatever
     * the client already sent — correct multi-hop behavior, but it means a
     * naive read of the first value would trust exactly the attacker-
     * controlled part; the genuinely trustworthy part is the last entry,
     * which requires list-parsing this filter has no reason to do when
     * X-Real-IP already hands over the same value directly. Falls back to
     * request.getRemoteAddr() (the servlet container's own view of the
     * immediate peer) when X-Real-IP is absent — correct both for a request
     * that genuinely never went through nginx and as a defensive default.
     *
     * Known, disclosed gap this doesn't close: the backend's own port
     * (8080) is published directly to the host (docker-compose.yml) for
     * direct Swagger access, alongside the frontend's proxy on 80. A
     * request that reaches the backend on 8080 skips nginx entirely, so
     * X-Real-IP on *that* path is unset-by-nginx and fully client-supplied
     * — an attacker hitting the backend directly could set it to whatever
     * they like and this filter would trust it. Nginx being correctly
     * configured (it already is) doesn't close this; only not publishing
     * 8080, or adding a second layer of trust, would, and either is a
     * bigger architectural call than this task makes on its own.
     */
    private String clientIp(HttpServletRequest request) {
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * Continuous refill, not a per-second tick: tokens accrue fractionally
     * between requests (tracked as a double) so a bucket doesn't need a
     * background thread or scheduled task to stay accurate — every call
     * computes elapsed time against the last refill and catches up exactly.
     * Not thread-safe on its own; every method here is synchronized because
     * concurrent requests from the same IP are the normal case this exists
     * to handle correctly, not an edge case.
     */
    private static final class TokenBucket {

        private final double capacity;
        private final double refillTokensPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(double capacity, double refillTokensPerNano) {
            this.capacity = capacity;
            this.refillTokensPerNano = refillTokensPerNano;
            // Starts full, not empty: a client this filter has never seen
            // before gets its first burst immediately, exactly like a
            // client that has been idle long enough to fully refill. That's
            // the intended token-bucket semantic, not a loophole -- what
            // this filter defends against is sustained/rapid guessing, not
            // a single legitimate attempt happening to be someone's first.
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(long nowNanos) {
            refill(nowNanos);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized long secondsUntilNextToken(long nowNanos) {
            refill(nowNanos);
            if (tokens >= 1.0) {
                return 0;
            }
            double nanosNeeded = (1.0 - tokens) / refillTokensPerNano;
            return Math.max(1, (long) Math.ceil(nanosNeeded / 1_000_000_000.0));
        }

        private void refill(long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + elapsed * refillTokensPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
