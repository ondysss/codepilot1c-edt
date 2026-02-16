package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

/**
 * Scans top-level metadata objects from EDT configuration.
 */
public class EdtMetadataIndexService {

    private static final Map<String, String> SCOPE_ALIASES = Map.ofEntries(
            Map.entry("catalog", "catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("catalogs", "catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("document", "documents"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("documents", "documents"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commonmodule", "commonmodules"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commonmodules", "commonmodules"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("enum", "enums"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("enums", "enums"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("report", "reports"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("reports", "reports"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("dataprocessor", "dataprocessors"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("dataprocessors", "dataprocessors"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("informationregister", "informationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("informationregisters", "informationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accumulationregister", "accumulationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accumulationregisters", "accumulationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accountingregister", "accountingregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accountingregisters", "accountingregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("calculationregister", "calculationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("calculationregisters", "calculationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofaccounts", "chartofaccounts"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofcharacteristictypes", "chartofcharacteristictypes"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofcalculationtypes", "chartofcalculationtypes"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("businessprocess", "businessprocesses"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("businessprocesses", "businessprocesses"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("task", "tasks"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("tasks", "tasks"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("constant", "constants"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("constants", "constants"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sequence", "sequences"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sequences", "sequences"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("exchangeplan", "exchangeplans"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("exchangeplans", "exchangeplans"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("subsystem", "subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("subsystems", "subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("role", "roles"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("roles", "roles"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("interface", "interfaces"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("interfaces", "interfaces"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("session", "sessions"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sessions", "sessions"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("scheduledjob", "scheduledjobs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("scheduledjobs", "scheduledjobs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commoncommand", "commoncommands"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commoncommands", "commoncommands"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("справочник", "catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("справочники", "catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("документ", "documents"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("документы", "documents"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("общиймодуль", "commonmodules"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("общиемодули", "commonmodules"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("перечисление", "enums"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("перечисления", "enums"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("отчет", "reports"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("отчеты", "reports"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("обработка", "dataprocessors"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("обработки", "dataprocessors"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("регистрсведений", "informationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("регистрысведений", "informationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("регистрнакопления", "accumulationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("регистрынакопления", "accumulationregisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("плансчетов", "chartofaccounts"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("планвидовхарактеристик", "chartofcharacteristictypes"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("планвидоврасчета", "chartofcalculationtypes"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("бизнеспроцесс", "businessprocesses"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("бизнеспроцессы", "businessprocesses"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("задача", "tasks"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("задачи", "tasks"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("константа", "constants"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("константы", "constants"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("последовательность", "sequences"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("последовательности", "sequences"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("планобмена", "exchangeplans"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("планыобмена", "exchangeplans"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("подсистема", "subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("подсистемы", "subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("роль", "roles"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("роли", "roles"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("интерфейс", "interfaces"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("интерфейсы", "interfaces"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("сеанс", "sessions"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("сеансы", "sessions"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("регламентноезадание", "scheduledjobs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("регламентныезадания", "scheduledjobs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("общаякоманда", "commoncommands"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("общиекоманды", "commoncommands") //$NON-NLS-1$ //$NON-NLS-2$
    );

    private static final List<String> OBJECT_MODULE_FEATURES = List.of(
            "objectModule", //$NON-NLS-1$
            "module", //$NON-NLS-1$
            "recordSetModule", //$NON-NLS-1$
            "valueManagerModule" //$NON-NLS-1$
    );
    private static final Map<String, String> TYPE_LABELS_RU = Map.ofEntries(
            Map.entry("catalogs", "Справочники"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("documents", "Документы"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commonmodules", "ОбщиеМодули"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("enums", "Перечисления"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("reports", "Отчеты"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("dataprocessors", "Обработки"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("informationregisters", "РегистрыСведений"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accumulationregisters", "РегистрыНакопления"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accountingregisters", "РегистрыБухгалтерии"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("calculationregisters", "РегистрыРасчета"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofaccounts", "ПланыСчетов"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofcharacteristictypes", "ПланыВидовХарактеристик"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofcalculationtypes", "ПланыВидовРасчета"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("businessprocesses", "БизнесПроцессы"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("tasks", "Задачи"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("constants", "Константы"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sequences", "Последовательности"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("exchangeplans", "ПланыОбмена"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("subsystems", "Подсистемы"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("roles", "Роли"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("interfaces", "Интерфейсы"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sessions", "Сеансы"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("scheduledjobs", "РегламентныеЗадания"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commoncommands", "ОбщиеКоманды") //$NON-NLS-1$ //$NON-NLS-2$
    );
    private static final Map<String, String> TYPE_LABELS_EN = Map.ofEntries(
            Map.entry("catalogs", "Catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("documents", "Documents"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commonmodules", "CommonModules"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("enums", "Enums"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("reports", "Reports"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("dataprocessors", "DataProcessors"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("informationregisters", "InformationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accumulationregisters", "AccumulationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("accountingregisters", "AccountingRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("calculationregisters", "CalculationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofaccounts", "ChartOfAccounts"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofcharacteristictypes", "ChartOfCharacteristicTypes"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("chartofcalculationtypes", "ChartOfCalculationTypes"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("businessprocesses", "BusinessProcesses"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("tasks", "Tasks"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("constants", "Constants"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sequences", "Sequences"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("exchangeplans", "ExchangePlans"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("subsystems", "Subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("roles", "Roles"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("interfaces", "Interfaces"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("sessions", "Sessions"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("scheduledjobs", "ScheduledJobs"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("commoncommands", "CommonCommands") //$NON-NLS-1$ //$NON-NLS-2$
    );

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;

    public EdtMetadataIndexService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    public MetadataIndexResult scan(MetadataIndexRequest request) {
        request.validate();

        IProject project = gateway.resolveProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configProvider = gateway.getConfigurationProvider();
        Configuration configuration = configProvider.getConfiguration(project);
        if (configuration == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration is unavailable for project", false); //$NON-NLS-1$
        }

        String scope = request.normalizedScope();
        String nameFilter = request.normalizedNameContains();
        String language = resolveLanguage(configuration, request.normalizedLanguage());

        List<MetadataIndexResult.Item> collected = collect(configuration, scope, nameFilter, language);
        collected.sort(Comparator
                .comparing(MetadataIndexResult.Item::getKind, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MetadataIndexResult.Item::getName, String.CASE_INSENSITIVE_ORDER));

        int total = collected.size();
        int returned = Math.min(request.limit(), total);
        boolean hasMore = total > returned;
        List<MetadataIndexResult.Item> page = returned == total
                ? collected
                : new ArrayList<>(collected.subList(0, returned));

        return new MetadataIndexResult(
                request.projectName(),
                "edt_configuration_scan", //$NON-NLS-1$
                scope,
                language,
                total,
                returned,
                hasMore,
                page);
    }

    private List<MetadataIndexResult.Item> collect(
            Configuration configuration,
            String scope,
            String nameFilter,
            String language) {
        List<MetadataIndexResult.Item> items = new ArrayList<>();

        for (EReference reference : configuration.eClass().getEAllReferences()) {
            if (!reference.isContainment() || !reference.isMany()) {
                continue;
            }
            if (reference.isDerived() || reference.isTransient() || reference.isVolatile()) {
                continue;
            }
            Object raw = configuration.eGet(reference);
            if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
                continue;
            }

            String collectionToken = normalize(reference.getName());
            String singularToken = singularize(collectionToken);

            for (Object element : collection) {
                if (!(element instanceof EObject eObject)) {
                    continue;
                }
                String kind = safe(eObject.eClass().getName());
                if (!matchesScope(scope, collectionToken, singularToken, kind)) {
                    continue;
                }
                String name = readStringFeature(eObject, "name"); //$NON-NLS-1$
                if (!nameFilter.isEmpty() && !normalize(name).contains(nameFilter)) {
                    continue;
                }
                String canonicalKind = canonicalScope(kind);
                String canonicalCollection = canonicalScope(reference.getName());
                String localizedKind = localizeTypeLabel(canonicalKind, language, kind);
                String localizedCollection = localizeTypeLabel(canonicalCollection, language, reference.getName());

                items.add(new MetadataIndexResult.Item(
                        safeFqn(eObject, kind, name),
                        name,
                        resolveSynonym(readSynonymFeature(eObject), language),
                        readStringFeature(eObject, "comment"), //$NON-NLS-1$
                        localizedKind,
                        localizedCollection,
                        hasAnyFeature(eObject, OBJECT_MODULE_FEATURES),
                        hasAnyFeature(eObject, List.of("managerModule")))); //$NON-NLS-1$
            }
        }

        return items;
    }

    private boolean matchesScope(String scope, String collectionToken, String singularToken, String kind) {
        if (scope == null || scope.isBlank() || "all".equals(scope)) { //$NON-NLS-1$
            return true;
        }

        String canonicalScope = canonicalScope(scope);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(canonicalScope(collectionToken));
        candidates.add(canonicalScope(singularToken));
        candidates.add(canonicalScope(kind));
        candidates.add(canonicalScope(singularize(collectionToken)));
        candidates.add(canonicalScope(singularize(kind)));

        return candidates.contains(canonicalScope);
    }

    private boolean hasAnyFeature(EObject object, List<String> featureNames) {
        for (String featureName : featureNames) {
            EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
            if (feature == null) {
                continue;
            }
            Object value = object.eGet(feature);
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private EMap<String, String> readSynonymFeature(EObject object) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature("synonym"); //$NON-NLS-1$
        if (feature == null) {
            return null;
        }
        Object value = object.eGet(feature);
        if (value instanceof EMap<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            EMap<String, String> cast = (EMap<String, String>) rawMap;
            return cast;
        }
        return null;
    }

    private String readStringFeature(EObject object, String featureName) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return ""; //$NON-NLS-1$
        }
        Object value = object.eGet(feature);
        return value != null ? String.valueOf(value) : ""; //$NON-NLS-1$
    }

    private String resolveLanguage(Configuration configuration, String requestedLanguage) {
        if (requestedLanguage != null && !requestedLanguage.isBlank()) {
            return requestedLanguage;
        }
        if (configuration.getDefaultLanguage() != null && configuration.getDefaultLanguage().getName() != null) {
            return configuration.getDefaultLanguage().getName().toLowerCase(Locale.ROOT);
        }
        return "ru"; //$NON-NLS-1$
    }

    private String resolveSynonym(EMap<String, String> synonymMap, String language) {
        if (synonymMap == null || synonymMap.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        if (language != null) {
            String direct = synonymMap.get(language);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        for (Map.Entry<String, String> entry : synonymMap.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return ""; //$NON-NLS-1$
    }

    private String safeFqn(EObject object, String kind, String name) {
        if (object instanceof IBmObject bmObject) {
            try {
                if (!bmObject.bmIsTransient()) {
                    IBmObject top = bmObject;
                    if (!top.bmIsTop()) {
                        top = top.bmGetTopObject();
                    }
                    if (top != null && !top.bmIsTransient() && top.bmIsTop()) {
                        String fqn = top.bmGetFqn();
                        if (fqn != null && !fqn.isBlank()) {
                            return fqn;
                        }
                    }
                }
            } catch (RuntimeException e) {
                // Detached BM object may throw on bmGetFqn()/bmGetTopObject(); fallback below.
            }
        }
        return kind + "." + name; //$NON-NLS-1$
    }

    private String canonicalScope(String value) {
        String normalized = normalize(value).replaceAll("[\\s_.-]+", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String alias = SCOPE_ALIASES.get(normalized);
        return alias != null ? alias : normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String singularize(String value) {
        if (value == null || value.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        if (value.endsWith("ies")) { //$NON-NLS-1$
            return value.substring(0, value.length() - 3) + "y"; //$NON-NLS-1$
        }
        if (value.endsWith("s") && !value.endsWith("ss")) { //$NON-NLS-1$ //$NON-NLS-2$
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String safe(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private String localizeTypeLabel(String canonicalType, String language, String fallback) {
        String lang = normalize(language);
        if (lang.startsWith("ru")) { //$NON-NLS-1$
            String label = TYPE_LABELS_RU.get(canonicalType);
            if (label != null) {
                return label;
            }
        }
        String en = TYPE_LABELS_EN.get(canonicalType);
        if (en != null) {
            return en;
        }
        return safe(fallback);
    }
}
