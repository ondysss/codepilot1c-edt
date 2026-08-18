package com.codepilot1c.core.tools.diagnostics;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.observability.eventlog.EventLogRecord;
import com.codepilot1c.core.edt.observability.eventlog.EventLogService;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Queries the 1C event log ({@code 1Cv8Log}) of a file infobase associated with
 * an EDT project — newest first, with severity/event/user/metadata/text filters.
 * Closes the "read the registration journal from the agent loop" gap: BSP-level
 * errors surface here when EDT diagnostics and test runs stay silent.
 */
@ToolMeta(name = "query_event_log", category = "diagnostics", tags = {"read-only", "infobase", "diagnostics"})
public class QueryEventLogTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QueryEventLogTool.class);
    private static final String TOOL_NAME = "query_event_log"; //$NON-NLS-1$
    private static final int MAX_LIMIT = 500;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "EDT project with an infobase association (base project, not an extension)"},
                "log_dir": {"type": "string", "description": "Explicit path to a 1Cv8Log directory; overrides project resolution"},
                "since": {"type": "string", "description": "Lower bound, ISO 2026-08-14T20:00:00 or literal 20260814200000"},
                "until": {"type": "string", "description": "Upper bound, same formats"},
                "last_minutes": {"type": "integer", "description": "Shortcut: since = now - last_minutes (ignored when since is set)"},
                "severity": {"type": "array", "items": {"type": "string"}, "description": "Any of Error, Warning, Information, Note (or codes E/W/I/N)"},
                "event_contains": {"type": "string"},
                "user_contains": {"type": "string"},
                "metadata_contains": {"type": "string"},
                "text_contains": {"type": "string", "description": "Substring over comment, data value and data presentation"},
                "limit": {"type": "integer", "description": "Max records to return, default 50, cap 500"},
                "offset": {"type": "integer"}
              },
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final EventLogService service;
    private final EdtProjectResolver projectResolver;

    public QueryEventLogTool() {
        this(new EventLogService(), new EdtProjectResolver());
    }

    public QueryEventLogTool(EventLogService service, EdtProjectResolver projectResolver) {
        this.service = service;
        this.projectResolver = projectResolver;
    }

    @Override
    public String getDescription() {
        return "Reads the 1C event log (registration journal) of a file infobase: newest-first records " //$NON-NLS-1$
                + "with severity/event/user/metadata/text filters. Resolve via EDT project association " //$NON-NLS-1$
                + "or an explicit 1Cv8Log path."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("evlog"); //$NON-NLS-1$
            Map<String, Object> raw = params.getRaw();
            try {
                Path logDir = resolveLogDir(raw);
                if (logDir == null) {
                    return failure(opId, "INVALID_ARGUMENT", //$NON-NLS-1$
                            "Pass either project (with infobase association) or log_dir"); //$NON-NLS-1$
                }
                if (EventLogService.isSqliteFormat(logDir)) {
                    return failure(opId, "SQLITE_LOG_FORMAT", //$NON-NLS-1$
                            "The infobase writes its event log in SQLite format (1Cv8.lgd); " //$NON-NLS-1$
                                    + "only the text format is supported — switch the log format " //$NON-NLS-1$
                                    + "or query the .lgd file with external tooling"); //$NON-NLS-1$
                }
                if (!Files.isDirectory(logDir)) {
                    return failure(opId, "LOG_DIR_NOT_FOUND", //$NON-NLS-1$
                            "Event log directory does not exist: " + logDir); //$NON-NLS-1$
                }
                EventLogService.Query query = buildQuery(raw, params);
                LOG.info("[%s] query_event_log dir=%s since=%d until=%d limit=%d", //$NON-NLS-1$
                        opId, logDir, query.sinceRaw, query.untilRaw, query.limit);
                EventLogService.Result r = service.query(logDir, query);
                JsonObject payload = new JsonObject();
                payload.addProperty("log_dir", logDir.toString()); //$NON-NLS-1$
                JsonArray records = new JsonArray();
                for (EventLogRecord ev : r.records) {
                    records.add(ev.toJson());
                }
                payload.add("records", records); //$NON-NLS-1$
                payload.addProperty("returned", r.records.size()); //$NON-NLS-1$
                payload.addProperty("matched", r.matched); //$NON-NLS-1$
                payload.addProperty("scanned", r.scanned); //$NON-NLS-1$
                payload.addProperty("partitions_scanned", r.partitionsScanned); //$NON-NLS-1$
                payload.addProperty("has_more", r.hasMore); //$NON-NLS-1$
                if (r.scanCapHit) {
                    payload.addProperty("scan_cap_hit", true); //$NON-NLS-1$
                }
                if (r.partialTail) {
                    payload.addProperty("partial_tail", true); //$NON-NLS-1$
                }
                return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(payload),
                        ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[%s] query_event_log failed: %s", opId, String.valueOf(e.getMessage())); //$NON-NLS-1$
                return failure(opId, "EVENT_LOG_READ_FAILED", String.valueOf(e.getMessage())); //$NON-NLS-1$
            }
        });
    }

    private Path resolveLogDir(Map<String, Object> raw) {
        String explicit = asString(raw.get("log_dir")); //$NON-NLS-1$
        if (!explicit.isBlank()) {
            Path p = Path.of(explicit);
            // accept both the 1Cv8Log dir itself and the infobase root above it
            if (Files.isDirectory(p.resolve("1Cv8Log"))) { //$NON-NLS-1$
                return p.resolve("1Cv8Log"); //$NON-NLS-1$
            }
            return p;
        }
        String project = asString(raw.get("project")); //$NON-NLS-1$
        if (project.isBlank()) {
            return null;
        }
        File workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
        InfobaseReference infobase = projectResolver.resolveInfobase(project, workspaceRoot);
        if (infobase == null || infobase.getConnectionString() == null) {
            return null;
        }
        Path infobasePath = EventLogService
                .infobasePathFromConnectionString(infobase.getConnectionString().asConnectionString());
        return EventLogService.logDirOf(infobasePath);
    }

    private static EventLogService.Query buildQuery(Map<String, Object> raw, ToolParameters params) {
        EventLogService.Query q = new EventLogService.Query();
        q.sinceRaw = parseMoment(asString(raw.get("since"))); //$NON-NLS-1$
        q.untilRaw = parseMoment(asString(raw.get("until"))); //$NON-NLS-1$
        int lastMinutes = params.optInt("last_minutes", 0); //$NON-NLS-1$
        if (q.sinceRaw == 0 && lastMinutes > 0) {
            q.sinceRaw = literalMinutesAgo(lastMinutes);
        }
        q.severities = parseSeverities(raw.get("severity")); //$NON-NLS-1$
        q.eventContains = asString(raw.get("event_contains")); //$NON-NLS-1$
        q.userContains = asString(raw.get("user_contains")); //$NON-NLS-1$
        q.metadataContains = asString(raw.get("metadata_contains")); //$NON-NLS-1$
        q.textContains = asString(raw.get("text_contains")); //$NON-NLS-1$
        q.limit = Math.min(Math.max(params.optInt("limit", 50), 0), MAX_LIMIT); //$NON-NLS-1$
        q.offset = Math.max(params.optInt("offset", 0), 0); //$NON-NLS-1$
        return q;
    }

    /** Accepts {@code 2026-08-14T20:00:00}, {@code 2026-08-14 20:00:00} or a raw 14-digit literal. */
    static long parseMoment(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String digits = value.replaceAll("\\D", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (digits.length() >= 14) {
            return Long.parseLong(digits.substring(0, 14));
        }
        if (digits.length() == 8) { // date only → start of day
            return Long.parseLong(digits) * 1_000_000L;
        }
        return 0;
    }

    private static long literalMinutesAgo(int minutes) {
        java.time.LocalDateTime t = java.time.LocalDateTime.now().minusMinutes(minutes);
        return t.getYear() * 10_000_000_000L + t.getMonthValue() * 100_000_000L
                + t.getDayOfMonth() * 1_000_000L + t.getHour() * 10_000L
                + t.getMinute() * 100L + t.getSecond();
    }

    private static Set<String> parseSeverities(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Set<String> out = new HashSet<>();
        for (Object item : list) {
            String s = String.valueOf(item).strip();
            if (s.length() == 1) {
                s = EventLogRecord.decodeSeverity(s.toUpperCase(Locale.ROOT));
            } else {
                s = normalizeSeverityName(s);
            }
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String normalizeSeverityName(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
        case "error": case "ошибка": return "Error"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "warning": case "предупреждение": return "Warning"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "information": case "информация": return "Information"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "note": case "примечание": return "Note"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        default: return s;
        }
    }

    private ToolResult failure(String opId, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("op_id", opId); //$NON-NLS-1$
        error.addProperty("tool", TOOL_NAME); //$NON-NLS-1$
        error.addProperty("code", code); //$NON-NLS-1$
        error.addProperty("message", message); //$NON-NLS-1$
        return ToolResult.failure("[" + code + "] " + message + "\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + new GsonBuilder().setPrettyPrinting().create().toJson(error));
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip(); //$NON-NLS-1$
    }
}
