package com.codepilot1c.core.edt.extension;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Резолв корня конфигурации по ссылке «Configuration» или
 * «Configuration.&lt;ИмяКонфигурации&gt;».
 *
 * <p>Обход containment-коллекций конфигурации корень не видит по построению —
 * он не элемент собственных коллекций, поэтому extension_manage set_state по
 * корневым свойствам расширения (например, usePurposes) отвечал
 * METADATA_NOT_FOUND, хотя корень расширения заимствован всегда. Используется
 * только в set_state: для adopt корень смысла не имеет, там поведение
 * не меняется.</p>
 *
 * <p>Отдельный класс без зависимостей на инфраструктуру плагина — чтобы
 * paired-eval компилировался против jar'ов EDT без sourcepath-цепочки
 * (см. scripts/run-extension-root-eval.sh).</p>
 */
final class ExtensionRootResolver {

    private static final String KIND = "Configuration"; //$NON-NLS-1$

    private ExtensionRootResolver() {
        // утилитарный класс
    }

    static MdObject resolve(Configuration configuration, String sourceRef) {
        if (configuration == null || sourceRef == null) {
            return null;
        }
        String trimmed = sourceRef.trim();
        if (KIND.equalsIgnoreCase(trimmed)) {
            return configuration;
        }
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || !KIND.equalsIgnoreCase(trimmed.substring(0, dot))) {
            return null;
        }
        String name = trimmed.substring(dot + 1).trim();
        String configurationName = configuration.getName() != null
                ? configuration.getName()
                : ""; //$NON-NLS-1$
        if (!name.isEmpty() && name.equalsIgnoreCase(configurationName)) {
            return configuration;
        }
        return null;
    }
}
