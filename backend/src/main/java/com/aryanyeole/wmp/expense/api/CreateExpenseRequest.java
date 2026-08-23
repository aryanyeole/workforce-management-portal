package com.aryanyeole.wmp.expense.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * amountCents is the only money representation the API ever accepts — long
 * cents, never a decimal or float. See com.aryanyeole.wmp.common.money.Money
 * for where that meets the NUMERIC(12,2) column.
 */
public record CreateExpenseRequest(
        @NotNull Long categoryId,
        @Positive long amountCents,
        @Pattern(regexp = "[A-Z]{3}") String currency,
        String description) {

    public CreateExpenseRequest {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
    }
}
