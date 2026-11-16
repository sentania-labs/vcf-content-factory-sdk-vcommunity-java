package com.vcfcf.adapters.vcommunity.text;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python's {@code round(x, 2)} on a float: the exact binary value of {@code x} is rounded to two
 * decimals, ties to even, and the result is the double nearest to that decimal. So
 * {@code 2.675} (which is a hair below) gives {@code 2.67} and {@code 0.125} (exactly half) gives
 * {@code 0.12}. A NaN or an infinity comes back unchanged, and is the caller's to refuse.
 */
public final class PyRound {

    private PyRound() {}

    /** One gibibyte, as the original divides a byte count. */
    public static final double GIB = 1024.0 * 1024.0 * 1024.0;

    public static double round2(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        return new BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code round(bytes / 1024**3, 2)}. */
    public static double gib(double bytes) {
        return round2(bytes / GIB);
    }
}
