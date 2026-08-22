package com.aryanyeole.wmp.auth.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.auth.service.AuthService;
import com.aryanyeole.wmp.common.security.AuthPrincipal;

import jakarta.validation.Valid;

/**
 * login/refresh are public (see PermissionRegistry.PUBLIC_PATTERNS); /me
 * requires a token but is declared open to every role in the registry.
 * No @PreAuthorize or role checks here — see CLAUDE.md convention #1.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        return new MeResponse(principal.userAccountId(), principal.employeeId(), principal.email(), principal.role());
    }
}
