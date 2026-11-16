package com.vcfcf.adapters.vcommunity;

import java.util.ArrayList;
import java.util.List;

/** Assertion helpers for the plain-main tests (run from the repo root). */
final class T {

    private T() {}

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want <" + want + "> got <" + got + ">");
        }
    }

    static void contains(String text, String part, String what) {
        if (text == null || !text.contains(part)) {
            throw new AssertionError(what + ": <" + text + "> does not contain <" + part + ">");
        }
    }

    static void notContains(String text, String part, String what) {
        if (text != null && text.contains(part)) {
            throw new AssertionError(what + ": <" + text + "> contains <" + part + ">");
        }
    }

    interface Thrower {
        void run() throws Exception;
    }

    static void throwsNothing(Thrower r, String what) {
        try {
            r.run();
        } catch (Exception e) {
            throw new AssertionError(what + ": threw " + e);
        }
    }

    static void throwsIae(Runnable r, String partOfMessage, String what) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            if (partOfMessage != null) contains(e.getMessage(), partOfMessage, what);
            return;
        }
        throw new AssertionError(what + ": no IllegalArgumentException");
    }

    /** A Log that records every line. */
    static final class RecLog implements Log {
        final List<String> infos = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public synchronized void info(String m) { infos.add(m); }
        @Override public synchronized void warn(String m) { warns.add(m); }
        @Override public synchronized void error(String m) { errors.add(m); }

        synchronized String all() {
            return String.join("\n", infos) + "\n" + String.join("\n", warns) + "\n"
                    + String.join("\n", errors);
        }
    }

    static void done(String name) {
        System.out.println("ok  " + name);
    }
}
