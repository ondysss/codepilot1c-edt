package com.codepilot1c.core.edt.observability.eventlog;

import com.google.gson.JsonObject;

/**
 * One decoded event-log entry. Field order in the source record (verified on a
 * live 8.3.27 log): [0] date literal {@code yyyyMMddHHmmss}, [1] tx status,
 * [2] tx id pair, [3] user seq, [4] computer seq, [5] application seq,
 * [6] connection, [7] event seq, [8] severity code, [9] comment,
 * [10] metadata seq, [11] data {type, value}, [12] data presentation,
 * [13] server seq, [14/15] port seqs, [16] session.
 */
public final class EventLogRecord {

    public long dateRaw;
    public String dateIso;
    public String txStatus;
    public String user;
    public String computer;
    public String application;
    public long connection;
    public String event;
    public String severity;
    public String comment;
    public String metadata;
    public String dataType;
    public String dataValue;
    public String dataPresentation;
    public String server;
    public long session;

    public static EventLogRecord decode(LgValue rec, LgfCatalog refs) {
        if (rec == null || !rec.isList() || rec.items().size() < 17) {
            return null;
        }
        Long date = rec.item(0).asLong();
        if (date == null) {
            return null;
        }
        EventLogRecord ev = new EventLogRecord();
        ev.dateRaw = date;
        ev.dateIso = toIso(date);
        ev.txStatus = decodeTxStatus(text(rec, 1));
        ev.user = refs.user(intAt(rec, 3));
        ev.computer = refs.computer(intAt(rec, 4));
        ev.application = refs.application(intAt(rec, 5));
        Long conn = rec.item(6).asLong();
        ev.connection = conn == null ? 0 : conn;
        ev.event = refs.event(intAt(rec, 7));
        ev.severity = decodeSeverity(text(rec, 8));
        ev.comment = text(rec, 9);
        ev.metadata = refs.metadataName(intAt(rec, 10));
        LgValue data = rec.item(11);
        if (data != null && data.isList() && !data.items().isEmpty()) {
            ev.dataType = data.item(0) == null ? null : data.item(0).asString();
            if (data.items().size() > 1 && data.item(1) != null && data.item(1).isAtom()) {
                ev.dataValue = data.item(1).asString();
            }
        }
        ev.dataPresentation = text(rec, 12);
        ev.server = refs.server(intAt(rec, 13));
        Long session = rec.item(16).asLong();
        ev.session = session == null ? 0 : session;
        return ev;
    }

    private static String text(LgValue rec, int index) {
        LgValue v = rec.item(index);
        return v == null ? null : v.asString();
    }

    private static int intAt(LgValue rec, int index) {
        LgValue v = rec.item(index);
        return v == null ? -1 : v.asInt(-1);
    }

    public static String decodeSeverity(String code) {
        if (code == null) {
            return null;
        }
        switch (code) {
        case "I": return "Information"; //$NON-NLS-1$ //$NON-NLS-2$
        case "W": return "Warning"; //$NON-NLS-1$ //$NON-NLS-2$
        case "E": return "Error"; //$NON-NLS-1$ //$NON-NLS-2$
        case "N": return "Note"; //$NON-NLS-1$ //$NON-NLS-2$
        default: return code;
        }
    }

    public static String decodeTxStatus(String code) {
        if (code == null) {
            return null;
        }
        switch (code) {
        case "N": return "NotTransactional"; //$NON-NLS-1$ //$NON-NLS-2$
        case "C": return "Committed"; //$NON-NLS-1$ //$NON-NLS-2$
        case "U": return "Unfinished"; //$NON-NLS-1$ //$NON-NLS-2$
        case "R": return "RolledBack"; //$NON-NLS-1$ //$NON-NLS-2$
        default: return code;
        }
    }

    /** {@code 20260814205206} → {@code 2026-08-14T20:52:06} (log-local time). */
    public static String toIso(long ts) {
        int yyyy = (int) (ts / 10_000_000_000L);
        int mm = (int) ((ts / 100_000_000L) % 100);
        int dd = (int) ((ts / 1_000_000L) % 100);
        int hh = (int) ((ts / 10_000L) % 100);
        int mi = (int) ((ts / 100L) % 100);
        int ss = (int) (ts % 100);
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d", yyyy, mm, dd, hh, mi, ss); //$NON-NLS-1$
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ts", dateIso); //$NON-NLS-1$
        o.addProperty("severity", severity); //$NON-NLS-1$
        o.addProperty("event", event); //$NON-NLS-1$
        addIfPresent(o, "user", user); //$NON-NLS-1$
        addIfPresent(o, "computer", computer); //$NON-NLS-1$
        addIfPresent(o, "application", application); //$NON-NLS-1$
        addIfPresent(o, "metadata", metadata); //$NON-NLS-1$
        addIfPresent(o, "comment", comment); //$NON-NLS-1$
        addIfPresent(o, "data_type", dataType); //$NON-NLS-1$
        addIfPresent(o, "data_value", dataValue); //$NON-NLS-1$
        addIfPresent(o, "data_presentation", dataPresentation); //$NON-NLS-1$
        addIfPresent(o, "tx_status", "NotTransactional".equals(txStatus) ? null : txStatus); //$NON-NLS-1$ //$NON-NLS-2$
        addIfPresent(o, "server", server); //$NON-NLS-1$
        if (session != 0) {
            o.addProperty("session", session); //$NON-NLS-1$
        }
        if (connection != 0) {
            o.addProperty("connection", connection); //$NON-NLS-1$
        }
        return o;
    }

    private static void addIfPresent(JsonObject o, String key, String value) {
        if (value != null && !value.isEmpty()) {
            o.addProperty(key, value);
        }
    }
}
