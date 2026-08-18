package com.codepilot1c.core.edt.observability.eventlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reference dictionaries from {@code 1Cv8.lgf}: event-log records store integer
 * sequence ids instead of names; this catalog resolves them back.
 *
 * <p>Entry kinds (first atom of each block): 1 = user {@code {1,uuid,"name",seq}},
 * 2 = computer, 3 = application, 4 = event, 5 = metadata
 * {@code {5,uuid,"fullName",seq}}, 6 = working server, 7/8 = ports. Other kinds
 * are ignored.</p>
 */
public final class LgfCatalog {

    private final Map<Integer, String> users = new HashMap<>();
    private final Map<Integer, String> userUuids = new HashMap<>();
    private final Map<Integer, String> computers = new HashMap<>();
    private final Map<Integer, String> applications = new HashMap<>();
    private final Map<Integer, String> events = new HashMap<>();
    private final Map<Integer, String> metadata = new HashMap<>();
    private final Map<Integer, String> metadataUuids = new HashMap<>();
    private final Map<Integer, String> servers = new HashMap<>();

    public static LgfCatalog load(Path lgf) throws IOException {
        LgfCatalog catalog = new LgfCatalog();
        if (lgf == null || !Files.isRegularFile(lgf)) {
            return catalog;
        }
        try (BufferedReader br = Files.newBufferedReader(lgf, StandardCharsets.UTF_8);
                PushbackReader reader = new PushbackReader(br, 1)) {
            while (true) {
                LgValue rec;
                try {
                    rec = LgValue.readRecord(reader);
                } catch (LgValue.TruncatedRecordException e) {
                    break; // catalog is appended live too; a torn tail is normal
                }
                if (rec == null) {
                    break;
                }
                catalog.accept(rec);
            }
        }
        return catalog;
    }

    private void accept(LgValue rec) {
        if (!rec.isList() || rec.items().isEmpty()) {
            return;
        }
        int kind = rec.item(0) == null ? -1 : rec.item(0).asInt(-1);
        switch (kind) {
        case 1: { // {1, uuid, "name", seq}
            String uuid = textAt(rec, 1);
            String name = textAt(rec, 2);
            Integer seq = intAt(rec, 3);
            if (seq != null) {
                users.put(seq, name);
                userUuids.put(seq, uuid);
            }
            break;
        }
        case 2:
            put(computers, rec);
            break;
        case 3:
            put(applications, rec);
            break;
        case 4:
            put(events, rec);
            break;
        case 5: { // {5, uuid, "fullName", seq}
            String uuid = textAt(rec, 1);
            String name = textAt(rec, 2);
            Integer seq = intAt(rec, 3);
            if (seq != null) {
                metadata.put(seq, name);
                metadataUuids.put(seq, uuid);
            }
            break;
        }
        case 6:
            put(servers, rec);
            break;
        default:
            break;
        }
    }

    /** Common shape {@code {kind, "name", seq}}. */
    private static void put(Map<Integer, String> target, LgValue rec) {
        String name = textAt(rec, 1);
        Integer seq = intAt(rec, 2);
        if (seq != null) {
            target.put(seq, name);
        }
    }

    private static String textAt(LgValue rec, int index) {
        LgValue v = rec.item(index);
        return v == null ? null : v.asString();
    }

    private static Integer intAt(LgValue rec, int index) {
        LgValue v = rec.item(index);
        Long n = v == null ? null : v.asLong();
        return n == null ? null : n.intValue();
    }

    public String user(int seq) {
        return users.get(seq);
    }

    public String userUuid(int seq) {
        return userUuids.get(seq);
    }

    public String computer(int seq) {
        return computers.get(seq);
    }

    public String application(int seq) {
        return applications.get(seq);
    }

    public String event(int seq) {
        return events.get(seq);
    }

    public String metadataName(int seq) {
        return metadata.get(seq);
    }

    public String metadataUuid(int seq) {
        return metadataUuids.get(seq);
    }

    public String server(int seq) {
        return servers.get(seq);
    }

    public int eventCount() {
        return events.size();
    }
}
