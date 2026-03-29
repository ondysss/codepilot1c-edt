package com.codepilot1c.core.tools.diagnostics;
import com.codepilot1c.core.tools.ToolErrorParser;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Analyzes structured and legacy tool errors and suggests recovery steps.
 */
@ToolMeta(name = "analyze_tool_error", category = "diagnostics", tags = {"workspace"})
public class AnalyzeToolErrorTool extends AbstractTool {

    private static final int DEFAULT_LOG_TAIL_LINES = 80;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "tool_name": {
                  "type": "string",
                  "description": "Name of the tool that failed, for example edt_diagnostics or create_metadata"
                },
                "tool_result": {
                  "type": "string",
                  "description": "Raw failed ToolResult payload or error JSON; pass the failure body, not a normal success response"
                },
                "include_log_tail": {
                  "type": "boolean",
                  "description": "Include tail of the referenced log file when the error payload points to one"
                },
                "max_log_lines": {
                  "type": "integer",
                  "description": "Maximum number of log lines to include from the referenced log file"
                }
              },
              "required": ["tool_result"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Разбирает конкретный failed tool result, извлекает error_code и предлагает вероятные причины и recovery steps. Используй после неуспешного tool-вызова, когда нужен структурированный разбор ошибки. Не заменяет get_diagnostics, metadata_smoke или повторный запуск самого доменного инструмента."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String toolName = asOptionalString(parameters == null ? null : parameters.get("tool_name")); //$NON-NLS-1$
            String toolResult = asOptionalString(parameters == null ? null : parameters.get("tool_result")); //$NON-NLS-1$
            boolean includeLogTail = parameters == null || !Boolean.FALSE.equals(parameters.get("include_log_tail")); //$NON-NLS-1$
            int maxLogLines = parsePositiveInt(parameters == null ? null : parameters.get("max_log_lines"), //$NON-NLS-1$
                    DEFAULT_LOG_TAIL_LINES);

            if (toolResult == null) {
                return ToolResult.failure("[INVALID_ARGUMENT] tool_result is required"); //$NON-NLS-1$
            }

            ToolErrorParser.ParsedToolError parsed = ToolErrorParser.parse(toolResult);
            JsonObject result = new JsonObject();
            result.addProperty("status", "analyzed"); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("tool_name", toolName == null ? "" : toolName); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("recognized", parsed.errorCode() != null || !"plain".equals(parsed.format())); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("error_code", parsed.errorCode() == null ? "" : parsed.errorCode()); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("message", parsed.message() == null ? "" : parsed.message()); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("recoverable", resolveRecoverable(parsed)); //$NON-NLS-1$
            result.addProperty("summary", buildSummary(toolName, parsed)); //$NON-NLS-1$

            result.add("likely_causes", toJsonArray(likelyCauses(toolName, parsed))); //$NON-NLS-1$
            result.add("recommended_actions", toJsonArray(recommendedActions(toolName, parsed))); //$NON-NLS-1$
            result.add("suggested_tool_calls", suggestedToolCalls(toolName, parsed)); //$NON-NLS-1$

            JsonObject details = new JsonObject();
            details.addProperty("format", parsed.format() == null ? "" : parsed.format()); //$NON-NLS-1$ //$NON-NLS-2$
            details.addProperty("raw_status", parsed.status() == null ? "" : parsed.status()); //$NON-NLS-1$ //$NON-NLS-2$
            details.addProperty("op_id", parsed.opId() == null ? "" : parsed.opId()); //$NON-NLS-1$ //$NON-NLS-2$
            details.addProperty("project_name", parsed.projectName() == null ? "" : parsed.projectName()); //$NON-NLS-1$ //$NON-NLS-2$
            details.addProperty("log_path", parsed.logPath() == null ? "" : parsed.logPath()); //$NON-NLS-1$ //$NON-NLS-2$
            result.add("details", details); //$NON-NLS-1$

            String logTail = includeLogTail ? resolveLogTail(parsed, maxLogLines) : ""; //$NON-NLS-1$
            result.addProperty("log_tail", logTail == null ? "" : logTail); //$NON-NLS-1$ //$NON-NLS-2$

            return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
        });
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    private String resolveLogTail(ToolErrorParser.ParsedToolError parsed, int maxLines) {
        if (parsed.embeddedLogTail() != null) {
            return parsed.embeddedLogTail();
        }
        if (parsed.logPath() == null) {
            return ""; //$NON-NLS-1$
        }
        File logFile = new File(parsed.logPath());
        if (!isAllowedLogFile(logFile)) {
            return ""; //$NON-NLS-1$
        }
        return tailFile(logFile, maxLines);
    }

    private boolean isAllowedLogFile(File logFile) {
        if (logFile == null || !logFile.isFile()) {
            return false;
        }
        try {
            File canonicalLog = logFile.getCanonicalFile();
            File workspaceRoot = getWorkspaceRoot();
            if (workspaceRoot != null) {
                File canonicalWorkspace = workspaceRoot.getCanonicalFile();
                if (canonicalLog.toPath().startsWith(canonicalWorkspace.toPath())) {
                    return true;
                }
            }
            File tempDir = new File(System.getProperty("java.io.tmpdir")).getCanonicalFile(); //$NON-NLS-1$
            return canonicalLog.toPath().startsWith(tempDir.toPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static String tailFile(File file, int maxLines) {
        try {
            ArrayDeque<String> lines = new ArrayDeque<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                lines.addLast(line);
                while (lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }
            return String.join("\n", lines); //$NON-NLS-1$
        } catch (IOException e) {
            return ""; //$NON-NLS-1$
        }
    }

    private static JsonArray suggestedToolCalls(String toolName, ToolErrorParser.ParsedToolError parsed) {
        JsonArray array = new JsonArray();
        String code = parsed.errorCode();
        String projectName = parsed.projectName();
        if (code == null) {
            return array;
        }

        if (isEdtRuntimeConfigurationError(code) && projectName != null) {
            JsonObject call = new JsonObject();
            call.addProperty("tool", "qa_inspect"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject arguments = new JsonObject();
            arguments.addProperty("command", "status"); //$NON-NLS-1$ //$NON-NLS-2$
            arguments.addProperty("project_name", projectName); //$NON-NLS-1$
            arguments.addProperty("use_edt_runtime", true); //$NON-NLS-1$
            call.add("arguments", arguments); //$NON-NLS-1$
            array.add(call);
        }

        if (EdtToolErrorCode.PROCESS_TIMEOUT.name().equals(code) && projectName != null
                && "edt_launch_app".equals(toolName)) { //$NON-NLS-1$
            JsonObject call = new JsonObject();
            call.addProperty("tool", "edt_diagnostics"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject arguments = new JsonObject();
            arguments.addProperty("command", "launch_app"); //$NON-NLS-1$ //$NON-NLS-2$
            arguments.addProperty("project_name", projectName); //$NON-NLS-1$
            arguments.addProperty("wait_for_exit", false); //$NON-NLS-1$
            call.add("arguments", arguments); //$NON-NLS-1$
            array.add(call);
        }

        return array;
    }

    private static List<String> likelyCauses(String toolName, ToolErrorParser.ParsedToolError parsed) {
        String code = parsed.errorCode();
        if (EdtToolErrorCode.INVALID_ARGUMENT.name().equals(code)) {
            return List.of(
                    "Вызов инструмента собран с отсутствующими или неверными параметрами.", //$NON-NLS-1$
                    "Тип параметра не соответствует JSON schema инструмента."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROJECT_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "Указан неверный project_name.", //$NON-NLS-1$
                    "Проект отсутствует или закрыт в текущем EDT workspace."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "Для EDT проекта не настроена ассоциация с инфобазой.", //$NON-NLS-1$
                    "Импортированный проект еще не привязан к рабочей базе."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.INFOBASE_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "EDT не смог разрешить связанную инфобазу.", //$NON-NLS-1$
                    "Подключение к инфобазе устарело, удалено или недоступно."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.LAUNCH_CONFIG_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "Для проекта отсутствует RuntimeClient launch configuration.", //$NON-NLS-1$
                    "Launch configuration указывает на другой проект или неверный тип запуска."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.RUNTIME_VERSION_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "В launch configuration выключен auto runtime, но версия платформы не указана.", //$NON-NLS-1$
                    "Конфигурация запуска EDT сохранена неполностью."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.RUNTIME_NOT_RESOLVED.name().equals(code)) {
            return List.of(
                    "Требуемая версия платформы 1С не установлена или не видна EDT.", //$NON-NLS-1$
                    "EDT runtime не смог разрешить thick client для проекта."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.UPDATE_FAILED.name().equals(code)) {
            return List.of(
                    "Обновление инфобазы завершилось ошибкой на стороне EDT или платформы.", //$NON-NLS-1$
                    "Есть блокировка, проблема аутентификации или несинхронизированные изменения."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROCESS_START_FAILED.name().equals(code)) {
            return List.of(
                    "Клиент 1С не удалось запустить или он завершился с ненулевым кодом.", //$NON-NLS-1$
                    "Проблема в runtime version, параметрах запуска или доступе к инфобазе."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROCESS_TIMEOUT.name().equals(code)) {
            return List.of(
                    "Процесс запуска завис, ждет ручного действия или стартует дольше ожидаемого.", //$NON-NLS-1$
                    "timeout_s слишком мал для текущего проекта или среды."); //$NON-NLS-1$
        }
        if (code != null && code.startsWith("QA_")) { //$NON-NLS-1$
            return List.of(
                    "Проблема в QA конфигурации, feature-плане или путях артефактов.", //$NON-NLS-1$
                    "Окружение Vanessa Automation не готово к выполнению сценария."); //$NON-NLS-1$
        }
        if ("edt_launch_app".equals(toolName) || "edt_update_infobase".equals(toolName)) { //$NON-NLS-1$ //$NON-NLS-2$
            return List.of(
                    "Ошибка произошла в EDT runtime pipeline: project -> infobase -> launch config -> runtime.", //$NON-NLS-1$
                    "Нужно проверить project_name, привязку инфобазы и RuntimeClient.launch."); //$NON-NLS-1$
        }
        return List.of("Инструмент вернул ошибку без стандартной диагностики; нужна ручная проверка сообщения и контекста."); //$NON-NLS-1$
    }

    private static List<String> recommendedActions(String toolName, ToolErrorParser.ParsedToolError parsed) {
        String code = parsed.errorCode();
        if (EdtToolErrorCode.INVALID_ARGUMENT.name().equals(code)) {
            return List.of(
                    "Сверь аргументы вызова с JSON schema инструмента и повтори запуск.", //$NON-NLS-1$
                    "Если параметр передается из предыдущего tool, проверь тип и обязательные поля."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROJECT_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "Проверь точное имя EDT проекта в текущем workspace.", //$NON-NLS-1$
                    "Запусти qa_inspect с command=status и use_edt_runtime=true для этого проекта."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.INFOBASE_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "Проверь association проекта с инфобазой в EDT.", //$NON-NLS-1$
                    "Повтори qa_inspect с command=status, use_edt_runtime=true и тем же project_name."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.LAUNCH_CONFIG_NOT_FOUND.name().equals(code)) {
            return List.of(
                    "Создай или исправь RuntimeClient launch configuration для проекта в EDT.", //$NON-NLS-1$
                    "После исправления снова вызови qa_inspect(command=status), затем edt_diagnostics(command=launch_app)."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.RUNTIME_VERSION_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.RUNTIME_NOT_RESOLVED.name().equals(code)) {
            return List.of(
                    "Проверь установленную версию платформы 1С и настройки runtime в EDT.", //$NON-NLS-1$
                    "Если возможно, включи auto runtime или укажи корректную версию в launch configuration."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.UPDATE_FAILED.name().equals(code)) {
            return List.of(
                    "Проверь log_path и диагностики EDT, затем повтори edt_diagnostics(command=update_infobase).", //$NON-NLS-1$
                    "Исключи блокировки инфобазы и ошибки аутентификации."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROCESS_START_FAILED.name().equals(code)) {
            return List.of(
                    "Проверь log_path, runtime_version и параметры запуска клиента.", //$NON-NLS-1$
                    "Сначала устрани конфигурационные проблемы через qa_inspect(command=status), потом повтори edt_diagnostics(command=launch_app)."); //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROCESS_TIMEOUT.name().equals(code)) {
            return List.of(
                    "Посмотри log_tail и реши, нужен ли больший timeout_s.", //$NON-NLS-1$
                    "Для long-running запуска используй wait_for_exit=false."); //$NON-NLS-1$
        }
        if (code != null && code.startsWith("QA_")) { //$NON-NLS-1$
            return List.of(
                    "Сначала вызови qa_inspect(command=status) и устрани ошибки окружения.", //$NON-NLS-1$
                    "Если ошибка связана с feature, повтори qa_validate_feature перед qa_run."); //$NON-NLS-1$
        }
        if ("edt_launch_app".equals(toolName) || "edt_update_infobase".equals(toolName)) { //$NON-NLS-1$ //$NON-NLS-2$
            return List.of(
                    "Проверь project_name, qa_inspect(command=status) и RuntimeClient launch configuration.", //$NON-NLS-1$
                    "Если есть log_path, проанализируй хвост лога и повтори вызов только после исправления причины."); //$NON-NLS-1$
        }
        return List.of(
                "Используй message/error_code как первичный источник причины, не подменяй его догадками.", //$NON-NLS-1$
                "При необходимости передай полный raw tool_result в ручной разбор."); //$NON-NLS-1$
    }

    private static String buildSummary(String toolName, ToolErrorParser.ParsedToolError parsed) {
        String code = parsed.errorCode();
        if (EdtToolErrorCode.INVALID_ARGUMENT.name().equals(code)) {
            return "Инструмент вызван с некорректными параметрами."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROJECT_NOT_FOUND.name().equals(code)) {
            return "EDT не нашел запрошенный проект в текущем workspace."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND.name().equals(code)) {
            return "У EDT проекта не настроена ассоциация с инфобазой."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.INFOBASE_NOT_FOUND.name().equals(code)) {
            return "Связанная инфобаза проекта не разрешилась в EDT runtime."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.LAUNCH_CONFIG_NOT_FOUND.name().equals(code)) {
            return "Для проекта не найден RuntimeClient launch configuration."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.RUNTIME_VERSION_NOT_FOUND.name().equals(code)) {
            return "В EDT launch configuration не указана версия runtime при выключенном auto режиме."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.RUNTIME_NOT_RESOLVED.name().equals(code)) {
            return "EDT runtime не смог разрешить подходящий клиент 1С."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.UPDATE_FAILED.name().equals(code)) {
            return "Обновление инфобазы через EDT завершилось ошибкой."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROCESS_START_FAILED.name().equals(code)) {
            return "Запуск приложения завершился ошибкой или ненулевым кодом выхода."; //$NON-NLS-1$
        }
        if (EdtToolErrorCode.PROCESS_TIMEOUT.name().equals(code)) {
            return "Запущенный процесс не завершился за отведенное время."; //$NON-NLS-1$
        }
        if (code != null && code.startsWith("QA_")) { //$NON-NLS-1$
            return "QA pipeline завершился ошибкой конфигурации или подготовки сценария."; //$NON-NLS-1$
        }
        if (code != null) {
            return "Инструмент завершился с ошибкой " + code + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (toolName != null && !toolName.isBlank()) {
            return "Инструмент " + toolName + " вернул неструктурированную ошибку."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "Инструмент вернул неструктурированную ошибку."; //$NON-NLS-1$
    }

    private static boolean resolveRecoverable(ToolErrorParser.ParsedToolError parsed) {
        if (parsed.recoverable() != null) {
            return parsed.recoverable().booleanValue();
        }
        String code = parsed.errorCode();
        if (code == null) {
            return false;
        }
        return !("INTERNAL_ERROR".equals(code) || "INTERRUPTED".equals(code)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isEdtRuntimeConfigurationError(String code) {
        return EdtToolErrorCode.PROJECT_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.INFOBASE_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.LAUNCH_CONFIG_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.RUNTIME_VERSION_NOT_FOUND.name().equals(code)
                || EdtToolErrorCode.RUNTIME_NOT_RESOLVED.name().equals(code)
                || EdtToolErrorCode.UPDATE_FAILED.name().equals(code)
                || EdtToolErrorCode.PROCESS_START_FAILED.name().equals(code)
                || EdtToolErrorCode.PROCESS_TIMEOUT.name().equals(code);
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static int parsePositiveInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
