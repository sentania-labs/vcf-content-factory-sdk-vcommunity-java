package com.vcfcf.adapters.vcommunity.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one read of the content libraries returned, with what did not come back. Plain data: the
 * collector turns it into objects and decides what it proves. A library or an item that could
 * not be read is in {@link #failures} and is never a half-filled entry.
 */
public final class LibraryData {

    /** One read that failed, with the cause id of the catalogue (design 5.2). */
    public static final class ReadFailure {
        public final String cause;
        public final String object;
        public final String message;
        /** Values this failure kept from being sent (an estimate: the keys of the object). */
        public final int keysWithheld;

        ReadFailure(String cause, String object, String message, int keysWithheld) {
            this.cause = cause;
            this.object = object;
            this.message = message;
            this.keysWithheld = keysWithheld;
        }
    }

    /** One library as the read left it. */
    public static final class Library {
        /** The identifier the list gave. */
        public final String id;
        /** The library's detail answer, or null when it could not be read (then the library is skipped). */
        public Map<String, Object> detail;
        /**
         * The identifiers of its items in the order given (a repeated one repeated, Q11), without the ones
         * found gone; null when that list could not be read.
         */
        public List<String> itemIds;
        /** The library was deleted between the list and its read (HTTP 404): it is not there, which is not a failed read. */
        public boolean gone;
        /** The items that were read, by item id, in list order. An item that failed is not here. */
        public final Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        /** How many of its items were asked for and not read (failed, or not reached by the deadline). */
        public int itemsMissing;
        /** The detail and the list were not reached before the deadline. */
        public boolean notRun;

        Library(String id) {
            this.id = id;
        }

        /** The library and every one of its items were read. */
        public boolean complete() {
            return detail != null && itemIds != null && itemsMissing == 0 && !notRun;
        }

        /** The items to ask for: each identifier once. */
        public java.util.Set<String> distinctItemIds() {
            return itemIds == null ? java.util.Collections.<String>emptySet() : new java.util.LinkedHashSet<>(itemIds);
        }
    }

    /** Why nothing was read (the login or the list failed); null when the list was read. */
    public String listFailure;
    /** The cycle deadline stopped the read before everything was asked for. */
    public boolean deadlineHit;
    /** The libraries the list named, in order, duplicates removed. */
    public final List<Library> libraries = new ArrayList<>();
    public final List<ReadFailure> failures = new ArrayList<>();

    void fail(String cause, String object, String message, int keys) {
        failures.add(new ReadFailure(cause, object, message, keys));
    }

    /** Nothing was read at all. */
    public static LibraryData failedList(String message) {
        LibraryData d = new LibraryData();
        d.listFailure = message;
        d.fail("F-REST-LIST", "content libraries", message, 0);
        return d;
    }
}
