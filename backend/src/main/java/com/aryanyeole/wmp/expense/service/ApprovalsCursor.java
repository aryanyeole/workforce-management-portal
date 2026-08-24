package com.aryanyeole.wmp.expense.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.aryanyeole.wmp.common.web.BadRequestException;

/**
 * Opaque keyset cursor for the pending-approvals queue: base64-encodes the
 * exact (sortKey, tiebreak) pair the query orders by — (submitted_at, id),
 * per (submitted_at DESC, id DESC) — never exposing either value to the
 * client directly. Any failure to decode is a 400 (BadRequestException),
 * never a 500: a cursor is untrusted client input the moment it comes back
 * on a request.
 */
record ApprovalsCursor(Instant submittedAt, Long id) {

    private static final String DELIMITER = "|";

    String encode() {
        String raw = submittedAt.toString() + DELIMITER + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static ApprovalsCursor decode(String cursor) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Malformed cursor");
        }

        int separatorIndex = raw.lastIndexOf(DELIMITER);
        if (separatorIndex < 0) {
            throw new BadRequestException("Malformed cursor");
        }

        try {
            Instant submittedAt = Instant.parse(raw.substring(0, separatorIndex));
            Long id = Long.parseLong(raw.substring(separatorIndex + 1));
            return new ApprovalsCursor(submittedAt, id);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new BadRequestException("Malformed cursor");
        }
    }
}
