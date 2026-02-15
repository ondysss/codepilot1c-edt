package com.codepilot1c.core.edt.metadata;

import java.util.Locale;

/**
 * Supported top-level metadata kinds.
 */
public enum MetadataKind {
    CATALOG("Catalog", "Справочник"),
    DOCUMENT("Document", "Документ"),
    INFORMATION_REGISTER("InformationRegister", "РегистрСведений"),
    ACCUMULATION_REGISTER("AccumulationRegister", "РегистрНакопления"),
    COMMON_MODULE("CommonModule", "ОбщийМодуль"),
    ENUM("Enum", "Перечисление"),
    REPORT("Report", "Отчет"),
    DATA_PROCESSOR("DataProcessor", "Обработка"),
    CONSTANT("Constant", "Константа"),
    ROLE("Role", "Роль"),
    SUBSYSTEM("Subsystem", "Подсистема"),
    EXCHANGE_PLAN("ExchangePlan", "ПланОбмена"),
    CHART_OF_ACCOUNTS("ChartOfAccounts", "ПланСчетов"),
    CHART_OF_CHARACTERISTIC_TYPES("ChartOfCharacteristicTypes", "ПланВидовХарактеристик"),
    CHART_OF_CALCULATION_TYPES("ChartOfCalculationTypes", "ПланВидовРасчета"),
    BUSINESS_PROCESS("BusinessProcess", "БизнесПроцесс"),
    TASK("Task", "Задача"),
    COMMON_FORM("CommonForm", "ОбщаяФорма"),
    COMMON_COMMAND("CommonCommand", "ОбщаяКоманда"),
    COMMON_TEMPLATE("CommonTemplate", "ОбщийМакет"),
    COMMON_PICTURE("CommonPicture", "ОбщаяКартинка"),
    SCHEDULED_JOB("ScheduledJob", "РегламентноеЗадание"),
    FILTER_CRITERION("FilterCriterion", "КритерийОтбора"),
    DEFINED_TYPE("DefinedType", "ОпределяемыйТип"),
    SEQUENCE("Sequence", "Последовательность"),
    DOCUMENT_JOURNAL("DocumentJournal", "ЖурналДокументов"),
    DOCUMENT_NUMERATOR("DocumentNumerator", "НумераторДокументов"),
    EVENT_SUBSCRIPTION("EventSubscription", "ПодпискаНаСобытие"),
    FUNCTIONAL_OPTION("FunctionalOption", "ФункциональнаяОпция"),
    FUNCTIONAL_OPTIONS_PARAMETER("FunctionalOptionsParameter", "ПараметрФункциональнойОпции"),
    WEB_SERVICE("WebService", "ВебСервис"),
    HTTP_SERVICE("HTTPService", "HTTPСервис"),
    EXTERNAL_DATA_SOURCE("ExternalDataSource", "ВнешнийИсточникДанных"),
    INTEGRATION_SERVICE("IntegrationService", "СервисИнтеграции"),
    BOT("Bot", "Бот"),
    WEB_SOCKET_CLIENT("WebSocketClient", "WebSocketКлиент");

    private final String fqnPrefix;
    private final String ruName;

    MetadataKind(String fqnPrefix, String ruName) {
        this.fqnPrefix = fqnPrefix;
        this.ruName = ruName;
    }

    public String getFqnPrefix() {
        return fqnPrefix;
    }

    public String getRuName() {
        return ruName;
    }

    public static MetadataKind fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Metadata kind is required", false); //$NON-NLS-1$
        }
        String normalized = normalizeToken(value);
        return switch (normalized) {
            case "catalog", "catalogs", "справочник", "справочники" -> CATALOG; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "document", "documents", "документ", "документы" -> DOCUMENT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "informationregister", "informationregisters", "информационныйрегистр", "регистрсведений", "регистрысведений" -> INFORMATION_REGISTER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "accumulationregister", "accumulationregisters", "регистрнакопления", "регистрынакопления" -> ACCUMULATION_REGISTER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "commonmodule", "commonmodules", "общиймодуль", "общиемодули" -> COMMON_MODULE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "enum", "enums", "перечисление", "перечисления" -> ENUM; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "report", "reports", "отчет", "отчеты" -> REPORT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "dataprocessor", "dataprocessors", "обработка", "обработки" -> DATA_PROCESSOR; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "constant", "constants", "константа", "константы" -> CONSTANT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "role", "roles", "роль", "роли" -> ROLE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "subsystem", "subsystems", "подсистема", "подсистемы" -> SUBSYSTEM; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "exchangeplan", "exchangeplans", "планобмена", "планыобмена" -> EXCHANGE_PLAN; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "chartofaccounts", "chartsofaccounts", "плансчетов", "планысчетов" -> CHART_OF_ACCOUNTS; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "chartofcharacteristictypes", "chartsofcharacteristictypes", "планвидовхарактеристик", "планывидовхарактеристик" -> CHART_OF_CHARACTERISTIC_TYPES; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "chartofcalculationtypes", "chartsofcalculationtypes", "планвидоврасчета", "планывидоврасчета" -> CHART_OF_CALCULATION_TYPES; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "businessprocess", "businessprocesses", "бизнеспроцесс", "бизнеспроцессы" -> BUSINESS_PROCESS; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "task", "tasks", "задача", "задачи" -> TASK; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "commonform", "commonforms", "общаяформа", "общиеформы" -> COMMON_FORM; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "commoncommand", "commoncommands", "общаякоманда", "общиекоманды" -> COMMON_COMMAND; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "commontemplate", "commontemplates", "общиймакет", "общиемакеты" -> COMMON_TEMPLATE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "commonpicture", "commonpictures", "общаякартинка", "общиекартинки" -> COMMON_PICTURE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "scheduledjob", "scheduledjobs", "регламентноезадание", "регламентныезадания" -> SCHEDULED_JOB; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "filtercriterion", "filtercriteria", "критерийотбора", "критерииотбора" -> FILTER_CRITERION; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "definedtype", "definedtypes", "определяемыйтип", "определяемыетипы" -> DEFINED_TYPE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "sequence", "sequences", "последовательность", "последовательности" -> SEQUENCE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "documentjournal", "documentjournals", "журналдокументов", "журналыдокументов" -> DOCUMENT_JOURNAL; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "documentnumerator", "documentnumerators", "нумератордокументов", "нумераторыдокументов" -> DOCUMENT_NUMERATOR; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "eventsubscription", "eventsubscriptions", "подписканасобытие", "подпискинасобытия" -> EVENT_SUBSCRIPTION; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "functionaloption", "functionaloptions", "функциональнаяопция", "функциональныеопции" -> FUNCTIONAL_OPTION; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "functionaloptionsparameter", "functionaloptionsparameters", "параметрфункциональнойопции", "параметрыфункциональнойопции" -> FUNCTIONAL_OPTIONS_PARAMETER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "webservice", "webservices", "вебсервис", "вебсервисы" -> WEB_SERVICE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "httpservice", "httpservices", "httpсервис", "httpсервисы" -> HTTP_SERVICE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "externaldatasource", "externaldatasources", "внешнийисточникданных", "внешниеисточникиданных" -> EXTERNAL_DATA_SOURCE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "integrationservice", "integrationservices", "сервисинтеграции", "сервисыинтеграции" -> INTEGRATION_SERVICE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "bot", "bots", "бот", "боты" -> BOT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "websocketclient", "websocketclients", "websocketклиент", "websocketклиенты" -> WEB_SOCKET_CLIENT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported metadata kind: " + value, false); //$NON-NLS-1$
        };
    }

    private static String normalizeToken(String value) {
        String lowered = value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (ch == '_' || ch == '-' || Character.isWhitespace(ch) || ch == '.') {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
