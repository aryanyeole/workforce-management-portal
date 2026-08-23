package com.aryanyeole.wmp.common.api;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Envelope for classic offset-paginated list endpoints (page + size).
 *
 * Deliberately not the shape Phase 6 will use for the approvals queue once
 * it moves to keyset pagination — that response carries an opaque
 * {@code nextCursor} instead of page/totalPages. This one is for the
 * LIMIT/OFFSET era only.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
