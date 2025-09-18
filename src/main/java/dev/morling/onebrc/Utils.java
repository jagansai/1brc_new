package dev.morling.onebrc;

import java.nio.charset.StandardCharsets;

public class Utils {
    

    public static final char DELIMETER = ';';
    public static final char NEW_LINE = '\n';
    public static final char CARRIAGE_RETURN = '\r';
    public static final char SPACE = ' ';


    private Utils() {
        // prevent instantiation
    }

    /**
     * Byte-array version of parseDoubleAscii. Parses bytes in [start, end).
     * Assumes ASCII/UTF-8 encoded digits, sign, dot, exponent and whitespace.
     */
    public static double parseDoubleAscii(byte[] b, int start, int end) {
        int i = start;
        // skip leading whitespace
        while (i < end && (b[i] == SPACE || b[i] == CARRIAGE_RETURN || b[i] == '\r' || b[i] == NEW_LINE))
            i++;
        if (i >= end)
            throw new NumberFormatException(new String(b, start, end - start, StandardCharsets.UTF_8));

        int sign = 1;
        byte c = b[i];
        if (c == '+' || c == '-') {
            if (c == '-')
                sign = -1;
            i++;
        }

        long intPart = 0;
        while (i < end) {
            c = b[i];
            if (c >= '0' && c <= '9') {
                intPart = intPart * 10 + (c - '0');
                i++;
            } else
                break;
        }

        double value = (double) intPart;

        // fraction
        if (i < end && b[i] == '.') {
            i++;
            long frac = 0;
            int fracLen = 0;
            while (i < end) {
                c = b[i];
                if (c >= '0' && c <= '9') {
                    frac = frac * 10 + (c - '0');
                    fracLen++;
                    i++;
                } else
                    break;
            }
            if (fracLen > 0) {
                value += frac / Math.pow(10.0, fracLen);
            }
        }

        // exponent
        if (i < end && (b[i] == 'e' || b[i] == 'E')) {
            i++;
            int expSign = 1;
            if (i < end) {
                c = b[i];
                if (c == '+' || c == '-') {
                    if (c == '-')
                        expSign = -1;
                    i++;
                }
            }
            int exp = 0;
            int expDigits = 0;
            while (i < end) {
                c = b[i];
                if (c >= '0' && c <= '9') {
                    exp = exp * 10 + (c - '0');
                    expDigits++;
                    i++;
                } else
                    break;
            }
            if (expDigits > 0) {
                value = value * Math.pow(10.0, expSign * exp);
            } else {
                throw new NumberFormatException(new String(b, start, end - start, StandardCharsets.UTF_8));
            }
        }

        // skip trailing whitespace
        while (i < end && (b[i] == SPACE || b[i] == CARRIAGE_RETURN || b[i] == '\r' || b[i] == NEW_LINE))
            i++;
        if (i != end) {
            throw new NumberFormatException(new String(b, start, end - start, StandardCharsets.UTF_8));
        }

        return sign * value;
    }

}
