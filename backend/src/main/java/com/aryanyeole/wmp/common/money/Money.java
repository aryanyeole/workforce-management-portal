package com.aryanyeole.wmp.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts between the API's long-cents representation and the BigDecimal
 * every domain's NUMERIC(12,2) money column actually stores. Money never
 * crosses the HTTP boundary as BigDecimal, double, or float — DTOs carry
 * only {@code amountCents} — but the schema predates that convention, so
 * this is the one place the two representations meet.
 */
public final class Money {

    private Money() {
    }

    public static BigDecimal centsToAmount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /**
     * @throws ArithmeticException if amount carries more than 2 decimal
     *                             places — it never should, given the
     *                             NUMERIC(12,2) column, but this fails loudly
     *                             rather than silently rounding if it ever does.
     */
    public static long amountToCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact();
    }
}
