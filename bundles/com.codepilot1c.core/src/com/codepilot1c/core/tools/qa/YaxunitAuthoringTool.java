package com.codepilot1c.core.tools.qa;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

import com.codepilot1c.core.diagnostics.DiagnosticsService;
import com.codepilot1c.core.diagnostics.DiagnosticsService.DiagnosticsSummary;
import com.codepilot1c.core.edt.metadata.CreateMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataNameValidator;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.metadata.ModuleArtifactKind;
import com.codepilot1c.core.edt.metadata.ModuleArtifactResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Tool for authoring YAxUnit tests in a common module with EDT-safe mutations.
 */
@ToolMeta(
        name = "author_yaxunit_tests",
        category = "metadata",
        surfaceCategory = "qa",
        mutating = true,
        tags = {"workspace", "edt"})
public class YaxunitAuthoringTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(YaxunitAuthoringTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String REGION_BEGIN = "// === YAXUNIT AUTO TESTS: BEGIN ==="; //$NON-NLS-1$
    private static final String REGION_END = "// === YAXUNIT AUTO TESTS: END ==="; //$NON-NLS-1$
    private static final String DEFAULT_SUBSYSTEM = "Tests"; //$NON-NLS-1$
    private static final String DEFAULT_SAMPLE_TEST = "TestSample"; //$NON-NLS-1$
    private static final String DEFAULT_DATA_HELPER = "ЮТДанные.Подготовить();"; //$NON-NLS-1$

    private static final Pattern PROCEDURE_PATTERN = Pattern.compile(
            "(?s)Процедура\\s+([\\p{L}0-9_]+).*?КонецПроцедуры"); //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "description": "Создает/обновляет YAxUnit tests в общем модуле. Зарезервированные имена реквизитов EDT запрещены; используйте безопасные альтернативы.",
              "additionalProperties": false,
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "feature": {
                  "type": "string",
                  "description": "Название фичи для модуля AutoTests_<Feature> (если module_name не задан)"
                },
                "module_name": {
                  "type": "string",
                  "description": "Имя общего модуля для тестов (переопределяет feature)"
                },
                "module_synonym": {
                  "type": "string",
                  "description": "Синоним модуля"
                },
                "module_comment": {
                  "type": "string",
                  "description": "Комментарий модуля"
                },
                "subsystem_name": {
                  "type": "string",
                  "description": "Имя подсистемы для тестов (по умолчанию Tests)"
                },
                "subsystem_synonym": {
                  "type": "string",
                  "description": "Синоним подсистемы"
                },
                "default_data_setup": {
                  "type": "string",
                  "description": "Строка подготовки данных через YAxUnit helper (должна содержать ЮТДанные)"
                },
                "tests": {
                  "type": "array",
                  "description": "Тесты для upsert/append. Каждый тест обязан использовать helper ЮТДанные.",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "properties": {
                      "name": {"type": "string", "description": "Имя процедуры теста"},
                      "description": {"type": "string", "description": "Комментарий/описание теста"},
                      "data_setup": {"type": "string", "description": "Подготовка данных через ЮТДанные.*"},
                      "arrange": {"type": "string", "description": "Arrange-блок"},
                      "act": {"type": "string", "description": "Act-блок"},
                      "assert": {"type": "string", "description": "Assert-блок (должен содержать ЮТест.*)"},
                      "enabled": {"type": "boolean", "description": "Флаг включения теста (по умолчанию true)"}
                    },
                    "required": ["name"]
                  }
                },
                "remove_tests": {
                  "type": "array",
                  "description": "Список тестов для удаления",
                  "items": { "type": "string" }
                },
                "replace_all": {
                  "type": "boolean",
                  "description": "Если true, удаляет все существующие автотесты и заменяет на tests[]"
                },
                "diagnostics_wait_ms": {
                  "type": "integer",
                  "description": "Ожидание перед сбором диагностик (ms, default=0)"
                },
                "diagnostics_max_items": {
                  "type": "integer",
                  "description": "Лимит диагностик в ответе (default=200)"
                }
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;
    private final DiagnosticsService diagnosticsService;

    public YaxunitAuthoringTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService(), new DiagnosticsService());
    }

    YaxunitAuthoringTool(
            EdtMetadataService metadataService,
            MetadataRequestValidationService validationService,
            DiagnosticsService diagnosticsService
    ) {
        this.metadataService = metadataService;
        this.validationService = validationService;
        this.diagnosticsService = diagnosticsService;
    }

    @Override
    public String getDescription() {
        return "Создает или обновляет YAxUnit тесты в общем модуле и синхронизирует регистрацию ИсполняемыеСценарии. Используй для unit or integration-style тестов на встроенном языке. Для BDD feature-пайплайна Vanessa prefer qa_plan_scenario, qa_generate, qa_validate_feature, qa_run."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("yaxunit-auth"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START author_yaxunit_tests", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));

            try {
                String project = stringParam(parameters, "project"); //$NON-NLS-1$
                if (project == null || project.isBlank()) {
                    return failure(opId, "INVALID_ARGUMENT", "project is required", false); //$NON-NLS-1$ //$NON-NLS-2$
                }

                String subsystemName = normalizeName(stringParam(parameters, "subsystem_name"), DEFAULT_SUBSYSTEM); //$NON-NLS-1$
                String subsystemSynonym = stringParam(parameters, "subsystem_synonym"); //$NON-NLS-1$
                String feature = stringParam(parameters, "feature"); //$NON-NLS-1$
                String moduleName = stringParam(parameters, "module_name"); //$NON-NLS-1$
                String moduleSynonym = stringParam(parameters, "module_synonym"); //$NON-NLS-1$
                String moduleComment = stringParam(parameters, "module_comment"); //$NON-NLS-1$

                if (moduleName == null || moduleName.isBlank()) {
                    if (feature == null || feature.isBlank()) {
                        return failure(opId, "INVALID_ARGUMENT", "feature or module_name is required", false); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    moduleName = "AutoTests_" + sanitizeName(feature); //$NON-NLS-1$
                }

                if (!MetadataNameValidator.isValidName(subsystemName)) {
                    return failure(opId, "INVALID_METADATA_NAME",
                            "Invalid subsystem name: " + subsystemName, false); //$NON-NLS-1$
                }
                if (!MetadataNameValidator.isValidName(moduleName)) {
                    return failure(opId, "INVALID_METADATA_NAME",
                            "Invalid module name: " + moduleName, false); //$NON-NLS-1$
                }

                List<TestSpec> incomingTests = parseTests(parameters, opId);
                boolean replaceAll = Boolean.TRUE.equals(parameters.get("replace_all")); //$NON-NLS-1$
                List<String> removeTests = parseStringList(parameters.get("remove_tests")); //$NON-NLS-1$
                String defaultDataSetup = stringParam(parameters, "default_data_setup"); //$NON-NLS-1$
                if (defaultDataSetup != null && !containsDataHelper(defaultDataSetup)) {
                    return failure(opId, "DATA_HELPER_REQUIRED",
                            "default_data_setup must include ЮТДанные helper", false); //$NON-NLS-1$ //$NON-NLS-2$
                }

                String subsystemFqn = "Subsystem." + subsystemName; //$NON-NLS-1$
                String moduleFqn = "CommonModule." + moduleName; //$NON-NLS-1$

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("ok", Boolean.TRUE); //$NON-NLS-1$
                output.put("op_id", opId); //$NON-NLS-1$
                output.put("project", project); //$NON-NLS-1$

                Map<String, Object> subsystemResult = ensureSubsystem(project, subsystemName, subsystemSynonym, opId);
                output.put("subsystem", subsystemResult); //$NON-NLS-1$

                Map<String, Object> moduleResult = ensureCommonModule(
                        project,
                        moduleName,
                        moduleSynonym,
                        moduleComment,
                        opId);
                output.put("module", moduleResult); //$NON-NLS-1$

                ModuleArtifactResult artifact = ensureModuleArtifact(project, moduleFqn, opId);
                moduleResult.put("module_path", artifact.modulePath()); //$NON-NLS-1$

                TestUpdateSummary summary = updateModuleTests(
                        project,
                        artifact.modulePath(),
                        incomingTests,
                        removeTests,
                        replaceAll,
                        defaultDataSetup,
                        opId);
                output.put("tests", summary.toMap()); //$NON-NLS-1$

                if (summary.usedFallbackDataSetup && defaultDataSetup == null) {
                    output.put("warnings", List.of("data_setup missing, used default ЮТДанные helper")); //$NON-NLS-1$ //$NON-NLS-2$
                }

                int diagWait = intParam(parameters, "diagnostics_wait_ms", 0); //$NON-NLS-1$
                int diagMax = intParam(parameters, "diagnostics_max_items", 200); //$NON-NLS-1$
                DiagnosticsSummary diagnostics = diagnosticsService.collectProjectDiagnostics(project, diagMax, diagWait);
                output.put("diagnostics", diagnostics); //$NON-NLS-1$

                output.put("duration_ms", System.currentTimeMillis() - startedAt); //$NON-NLS-1$
                LOG.info("[%s] SUCCESS author_yaxunit_tests in %s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt));
                return ToolResult.success(GSON.toJson(output));
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED author_yaxunit_tests: %s (%s)", opId, e.getMessage(), e.getCode()); //$NON-NLS-1$
                return failure(opId, e.getCode().name(), e.getMessage(), e.isRecoverable());
            } catch (Exception e) {
                LOG.error("[" + opId + "] author_yaxunit_tests failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(opId, "INTERNAL_ERROR", e.getMessage(), false); //$NON-NLS-1$
            }
        });
    }

    private Map<String, Object> ensureSubsystem(
            String project,
            String subsystemName,
            String subsystemSynonym,
            String opId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn", "Subsystem." + subsystemName); //$NON-NLS-1$ //$NON-NLS-2$
        result.put("name", subsystemName); //$NON-NLS-1$

        try {
            MetadataOperationResult createResult = createMetadataWithValidation(
                    project,
                    MetadataKind.SUBSYSTEM,
                    subsystemName,
                    subsystemSynonym,
                    null,
                    Map.of(),
                    opId);
            result.put("status", "created"); //$NON-NLS-1$ //$NON-NLS-2$
            result.put("message", createResult.message()); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            if (e.getCode() == MetadataOperationCode.METADATA_ALREADY_EXISTS) {
                result.put("status", "existing"); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                throw e;
            }
        }
        return result;
    }

    private Map<String, Object> ensureCommonModule(
            String project,
            String moduleName,
            String moduleSynonym,
            String moduleComment,
            String opId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn", "CommonModule." + moduleName); //$NON-NLS-1$ //$NON-NLS-2$
        result.put("name", moduleName); //$NON-NLS-1$

        try {
            MetadataOperationResult createResult = createMetadataWithValidation(
                    project,
                    MetadataKind.COMMON_MODULE,
                    moduleName,
                    moduleSynonym,
                    moduleComment,
                    Map.of(),
                    opId);
            result.put("status", "created"); //$NON-NLS-1$ //$NON-NLS-2$
            result.put("message", createResult.message()); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            if (e.getCode() == MetadataOperationCode.METADATA_ALREADY_EXISTS) {
                result.put("status", "existing"); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                throw e;
            }
        }

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("clientManagedApplication", Boolean.TRUE); //$NON-NLS-1$
        set.put("clientOrdinaryApplication", Boolean.TRUE); //$NON-NLS-1$
        set.put("server", Boolean.TRUE); //$NON-NLS-1$
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("set", set); //$NON-NLS-1$

        updateMetadataWithValidation(project, "CommonModule." + moduleName, changes, opId); //$NON-NLS-1$
        result.put("client_server", Boolean.TRUE); //$NON-NLS-1$
        return result;
    }

    private ModuleArtifactResult ensureModuleArtifact(String project, String moduleFqn, String opId) {
        return metadataService.ensureModuleArtifact(
                new com.codepilot1c.core.edt.metadata.EnsureModuleArtifactRequest(
                        project,
                        moduleFqn,
                        ModuleArtifactKind.MODULE,
                        true,
                        "")); //$NON-NLS-1$
    }

    private TestUpdateSummary updateModuleTests(
            String project,
            String modulePath,
            List<TestSpec> incomingTests,
            List<String> removeTests,
            boolean replaceAll,
            String defaultDataSetup,
            String opId
    ) {
        IFile moduleFile = resolveWorkspaceFile(modulePath);
        if (moduleFile == null || !moduleFile.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Module file not found: " + modulePath, false); //$NON-NLS-1$
        }

        String current = readFile(moduleFile);
        Map<String, String> existing = extractExistingTests(current);
        Map<String, String> finalTests = new LinkedHashMap<>();

        if (!replaceAll) {
            finalTests.putAll(existing);
        }

        Set<String> added = new LinkedHashSet<>();
        Set<String> updated = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        boolean usedFallbackDataSetup = false;

        for (TestSpec spec : incomingTests) {
            if (!spec.enabled) {
                continue;
            }
            String name = spec.name;
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!MetadataNameValidator.isValidName(name)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_NAME,
                        "Invalid test name: " + name, false); //$NON-NLS-1$
            }

            String dataSetup = spec.dataSetup != null ? spec.dataSetup : defaultDataSetup;
            if (dataSetup == null || dataSetup.isBlank()) {
                dataSetup = DEFAULT_DATA_HELPER;
                usedFallbackDataSetup = true;
            }
            if (!containsDataHelper(dataSetup)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.KNOWLEDGE_REQUIRED,
                        "Test " + name + " must include ЮТДанные helper", false); //$NON-NLS-1$ //$NON-NLS-2$
            }

            String generated = buildTestProcedure(spec, dataSetup);
            boolean existed = finalTests.containsKey(name);
            finalTests.put(name, generated);
            if (existed) {
                updated.add(name);
            } else {
                added.add(name);
            }
        }

        for (String toRemove : removeTests) {
            if (toRemove == null || toRemove.isBlank()) {
                continue;
            }
            if (finalTests.remove(toRemove) != null) {
                removed.add(toRemove);
            }
        }

        if (finalTests.isEmpty()) {
            TestSpec sample = new TestSpec(DEFAULT_SAMPLE_TEST, "Sample YAxUnit test", DEFAULT_DATA_HELPER, //$NON-NLS-1$
                    "Перем Назначение = \"Автотест\";", //$NON-NLS-1$
                    "", //$NON-NLS-1$
                    "ЮТест.ПроверитьИстину(Истина, \"Sample assertion\");", //$NON-NLS-1$
                    true);
            finalTests.put(sample.name, buildTestProcedure(sample, sample.dataSetup));
            added.add(sample.name);
            usedFallbackDataSetup = true;
        }

        String regionBody = buildRegionBody(finalTests);
        String nextContent = applyRegion(current, regionBody);
        writeFile(moduleFile, nextContent);

        Set<String> retained = new LinkedHashSet<>(finalTests.keySet());
        retained.removeAll(added);
        retained.removeAll(updated);
        retained.removeAll(removed);

        return new TestUpdateSummary(added, updated, removed, retained, usedFallbackDataSetup);
    }

    private Map<String, String> extractExistingTests(String content) {
        if (content == null || content.isBlank()) {
            return Collections.emptyMap();
        }
        int start = content.indexOf(REGION_BEGIN);
        int end = content.indexOf(REGION_END);
        if (start < 0 || end < 0 || end <= start) {
            return Collections.emptyMap();
        }
        String region = content.substring(start + REGION_BEGIN.length(), end);
        Map<String, String> tests = new LinkedHashMap<>();
        Matcher matcher = PROCEDURE_PATTERN.matcher(region);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name == null) {
                continue;
            }
            if ("ИсполняемыеСценарии".equalsIgnoreCase(name)) { //$NON-NLS-1$
                continue;
            }
            String body = matcher.group(0);
            tests.put(name, body.trim());
        }
        return tests;
    }

    private String buildRegionBody(Map<String, String> testProcedures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Процедура ИсполняемыеСценарии(Результат) Экспорт\n"); //$NON-NLS-1$
        for (String testName : testProcedures.keySet()) {
            sb.append("    ЮТТесты.ДобавитьТест(Результат, \"") //$NON-NLS-1$
                    .append(testName)
                    .append("\");\n"); //$NON-NLS-1$
        }
        sb.append("КонецПроцедуры\n\n"); //$NON-NLS-1$

        int index = 0;
        for (String body : testProcedures.values()) {
            if (index > 0) {
                sb.append('\n');
            }
            sb.append(body).append('\n');
            index++;
        }
        return sb.toString().trim();
    }

    private String buildTestProcedure(TestSpec spec, String dataSetup) {
        StringBuilder sb = new StringBuilder();
        sb.append("Процедура ").append(spec.name).append("() Экспорт\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (spec.description != null && !spec.description.isBlank()) {
            sb.append("    // ").append(spec.description.trim()).append('\n'); //$NON-NLS-1$
        }
        appendIndentedBlock(sb, dataSetup);
        appendIndentedBlock(sb, spec.arrange);
        appendIndentedBlock(sb, spec.act);
        String assertBlock = spec.assertBlock;
        if (assertBlock == null || assertBlock.isBlank()) {
            assertBlock = "ЮТест.ПроверитьИстину(Истина, \"Auto assertion\");"; //$NON-NLS-1$
        } else if (!assertBlock.contains("ЮТест")) { //$NON-NLS-1$
            assertBlock = assertBlock + "\nЮТест.ПроверитьИстину(Истина, \"Auto assertion\");"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        appendIndentedBlock(sb, assertBlock);
        sb.append("КонецПроцедуры"); //$NON-NLS-1$
        return sb.toString();
    }

    private String applyRegion(String current, String regionBody) {
        String region = REGION_BEGIN + "\n" + regionBody + "\n" + REGION_END; //$NON-NLS-1$ //$NON-NLS-2$
        if (current == null || current.isBlank()) {
            return region + "\n"; //$NON-NLS-1$
        }
        int start = current.indexOf(REGION_BEGIN);
        int end = current.indexOf(REGION_END);
        if (start >= 0 && end > start) {
            return current.substring(0, start)
                    + region
                    + current.substring(end + REGION_END.length());
        }
        StringBuilder sb = new StringBuilder(current.trim());
        sb.append("\n\n").append(region).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }

    private void appendIndentedBlock(StringBuilder sb, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        String[] lines = block.replace("\r\n", "\n").replace("\r", "\n").split("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            sb.append("    ").append(line.trim()).append('\n'); //$NON-NLS-1$
        }
    }

    private Map<String, Object> updateMetadataWithValidation(
            String project,
            String targetFqn,
            Map<String, Object> changes,
            String opId
    ) {
        Map<String, Object> payload = validationService.normalizeUpdatePayload(project, targetFqn, changes);
        ValidationResult token = validationService.validateAndIssueToken(
                new ValidationRequest(project, ValidationOperation.UPDATE_METADATA, payload));
        validationService.consumeToken(token.validationToken(), ValidationOperation.UPDATE_METADATA, project);
        MetadataOperationResult result = metadataService.updateMetadata(
                new com.codepilot1c.core.edt.metadata.UpdateMetadataRequest(project, targetFqn, changes));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fqn", result.fqn()); //$NON-NLS-1$
        out.put("message", result.message()); //$NON-NLS-1$
        out.put("op_id", opId); //$NON-NLS-1$
        return out;
    }

    private MetadataOperationResult createMetadataWithValidation(
            String project,
            MetadataKind kind,
            String name,
            String synonym,
            String comment,
            Map<String, Object> properties,
            String opId
    ) {
        Map<String, Object> payload = validationService.normalizeCreatePayload(
                project,
                kind.name(),
                name,
                synonym,
                comment,
                properties);
        ValidationResult token = validationService.validateAndIssueToken(
                new ValidationRequest(project, ValidationOperation.CREATE_METADATA, payload));
        validationService.consumeToken(token.validationToken(), ValidationOperation.CREATE_METADATA, project);
        return metadataService.createMetadata(
                new CreateMetadataRequest(project, kind, name, synonym, comment, properties));
    }

    private IFile resolveWorkspaceFile(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return null;
        }
        String normalized = workspacePath.startsWith("/") ? workspacePath.substring(1) : workspacePath; //$NON-NLS-1$
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getFile(new Path(normalized));
    }

    private String readFile(IFile file) {
        if (file == null || !file.exists()) {
            return ""; //$NON-NLS-1$
        }
        try {
            return new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to read module file: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private void writeFile(IFile file, String content) {
        if (file == null) {
            return;
        }
        try (ByteArrayInputStream source = new ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8))) {
            if (file.exists()) {
                file.setContents(source, IResource.FORCE, null);
            } else {
                file.create(source, IResource.FORCE, null);
            }
            file.refreshLocal(IResource.DEPTH_ZERO, null);
        } catch (IOException | CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to write module file: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private List<TestSpec> parseTests(Map<String, Object> parameters, String opId) {
        Object raw = parameters.get("tests"); //$NON-NLS-1$
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<TestSpec> tests = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) map;
            String name = stringParam(spec, "name"); //$NON-NLS-1$
            String description = stringParam(spec, "description"); //$NON-NLS-1$
            String dataSetup = stringParam(spec, "data_setup"); //$NON-NLS-1$
            String arrange = stringParam(spec, "arrange"); //$NON-NLS-1$
            String act = stringParam(spec, "act"); //$NON-NLS-1$
            String assertBlock = stringParam(spec, "assert"); //$NON-NLS-1$
            boolean enabled = !"false".equalsIgnoreCase(stringParam(spec, "enabled")); //$NON-NLS-1$ //$NON-NLS-2$
            tests.add(new TestSpec(name, description, dataSetup, arrange, act, assertBlock, enabled));
        }
        return tests;
    }

    private List<String> parseStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean containsDataHelper(String block) {
        if (block == null) {
            return false;
        }
        String lower = block.toLowerCase(Locale.ROOT);
        return lower.contains("ютданные") || lower.contains("yaxunit"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String normalizeName(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String sanitizeName(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.replaceAll("[^\\p{L}\\p{N}_]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private ToolResult failure(String opId, String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false); //$NON-NLS-1$
        obj.addProperty("op_id", opId); //$NON-NLS-1$
        JsonObject err = new JsonObject();
        err.addProperty("code", code); //$NON-NLS-1$
        err.addProperty("message", message); //$NON-NLS-1$
        err.addProperty("recoverable", recoverable); //$NON-NLS-1$
        obj.add("error", err); //$NON-NLS-1$
        return ToolResult.failure(GSON.toJson(obj));
    }

    private record TestSpec(
            String name,
            String description,
            String dataSetup,
            String arrange,
            String act,
            String assertBlock,
            boolean enabled
    ) { }

    private static final class TestUpdateSummary {
        private final Set<String> added;
        private final Set<String> updated;
        private final Set<String> removed;
        private final Set<String> retained;
        private final boolean usedFallbackDataSetup;

        private TestUpdateSummary(
                Set<String> added,
                Set<String> updated,
                Set<String> removed,
                Set<String> retained,
                boolean usedFallbackDataSetup
        ) {
            this.added = added;
            this.updated = updated;
            this.removed = removed;
            this.retained = retained;
            this.usedFallbackDataSetup = usedFallbackDataSetup;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", added.size() + updated.size() + retained.size()); //$NON-NLS-1$
            out.put("added", new ArrayList<>(added)); //$NON-NLS-1$
            out.put("updated", new ArrayList<>(updated)); //$NON-NLS-1$
            out.put("removed", new ArrayList<>(removed)); //$NON-NLS-1$
            out.put("retained", new ArrayList<>(retained)); //$NON-NLS-1$
            return out;
        }
    }
}
