package com.vcfcf.adapters.vcommunity.guest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what a guest wrote as comma separated values (RFC 4180): fields split on commas, a quoted field may
 * hold commas, doubled quotes and line breaks. The text came from a guest, so it is untrusted: it is decoded
 * as strict UTF-8, one leading byte order mark is dropped, and the size of the text, the number of rows and
 * columns and the length of a field are bounded. Anything the format does not allow (a quote inside an
 * unquoted field, text after a closing quote, a quote that never closes) refuses the whole text rather than
 * guessing a row.
 */
public final class GuestCsv {

    public static final int MAX_ROWS = 10_000;
    public static final int MAX_COLUMNS = 32;
    public static final int MAX_FIELD_CHARS = 32 * 1024;

    /** The text is not CSV this port reads; the message says why and holds none of the text. */
    public static final class CsvException extends Exception {
        private static final long serialVersionUID = 1L;

        CsvException(String message) {
            super(message);
        }
    }

    /** The header and the data rows, each row as long as it was written. */
    public static final class Table {
        public final List<String> header;
        public final List<List<String>> rows;

        Table(List<String> header, List<List<String>> rows) {
            this.header = header;
            this.rows = rows;
        }

        /** The position of a column by its exact name, or -1. */
        public int column(String name) {
            return header.indexOf(name);
        }
    }

    private GuestCsv() {}

    /**
     * @return the table, or null when the text holds nothing (no header at all)
     * @throws CsvException the text is not readable CSV
     */
    public static Table parse(byte[] bytes) throws CsvException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CsvException("the output is not valid UTF-8");
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        if (text.trim().isEmpty()) return null;

        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;      // inside a quoted field
        boolean wasQuoted = false;   // this field started with a quote
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        if (i + 1 < n) {
                            char d = text.charAt(i + 1);
                            if (d != ',' && d != '\r' && d != '\n') throw new CsvException("text follows a closing quote");
                        }
                    }
                } else {
                    field.append(c);
                }
                if (field.length() > MAX_FIELD_CHARS) throw new CsvException("a field is longer than the limit");
                continue;
            }
            if (c == '"') {
                if (field.length() != 0 || wasQuoted) throw new CsvException("a quote inside an unquoted field");
                quoted = true;
                wasQuoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
                if (row.size() > MAX_COLUMNS) throw new CsvException("a row has more columns than the limit");
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
                records.add(row);
                row = new ArrayList<>();
                if (records.size() > MAX_ROWS + 1) throw new CsvException("more rows than the limit");
            } else {
                field.append(c);
                if (field.length() > MAX_FIELD_CHARS) throw new CsvException("a field is longer than the limit");
            }
        }
        if (quoted) throw new CsvException("a quoted field never closes");
        if (field.length() != 0 || wasQuoted || !row.isEmpty()) {
            row.add(field.toString());
            records.add(row);
        }
        if (records.isEmpty()) return null;
        List<String> header = records.get(0);
        return new Table(header, new ArrayList<>(records.subList(1, records.size())));
    }
}
