package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Pure, headlessly-testable BSL handler-procedure-stub generator.
 *
 * <p>Turns a resolved {@code mcore.Event} + a validated handler name + the
 * configuration {@link ScriptVariant} into a BSL procedure stub value:
 * {@code {directive, signatureText, procedureText}}. Directive derivation
 * (STUB-02/STUB-04) and verbatim widest-{@link ParamSet} signature reproduction
 * (STUB-03/STUB-05) live here, entirely as a pure text transform with zero I/O,
 * EMF-transaction, or OSGi-service dependency — every branch is unit-testable
 * against {@code McoreFactory} fakes (see {@code BslHandlerStubGeneratorTest}).</p>
 *
 * <p>The directive is derived ONLY from {@link Event#isServerCallWithContextNotAllowed()}
 * and {@link Event#environments()} — never from the event name or any name-suffix
 * heuristic (STUB-02). This class MUST NOT import any workbench-UI-tier event/directive
 * type (core-independent-of-UI rule), and MUST NOT reference any extension
 * (Расширения) construct — that is Phase 8.</p>
 */
public class BslHandlerStubGenerator {

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
        Directive directive = resolveDirective(event);
        String directiveLiteral = directiveLiteral(directive, variant);
        String signatureText = buildParamList(event, variant);
        String procedureText = buildProcedureText(directiveLiteral, handlerName, signatureText, variant);
        return new StubText(directiveLiteral, signatureText, procedureText);
    }

    /**
     * Resolves the client/server directive per STUB-02/STUB-04.
     *
     * <p>Checks {@link Event#isServerCallWithContextNotAllowed()} FIRST — if {@code true},
     * the directive is unconditionally {@code AT_SERVER_NO_CONTEXT}, no {@link Environments}
     * inspection at all. Otherwise derives from {@link Event#environments()} (the convenience
     * accessor, mirroring {@code BslSemanticService}'s null-safety precedent exactly — falls
     * back to {@link Environments#ALL} when unset, never NPEs): server-capable and NOT
     * all-clients-capable yields {@code AT_SERVER}; every other case (client-only, mixed,
     * unset/ALL) yields {@code AT_CLIENT}.</p>
     *
     * <p>No event-name-suffix branch exists anywhere in this method — this is a hard
     * requirement of STUB-02.</p>
     */
    private Directive resolveDirective(Event event) {
        if (event.isServerCallWithContextNotAllowed()) {
            return Directive.AT_SERVER_NO_CONTEXT;
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
     * the event's widest {@link ParamSet}, reproducing parameter names verbatim per
     * {@link ScriptVariant}. Out-parameters ({@link Parameter#isOut()}) render as plain
     * parameter names (1C BSL has no explicit out/ref keyword in the procedure declaration
     * itself — the out behavior is a calling-convention contract, not BSL syntax).
     */
    private String buildParamList(Event event, ScriptVariant variant) {
        ParamSet widest = selectWidestParamSet(event);
        if (widest == null) {
            return ""; //$NON-NLS-1$
        }
        boolean useRussian = BslKeywords.isRussian(variant);
        List<String> names = new ArrayList<>();
        for (Parameter parameter : widest.getParams()) {
            String name = useRussian
                    ? firstNonBlank(parameter.getNameRu(), parameter.getName())
                    : firstNonBlank(parameter.getName(), parameter.getNameRu());
            names.add(name);
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /**
     * Selects the widest {@link ParamSet} by arity (largest {@link ParamSet#getMaxParams()}),
     * per STUB-05. Returns the sole entry if only one exists, or {@code null} if the event
     * declares no {@link ParamSet} at all.
     */
    private ParamSet selectWidestParamSet(Event event) {
        List<ParamSet> paramSets = event.getParamSet();
        if (paramSets == null || paramSets.isEmpty()) {
            return null;
        }
        return paramSets.stream()
                .max(Comparator.comparingInt(ParamSet::getMaxParams))
                .orElse(paramSets.get(0));
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
