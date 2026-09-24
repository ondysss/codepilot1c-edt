package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.List;

import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Pure, headlessly-testable BSL handler-procedure-stub generator.
 *
 * <p>Turns a resolved {@code mcore.Event} + a validated handler name + the
 * configuration {@link ScriptVariant} into a BSL procedure stub value:
 * {@code {directive, signatureText, procedureText}}. Directive derivation
 * (STUB-02/STUB-04) and EDT-compatible first-{@link ParamSet} signature reproduction
 * (STUB-03/STUB-05) live here, entirely as a pure text transform with zero I/O,
 * EMF-transaction, or OSGi-service dependency — every branch is unit-testable
 * against {@code McoreFactory} fakes (see {@code BslHandlerStubGeneratorTest}).</p>
 *
 * <p>There are two supported stub kinds. {@link HandlerStubKind#EVENT_HANDLER} uses the
 * resolved {@link Event} contract described below. {@link HandlerStubKind#COMMAND_ACTION}
 * has no {@code Event}; a managed-form command is always a client procedure with the
 * platform-defined command parameter, so its directive and signature are explicit.</p>
 *
 * <p>The directive follows the rule of EDT's own form editor
 * ({@code GotoEventHandlerHandler.getProceduresParameters}, EDT 2025.2.3): an event whose
 * name ends with {@code AtServer}, and {@code OnComposeResult}/{@code AfterComposeResult},
 * get {@code &AtServer} — except {@code OnGetDataAtServer} of a dynamic-list table, which
 * gets {@code &AtServerNoContext}; every other event is a client event.</p>
 *
 * <p>STUB-02 originally forbade the name and derived the directive from
 * {@link Event#environments()} alone. The EDT event model does not mark server form
 * events as server-only, so {@code OnCreateAtServer} and {@code OnReadAtServer} came out
 * as {@code &AtClient} stubs (measured 2026-09-14): EDT diagnostics stay silent on such a
 * module and the form fails only when it is opened. {@link Event#isServerCallWithContextNotAllowed()}
 * and server-only environments are still honoured for events the name rule does not
 * cover. This class MUST NOT import any workbench-UI-tier event/directive type
 * (core-independent-of-UI rule), and MUST NOT reference any extension (Расширения)
 * construct — that is Phase 8.</p>
 */
public class BslHandlerStubGenerator {

    private static final String ON_GET_DATA_AT_SERVER = "OnGetDataAtServer"; //$NON-NLS-1$
    private static final String AT_SERVER_SUFFIX = "AtServer"; //$NON-NLS-1$
    /** Server events whose names do not carry the {@code AtServer} suffix. */
    private static final java.util.Set<String> SERVER_EVENTS_WITHOUT_SUFFIX =
            java.util.Set.of("OnComposeResult", "AfterComposeResult"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final String DYNAMIC_LIST_TABLE_EXTENSION_TYPE =
            "FormTableExtensionForDynamicList"; //$NON-NLS-1$

    /** Context used by EDT when it supplies the implicit first handler parameter. */
    public enum TargetContext {
        FORM,
        VISUAL_ITEM,
        COMMAND
    }

    public BslHandlerStubGenerator() {
        // pure, stateless
    }

    /**
     * Generates the BSL procedure stub for the given resolved event.
     *
     * @param event the already-resolved, platform-native {@code mcore.Event} (single source
     *              of truth for directive and signature)
     * @param handlerName the already-validated handler procedure name (validated upstream by
     *              {@code MetadataNameValidator.isValidName} at wire time; this generator does
     *              not re-validate)
     * @param variant the configuration's {@link ScriptVariant} (RU vs EN keyword/name rendering)
     * @return the assembled {@link StubText} value object
     */
    public StubText generate(Event event, String handlerName, ScriptVariant variant) {
        return generate(event, handlerName, variant, TargetContext.FORM);
    }

    /**
     * Generates a stub with the same implicit target parameter rules as EDT's
     * {@code BslModuleEventsLookup}.
     *
     * @param targetContext whether the event belongs to the form, a visual item, or a command
     */
    public StubText generate(Event event, String handlerName, ScriptVariant variant, TargetContext targetContext) {
        Directive directive = resolveDirective(event);
        String directiveLiteral = directiveLiteral(directive, variant);
        String signatureText = buildParamList(event, variant, targetContext);
        String procedureText = buildProcedureText(directiveLiteral, handlerName, signatureText, variant);
        return new StubText(directiveLiteral, signatureText, procedureText);
    }

    /**
     * Generates the BSL stub for a form-command action handler
     * ({@link HandlerStubKind#COMMAND_ACTION}).
     *
     * <p>The 1C platform invokes a managed-form command handler on the client and passes
     * one command object. Omitting that parameter causes a too-many-actual-parameters
     * runtime error. EDT's {@code CommandHandler} exposes neither an {@link Event} nor
     * environments or parameter sets, so this contract must not use event directive
     * inference. Stored-data modification does not change the directive; server work is
     * invoked from the procedure body.</p>
     *
     * @param handlerName the already-validated handler procedure name
     * @param variant the configuration's {@link ScriptVariant}
     * @return the assembled client-side command-action stub
     */
    public StubText generateCommandAction(String handlerName, ScriptVariant variant) {
        String directiveLiteral = BslKeywords.directiveAtClient(variant);
        String signatureText = BslKeywords.commandParameter(variant);
        String procedureText = buildProcedureText(directiveLiteral, handlerName, signatureText, variant);
        return new StubText(directiveLiteral, signatureText, procedureText);
    }

    /**
     * Resolves the client/server directive.
     *
     * <p>Order: {@link Event#isServerCallWithContextNotAllowed()} or a dynamic-list
     * {@code OnGetDataAtServer} yields {@code AT_SERVER_NO_CONTEXT}; a server event by EDT's
     * name rule yields {@code AT_SERVER}; otherwise {@link Event#environments()} decides
     * (falls back to {@link Environments#ALL} when unset, never NPEs): server-capable and NOT
     * all-clients-capable yields {@code AT_SERVER}, every other case {@code AT_CLIENT}.</p>
     */
    private Directive resolveDirective(Event event) {
        if (event.isServerCallWithContextNotAllowed() || isOnGetDataAtServer(event)) {
            return Directive.AT_SERVER_NO_CONTEXT;
        }
        if (isServerEventByName(event)) {
            return Directive.AT_SERVER;
        }
        Environments envs = event.environments();
        if (envs == null || envs.isEmpty()) {
            envs = Environments.ALL;
        }
        boolean serverCapable = envs.containsAny(Environments.SERVER);
        boolean clientCapable = envs.containsAny(Environments.ALL_CLIENTS);
        if (serverCapable && !clientCapable) {
            return Directive.AT_SERVER;
        }
        return Directive.AT_CLIENT;
    }

    private String directiveLiteral(Directive directive, ScriptVariant variant) {
        switch (directive) {
            case AT_SERVER_NO_CONTEXT:
                return BslKeywords.directiveAtServerNoContext(variant);
            case AT_SERVER:
                return BslKeywords.directiveAtServer(variant);
            case AT_CLIENT:
            default:
                return BslKeywords.directiveAtClient(variant);
        }
    }

    /**
     * Builds the BSL parameter list text (e.g. {@code "Элемент, СтандартнаяОбработка"}) from
     * the event's first {@link ParamSet}, reproducing parameter names verbatim per
     * {@link ScriptVariant}. Out-parameters ({@link Parameter#isOut()}) render as plain
     * parameter names (1C BSL has no explicit out/ref keyword in the procedure declaration
     * itself — the out behavior is a calling-convention contract, not BSL syntax).
     */
    private String buildParamList(Event event, ScriptVariant variant, TargetContext targetContext) {
        boolean useRussian = BslKeywords.isRussian(variant);
        List<String> names = new ArrayList<>();
        if (targetContext == TargetContext.COMMAND) {
            names.add(useRussian ? "Команда" : "Command"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (targetContext == TargetContext.VISUAL_ITEM && !isOnGetDataAtServer(event)) {
            names.add(useRussian ? "Элемент" : "Item"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ParamSet first = selectFirstParamSet(event);
        if (first == null) {
            return String.join(", ", names); //$NON-NLS-1$
        }
        for (Parameter parameter : first.getParams()) {
            String name = useRussian
                    ? firstNonBlank(parameter.getNameRu(), parameter.getName())
                    : firstNonBlank(parameter.getName(), parameter.getNameRu());
            names.add(name);
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /**
     * EDT's {@code BslModuleEventsLookup} always uses the first declared parameter set.
     */
    private ParamSet selectFirstParamSet(Event event) {
        List<ParamSet> paramSets = event.getParamSet();
        if (paramSets == null || paramSets.isEmpty()) {
            return null;
        }
        return paramSets.get(0);
    }

    /** EDT's form-editor rule: {@code *AtServer}, {@code OnComposeResult}, {@code AfterComposeResult}. */
    private boolean isServerEventByName(Event event) {
        String name = event == null ? null : event.getName();
        return name != null && (name.endsWith(AT_SERVER_SUFFIX) || SERVER_EVENTS_WITHOUT_SUFFIX.contains(name));
    }

    private boolean isOnGetDataAtServer(Event event) {
        return event != null
                && ON_GET_DATA_AT_SERVER.equals(event.getName())
                && event.eContainer() instanceof TypeItem typeItem
                && DYNAMIC_LIST_TABLE_EXTENSION_TYPE.equals(McoreUtil.getTypeName(typeItem));
    }

    private String buildProcedureText(String directiveLiteral, String handlerName, String signatureText,
            ScriptVariant variant) {
        String procedureKeyword = BslKeywords.procedureKeyword(variant);
        String endProcedureKeyword = BslKeywords.endProcedureKeyword(variant);
        String bodyComment = BslKeywords.handlerBodyComment(variant);

        StringBuilder text = new StringBuilder();
        text.append(directiveLiteral).append('\n');
        text.append(procedureKeyword).append(' ').append(handlerName).append('(').append(signatureText).append(")\n"); //$NON-NLS-1$
        text.append('\t').append(bodyComment).append('\n');
        text.append(endProcedureKeyword);
        return text.toString();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback != null ? fallback : ""; //$NON-NLS-1$
    }

    /**
     * Directive kind — derivation source is exclusively {@link Event#isServerCallWithContextNotAllowed()}
     * and {@link Event#environments()}, never the event name.
     */
    private enum Directive {
        AT_CLIENT,
        AT_SERVER,
        AT_SERVER_NO_CONTEXT
    }

    /**
     * Immutable value object holding the generated stub's three text facets: the directive
     * literal, the bare parameter-list signature text, and the fully-assembled procedure text
     * (directive line + {@code Процедура ...} header + native body comment + {@code КонецПроцедуры}).
     * This is the single public entry consumed by the write orchestration (07-03).
     */
    public static final class StubText {

        private final String directive;
        private final String signatureText;
        private final String procedureText;

        StubText(String directive, String signatureText, String procedureText) {
            this.directive = directive;
            this.signatureText = signatureText;
            this.procedureText = procedureText;
        }

        public String directive() {
            return directive;
        }

        public String signatureText() {
            return signatureText;
        }

        public String procedureText() {
            return procedureText;
        }
    }
}
