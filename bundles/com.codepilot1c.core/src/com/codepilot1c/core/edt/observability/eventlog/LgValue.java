package com.codepilot1c.core.edt.observability.eventlog;

import java.io.IOException;
import java.io.PushbackReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Node of the 1C text event-log serialization ({@code 1Cv8.lgf} / {@code *.lgp}):
 * either an atom (bare token or quoted string) or a brace-list of nested values.
 *
 * <p>Format essentials (verified against a live 8.3.27 file-infobase log; field
 * mapping cross-checked with the ru.fedukhin.edt.mcp eventlog bundle, Apache-2.0):
 * records are top-level {@code {...}} blocks separated by commas/newlines; strings
 * are double-quoted with {@code ""} escaping; atoms are numbers, UUIDs or letter
 * codes such as {@code I}/{@code N}.</p>
 */
public final class LgValue {

    private final String atom;
    private final List<LgValue> items;

    private LgValue(String atom, List<LgValue> items) {
        this.atom = atom;
        this.items = items;
    }

    public static LgValue atom(String text) {
        return new LgValue(text, null);
    }

    public static LgValue list(List<LgValue> items) {
        return new LgValue(null, items);
    }

    public boolean isList() {
        return items != null;
    }

    public boolean isAtom() {
        return atom != null;
    }

    public List<LgValue> items() {
        return items == null ? List.of() : items;
    }

    /** Atom text, or {@code null} for lists; never throws. */
    public String asString() {
        return atom;
    }

    public Long asLong() {
        if (atom == null || atom.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(atom);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int asInt(int fallback) {
        Long v = asLong();
        return v == null ? fallback : v.intValue();
    }

    /** Nested item by index or {@code null} when out of range / not a list. */
    public LgValue item(int index) {
        if (items == null || index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    /**
     * Reads the next top-level {@code {...}} block, skipping separators between
     * records. Returns {@code null} on clean EOF and throws
     * {@link TruncatedRecordException} when EOF hits inside a record — the normal
     * state of the tail of the partition currently being written.
     */
    public static LgValue readRecord(PushbackReader reader) throws IOException {
        int c;
        do {
            c = reader.read();
            if (c < 0) {
                return null;
            }
        } while (c != '{');
        return parseList(reader);
    }

    private static LgValue parseList(PushbackReader reader) throws IOException {
        List<LgValue> out = new ArrayList<>();
        while (true) {
            int c = readSkippingEol(reader);
            if (c < 0) {
                throw new TruncatedRecordException();
            }
            switch (c) {
            case '{':
                out.add(parseList(reader));
                break;
            case '}':
                return list(out);
            case ',':
                // separator; empty positions between commas are not produced by 1C
                break;
            case '"':
                out.add(atom(parseQuoted(reader)));
                break;
            default:
                reader.unread(c);
                out.add(atom(parseBareAtom(reader)));
                break;
            }
        }
    }

    private static String parseBareAtom(PushbackReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = reader.read();
            if (c < 0) {
                throw new TruncatedRecordException();
            }
            if (c == ',' || c == '}') {
                reader.unread(c);
                return sb.toString();
            }
            if (c == '\r' || c == '\n' || c == ' ' || c == '\t') {
                continue;
            }
            sb.append((char) c);
        }
    }

    private static String parseQuoted(PushbackReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = reader.read();
            if (c < 0) {
                throw new TruncatedRecordException();
            }
            if (c == '"') {
                int next = reader.read();
                if (next == '"') {
                    sb.append('"');
                    continue;
                }
                if (next >= 0) {
                    reader.unread(next);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    private static int readSkippingEol(PushbackReader reader) throws IOException {
        while (true) {
            int c = reader.read();
            if (c != '\r' && c != '\n' && c != ' ' && c != '\t') {
                return c;
            }
        }
    }

    /** EOF inside a record — active-partition tail, not an error for the file. */
    public static final class TruncatedRecordException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
