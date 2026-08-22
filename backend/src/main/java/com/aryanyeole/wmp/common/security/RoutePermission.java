package com.aryanyeole.wmp.common.security;

import java.util.Set;
import org.springframework.http.HttpMethod;

import com.aryanyeole.wmp.auth.domain.RoleCode;

/**
 * One authorization rule: which roles may invoke a given method + path pattern.
 *
 * @param ownershipScoped when true, route-level role check is necessary but not
 *                        sufficient — the service/repository layer must further
 *                        constrain results to the authenticated principal.
 */
public record RoutePermission(
        HttpMethod method,
        String pattern,
        Set<RoleCode> allowedRoles,
        boolean ownershipScoped) {
}