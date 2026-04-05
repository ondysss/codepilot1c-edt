/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * Detects 1C standard libraries from configuration metadata.
 *
 * <p>Uses multiple signals: common modules, subsystems, and constants.
 * All reads are from the EDT Configuration object (project model), not filesystem.</p>
 *
 * <p>Libraries detected: BSP, BIP, BED, BPO, BDO.</p>
 *
 * <p>Important: constant <b>values</b> are stored in the infobase (runtime data).
 * From EDT metadata we can only detect constant <b>existence</b> and read
 * synonym/comment fields as version hints.</p>
 */
public final class LibraryDetector {

    /** Version pattern: digits.digits.digits or digits.digits.digits.digits */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)"); //$NON-NLS-1$

    private LibraryDetector() {
    }

    /**
     * Detects all known 1C standard libraries in the given configuration.
     *
     * @param config the Configuration object from EDT project model
     * @return list of detected libraries (may be empty, never null)
     */
    public static List<DetectedLibrary> detectAll(Configuration config) {
        if (config == null) {
            return List.of();
        }

        List<DetectedLibrary> found = new ArrayList<>();
        detectBsp(config).ifPresent(found::add);
        detectBip(config).ifPresent(found::add);
        detectBed(config).ifPresent(found::add);
        detectBpo(config).ifPresent(found::add);
        detectBdo(config).ifPresent(found::add);
        return List.copyOf(found);
    }

    // --- BSP: Standard Subsystems Library ---

    private static Optional<DetectedLibrary> detectBsp(Configuration config) {
        Set<String> bspModules = Set.of(
                "\u041E\u0431\u0449\u0435\u0433\u043E\u041D\u0430\u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F", // ОбщегоНазначения
                "\u041E\u0431\u0449\u0435\u0433\u043E\u041D\u0430\u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F\u041A\u043B\u0438\u0435\u043D\u0442", // ОбщегоНазначенияКлиент
                "\u041E\u0431\u0449\u0435\u0433\u043E\u041D\u0430\u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F\u041A\u043B\u0438\u0435\u043D\u0442\u0421\u0435\u0440\u0432\u0435\u0440", // ОбщегоНазначенияКлиентСервер
                "\u041E\u0431\u0449\u0435\u0433\u043E\u041D\u0430\u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F\u0421\u0435\u0440\u0432\u0435\u0440", // ОбщегоНазначенияСервер
                "\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0435\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u044B\u0421\u0435\u0440\u0432\u0435\u0440", // СтандартныеПодсистемыСервер
                "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u0438\u0435\u0418\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439\u0411\u0430\u0437\u044B", // ОбновлениеИнформационнойБазы
                "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u0438\u0435\u0418\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439\u0411\u0430\u0437\u044B\u0421\u0435\u0440\u0432\u0435\u0440", // ОбновлениеИнформационнойБазыСервер
                "\u041F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u0438\u0421\u0435\u0440\u0432\u0435\u0440", // ПользователиСервер
                "\u0423\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u0438\u0435\u0414\u043E\u0441\u0442\u0443\u043F\u043E\u043C\u0421\u043B\u0443\u0436\u0435\u0431\u043D\u044B\u0439", // УправлениеДоступомСлужебный
                "\u041E\u0431\u0449\u0435\u0433\u043E\u041D\u0430\u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F\u041F\u043E\u0432\u0442\u0418\u0441\u043F" // ОбщегоНазначенияПовтИсп
        );

        long moduleHits = countModuleHits(config, bspModules);
        if (moduleHits < 3) {
            return Optional.empty();
        }

        boolean hasSubsystem = hasSubsystemNamed(config,
                "\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0435\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u044B"); // СтандартныеПодсистемы

        double confidence = Math.min(1.0, moduleHits * 0.15 + (hasSubsystem ? 0.3 : 0));

        String versionHint = findConstantVersionHint(config,
                "\u0412\u0435\u0440\u0441\u0438\u044F\u0411\u0421\u041F"); // ВерсияБСП
        if (versionHint == null) {
            versionHint = extractVersionFromSubsystemComment(config,
                    "\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0435\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u044B"); // СтандартныеПодсистемы
        }

        return Optional.of(new DetectedLibrary(
                "bsp", "\u0411\u0421\u041F", //$NON-NLS-1$
                "\u0411\u0438\u0431\u043B\u0438\u043E\u0442\u0435\u043A\u0430 \u0441\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0445 \u043F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C", // Библиотека стандартных подсистем
                versionHint, confidence));
    }

    // --- BIP: Internet Support Library ---

    private static Optional<DetectedLibrary> detectBip(Configuration config) {
        Set<String> bipModules = Set.of(
                "\u0418\u043D\u0442\u0435\u0440\u043D\u0435\u0442\u041F\u043E\u0434\u0434\u0435\u0440\u0436\u043A\u0430", // ИнтернетПоддержка
                "\u0418\u043D\u0442\u0435\u0440\u043D\u0435\u0442\u041F\u043E\u0434\u0434\u0435\u0440\u0436\u043A\u0430\u041A\u043B\u0438\u0435\u043D\u0442", // ИнтернетПоддержкаКлиент
                "\u0418\u043D\u0442\u0435\u0440\u043D\u0435\u0442\u041F\u043E\u0434\u0434\u0435\u0440\u0436\u043A\u0430\u0421\u0435\u0440\u0432\u0435\u0440", // ИнтернетПоддержкаСервер
                "\u0418\u043D\u0442\u0435\u0440\u043D\u0435\u0442\u041F\u043E\u0434\u0434\u0435\u0440\u0436\u043A\u0430\u0421\u043B\u0443\u0436\u0435\u0431\u043D\u044B\u0439" // ИнтернетПоддержкаСлужебный
        );

        long hits = countModuleHits(config, bipModules);
        if (hits < 2) {
            return Optional.empty();
        }

        boolean hasSub = hasSubsystemNamed(config,
                "\u0418\u043D\u0442\u0435\u0440\u043D\u0435\u0442\u041F\u043E\u0434\u0434\u0435\u0440\u0436\u043A\u0430"); // ИнтернетПоддержка

        String versionHint = findConstantVersionHint(config,
                "\u0412\u0435\u0440\u0441\u0438\u044F\u0411\u0418\u041F"); // ВерсияБИП

        return Optional.of(new DetectedLibrary(
                "bip", "\u0411\u0418\u041F", //$NON-NLS-1$
                "\u0411\u0438\u0431\u043B\u0438\u043E\u0442\u0435\u043A\u0430 \u0438\u043D\u0442\u0435\u0440\u043D\u0435\u0442-\u043F\u043E\u0434\u0434\u0435\u0440\u0436\u043A\u0438", // Библиотека интернет-поддержки
                versionHint, Math.min(1.0, hits * 0.2 + (hasSub ? 0.3 : 0))));
    }

    // --- BED: Electronic Documents Library ---

    private static Optional<DetectedLibrary> detectBed(Configuration config) {
        Set<String> bedModules = Set.of(
                "\u042D\u043B\u0435\u043A\u0442\u0440\u043E\u043D\u043D\u044B\u0435\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u044B", // ЭлектронныеДокументы
                "\u042D\u043B\u0435\u043A\u0442\u0440\u043E\u043D\u043D\u044B\u0435\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u044B\u0421\u043B\u0443\u0436\u0435\u0431\u043D\u044B\u0439", // ЭлектронныеДокументыСлужебный
                "\u042D\u043B\u0435\u043A\u0442\u0440\u043E\u043D\u043D\u044B\u0439\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u043E\u0431\u043E\u0440\u043E\u0442\u0421\u041A\u043E\u043D\u0442\u0440\u0430\u0433\u0435\u043D\u0442\u0430\u043C\u0438" // ЭлектронныйДокументооборотСКонтрагентами
        );

        long hits = countModuleHits(config, bedModules);
        if (hits < 2) {
            return Optional.empty();
        }

        String versionHint = findConstantVersionHint(config,
                "\u0412\u0435\u0440\u0441\u0438\u044F\u0411\u042D\u0414"); // ВерсияБЭД
        if (versionHint == null) {
            versionHint = findConstantVersionHint(config,
                    "\u0412\u0435\u0440\u0441\u0438\u044F\u0411\u0438\u0431\u043B\u0438\u043E\u0442\u0435\u043A\u0438\u042D\u043B\u0435\u043A\u0442\u0440\u043E\u043D\u043D\u044B\u0445\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u0432"); // ВерсияБиблиотекиЭлектронныхДокументов
        }

        return Optional.of(new DetectedLibrary(
                "bed", "\u0411\u042D\u0414", //$NON-NLS-1$
                "\u0411\u0438\u0431\u043B\u0438\u043E\u0442\u0435\u043A\u0430 \u044D\u043B\u0435\u043A\u0442\u0440\u043E\u043D\u043D\u044B\u0445 \u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u0432", // Библиотека электронных документов
                versionHint, Math.min(1.0, hits * 0.25)));
    }

    // --- BPO: Peripheral Equipment Library ---

    private static Optional<DetectedLibrary> detectBpo(Configuration config) {
        Set<String> bpoModules = Set.of(
                "\u041F\u043E\u0434\u043A\u043B\u044E\u0447\u0430\u0435\u043C\u043E\u0435\u041E\u0431\u043E\u0440\u0443\u0434\u043E\u0432\u0430\u043D\u0438\u0435", // ПодключаемоеОборудование
                "\u041F\u043E\u0434\u043A\u043B\u044E\u0447\u0430\u0435\u043C\u043E\u0435\u041E\u0431\u043E\u0440\u0443\u0434\u043E\u0432\u0430\u043D\u0438\u0435\u0421\u0435\u0440\u0432\u0435\u0440", // ПодключаемоеОборудованиеСервер
                "\u041F\u043E\u0434\u043A\u043B\u044E\u0447\u0430\u0435\u043C\u043E\u0435\u041E\u0431\u043E\u0440\u0443\u0434\u043E\u0432\u0430\u043D\u0438\u0435\u041A\u043B\u0438\u0435\u043D\u0442" // ПодключаемоеОборудованиеКлиент
        );

        long hits = countModuleHits(config, bpoModules);
        if (hits < 2) {
            return Optional.empty();
        }

        String versionHint = findConstantVersionHint(config,
                "\u0412\u0435\u0440\u0441\u0438\u044F\u0411\u041F\u041E"); // ВерсияБПО

        return Optional.of(new DetectedLibrary(
                "bpo", "\u0411\u041F\u041E", //$NON-NLS-1$
                "\u0411\u0438\u0431\u043B\u0438\u043E\u0442\u0435\u043A\u0430 \u043F\u043E\u0434\u043A\u043B\u044E\u0447\u0430\u0435\u043C\u043E\u0433\u043E \u043E\u0431\u043E\u0440\u0443\u0434\u043E\u0432\u0430\u043D\u0438\u044F", // Библиотека подключаемого оборудования
                versionHint, Math.min(1.0, hits * 0.25)));
    }

    // --- BDO: Document Management Integration ---

    private static Optional<DetectedLibrary> detectBdo(Configuration config) {
        boolean hasModule = config.getCommonModules().stream()
                .anyMatch(m -> "\u0418\u043D\u0442\u0435\u0433\u0440\u0430\u0446\u0438\u044F\u0421\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u043E\u0431\u043E\u0440\u043E\u0442\u043E\u043C".equals(m.getName())); // ИнтеграцияСДокументооборотом

        boolean hasSub = hasSubsystemNamed(config,
                "\u0418\u043D\u0442\u0435\u0433\u0440\u0430\u0446\u0438\u044F\u0421\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u043E\u0431\u043E\u0440\u043E\u0442\u043E\u043C"); // ИнтеграцияСДокументооборотом

        if (!hasModule && !hasSub) {
            return Optional.empty();
        }

        String versionHint = findConstantVersionHint(config,
                "\u0412\u0435\u0440\u0441\u0438\u044F\u0411\u0414\u041E"); // ВерсияБДО

        return Optional.of(new DetectedLibrary(
                "bdo", "\u0411\u0414\u041E", //$NON-NLS-1$
                "\u0411\u0438\u0431\u043B\u0438\u043E\u0442\u0435\u043A\u0430 \u0438\u043D\u0442\u0435\u0433\u0440\u0430\u0446\u0438\u0438 \u0441 1\u0421:\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u043E\u0431\u043E\u0440\u043E\u0442\u043E\u043C", // Библиотека интеграции с 1С:Документооборотом
                versionHint, hasModule && hasSub ? 0.8 : 0.5));
    }

    // --- Helpers ---

    private static long countModuleHits(Configuration config, Set<String> moduleNames) {
        return config.getCommonModules().stream()
                .filter(m -> m != null && moduleNames.contains(m.getName()))
                .count();
    }

    private static boolean hasSubsystemNamed(Configuration config, String name) {
        return config.getSubsystems().stream()
                .anyMatch(s -> s != null && name.equals(s.getName()));
    }

    /**
     * Finds a constant by name and returns its synonym as a version hint.
     * We cannot read the actual constant value (it's in the infobase).
     */
    private static String findConstantVersionHint(Configuration config, String constantName) {
        for (Constant constant : config.getConstants()) {
            if (constant != null && constantName.equalsIgnoreCase(constant.getName())) {
                // Constant exists - library is present.
                // Try to extract version hint from synonym
                String synonym = extractSynonym(constant);
                if (synonym != null) {
                    String version = extractVersionNumber(synonym);
                    if (version != null) {
                        return version;
                    }
                }
                return null; // constant exists but no version hint
            }
        }
        return null;
    }

    private static String extractVersionFromSubsystemComment(Configuration config, String subsystemName) {
        for (Subsystem subsystem : config.getSubsystems()) {
            if (subsystem != null && subsystemName.equals(subsystem.getName())) {
                String comment = subsystem.getComment();
                if (comment != null && !comment.isBlank()) {
                    return extractVersionNumber(comment);
                }
            }
        }
        return null;
    }

    private static String extractSynonym(Constant constant) {
        try {
            var synonymMap = constant.getSynonym();
            if (synonymMap == null || synonymMap.isEmpty()) {
                return null;
            }
            // Try Russian first, then any available language
            String ru = synonymMap.get("ru"); //$NON-NLS-1$
            if (ru != null && !ru.isBlank()) {
                return ru;
            }
            for (var entry : synonymMap.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        } catch (Exception e) {
            // Ignore — synonym access can fail on partially loaded models
        }
        return null;
    }

    private static String extractVersionNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = VERSION_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
