package com.codepilot1c.core.edt.forms;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Sanctioned hand-rolled RU/EN literal map for {@link BslHandlerStubGenerator}.
 *
 * <p>This is the ONLY hand-rolled-BSL-text surface in the codebase, per the project's
 * documented "stub-body text is the sanctioned hand-roll exception" carve-out (see
 * {@code 07-RESEARCH.md} Pattern 3 / Don't Hand-Roll table). Every literal here is a
 * named constant so this surface stays small, auditable, and greppable in one file.</p>
 *
 * <p>Selection is keyed by {@link ScriptVariant}: {@code russian == true} unless the
 * configuration's script variant is explicitly {@link ScriptVariant#ENGLISH}.</p>
 */
final class BslKeywords {

    private static final String PROCEDURE_RU = "Процедура"; //$NON-NLS-1$
    private static final String PROCEDURE_EN = "Procedure"; //$NON-NLS-1$

    private static final String END_PROCEDURE_RU = "КонецПроцедуры"; //$NON-NLS-1$
    private static final String END_PROCEDURE_EN = "EndProcedure"; //$NON-NLS-1$

    private static final String DIRECTIVE_AT_CLIENT_RU = "&НаКлиенте"; //$NON-NLS-1$
    private static final String DIRECTIVE_AT_CLIENT_EN = "&AtClient"; //$NON-NLS-1$

    private static final String DIRECTIVE_AT_SERVER_RU = "&НаСервере"; //$NON-NLS-1$
    private static final String DIRECTIVE_AT_SERVER_EN = "&AtServer"; //$NON-NLS-1$

    private static final String DIRECTIVE_AT_SERVER_NO_CONTEXT_RU = "&НаСервереБезКонтекста"; //$NON-NLS-1$
    private static final String DIRECTIVE_AT_SERVER_NO_CONTEXT_EN = "&AtServerNoContext"; //$NON-NLS-1$

    private static final String HANDLER_BODY_COMMENT_RU = "// Вставить содержимое обработчика."; //$NON-NLS-1$
    private static final String HANDLER_BODY_COMMENT_EN = "// Insert handler content."; //$NON-NLS-1$

    private BslKeywords() {
        // constants only
    }

    static boolean isRussian(ScriptVariant variant) {
        return variant != ScriptVariant.ENGLISH;
    }

    static String procedureKeyword(ScriptVariant variant) {
        return isRussian(variant) ? PROCEDURE_RU : PROCEDURE_EN;
    }

    static String endProcedureKeyword(ScriptVariant variant) {
        return isRussian(variant) ? END_PROCEDURE_RU : END_PROCEDURE_EN;
    }

    static String directiveAtClient(ScriptVariant variant) {
        return isRussian(variant) ? DIRECTIVE_AT_CLIENT_RU : DIRECTIVE_AT_CLIENT_EN;
    }

    static String directiveAtServer(ScriptVariant variant) {
        return isRussian(variant) ? DIRECTIVE_AT_SERVER_RU : DIRECTIVE_AT_SERVER_EN;
    }

    static String directiveAtServerNoContext(ScriptVariant variant) {
        return isRussian(variant) ? DIRECTIVE_AT_SERVER_NO_CONTEXT_RU : DIRECTIVE_AT_SERVER_NO_CONTEXT_EN;
    }

    static String handlerBodyComment(ScriptVariant variant) {
        return isRussian(variant) ? HANDLER_BODY_COMMENT_RU : HANDLER_BODY_COMMENT_EN;
    }
}
