package com.aryanyeole.wmp.expense.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * PATCH semantics: every field is optional. A null field means "leave
 * unchanged"; a present field is applied verbatim (including an empty
 * string clearing description). Jakarta Bean Validation constraints treat
 * null as valid by design, so @Positive/@Pattern only fire when a field is
 * actually supplied.
 */
public record UpdateExpenseRequest(
        Long categoryId,
        @Positive Long amountCents,
        @Pattern(regexp = "[A-Z]{3}") String currency,
        String description) {
}
