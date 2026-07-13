package com.codepilot1c.core.edt.forms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator.StubText;

/**
 * Pure filesystem read-modify-write orchestrator for appending a generated BSL handler
 * stub into a form's {@code Module.bsl}, keyed strictly through an injectable
 * {@link ModuleFileWriter} seam — no BM-transaction or transactional-write dependency
 * whatsoever (rollback of the EMF model slot is 07-03's concern, not this class's).
 *
 * <p>Sequence: read current module text -&gt; scan for an existing procedure of the
 * target name (ScriptVariant-correct regex, STUB-01/Pitfall 5) -&gt; if found with a
 * non-empty body OR a different signature, leave the text byte-for-byte untouched and
 * return {@link StubWriteOutcome#SKIPPED_EXISTING_WARN} (a benign warn, never a rollback
 * trigger — Pitfall 3) -&gt; otherwise append the generated procedure text (blank-line
 * separated, with no forced-region wrapper around it) via the injected writer,
 * re-read to verify the handler name is actually present (STUB-06 verify-don't-trust),
 * and return {@link StubWriteOutcome#WRITTEN}. Any thrown failure from the injected
 * writer's {@code write(...)}, or a re-read that does not contain the handler name,
 * yields {@link StubWriteOutcome#WRITE_FAILURE} — the explicit both-or-neither signal
 * 07-03's caller switches on to trigger the compensating model-slot rollback.</p>
 */
public class BslHandlerStubWriter {

    private static final Pattern RU_PROCEDURE_PATTERN = Pattern.compile(
            "(?s)Процедура\\s+([\\p{L}0-9_]+)\\s*\\([^)]*\\).*?КонецПроцедуры"); //$NON-NLS-1$

    private static final Pattern EN_PROCEDURE_PATTERN = Pattern.compile(
            "(?s)Procedure\\s+([\\p{L}0-9_]+)\\s*\\([^)]*\\).*?EndProcedure"); //$NON-NLS-1$

    private final ModuleFileWriter writer;

    public BslHandlerStubWriter(ModuleFileWriter writer) {
        this.writer = writer;
    }

    /**
     * Performs the read -&gt; existing-procedure check -&gt; append -&gt; re-read-verify
     * sequence for the given module path and generated stub.
     *
     * @param modulePath the workspace-relative path to the form's {@code Module.bsl}
     * @param handlerName the target procedure name
     * @param stub the already-generated {@link StubText} (directive + signature + full
     *             procedure text) produced by {@link BslHandlerStubGenerator}
     * @param variant the configuration's {@link ScriptVariant}, selecting the RU vs EN
     *                existing-procedure detection pattern (Pitfall 5)
     * @return the {@link StubWriteOutcome} the caller (07-03) switches on
     */
    public StubWriteOutcome write(String modulePath, String handlerName, StubText stub, ScriptVariant variant) {
        String currentText;
        try {
            currentText = writer.read(modulePath);
        } catch (RuntimeException e) {
            return StubWriteOutcome.WRITE_FAILURE;
        }
        if (currentText == null) {
            currentText = ""; //$NON-NLS-1$
        }

        ExistingProcedureMatch existing = findExistingProcedure(currentText, handlerName, variant);
        if (existing != null) {
            // Present with a non-empty body, OR present with a different signature: leave
            // untouched, surface a warning (Pitfall 3 -- never a rollback trigger).
            // Present and matching exactly is also a benign idempotent no-op.
            return StubWriteOutcome.SKIPPED_EXISTING_WARN;
        }

        String nextText = currentText.isBlank()
                ? stub.procedureText()
                : currentText.stripTrailing() + "\n\n" + stub.procedureText() + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

        try {
            writer.write(modulePath, nextText);
        } catch (RuntimeException e) {
            return StubWriteOutcome.WRITE_FAILURE;
        }

        String reRead;
        try {
            reRead = writer.read(modulePath);
        } catch (RuntimeException e) {
            return StubWriteOutcome.WRITE_FAILURE;
        }
        if (reRead == null || !reRead.contains(handlerName)) {
            return StubWriteOutcome.WRITE_FAILURE;
        }
        return StubWriteOutcome.WRITTEN;
    }

    /**
     * Scans {@code moduleText} for a procedure declaration whose captured name equals
     * {@code handlerName}, using the ScriptVariant-correct pattern (RU {@code Процедура}
     * for non-{@link ScriptVariant#ENGLISH}, EN {@code Procedure} for
     * {@link ScriptVariant#ENGLISH} -- Pitfall 5). Matches only real procedure
     * declarations (the regex requires the {@code Процедура}/{@code Procedure} keyword
     * immediately before the name), so a handler name appearing only inside a comment or
     * string literal elsewhere in the module is correctly treated as absent.
     */
    private ExistingProcedureMatch findExistingProcedure(String moduleText, String handlerName, ScriptVariant variant) {
        Pattern pattern = BslKeywords.isRussian(variant) ? RU_PROCEDURE_PATTERN : EN_PROCEDURE_PATTERN;
        Matcher matcher = pattern.matcher(moduleText);
        while (matcher.find()) {
            String matchedName = matcher.group(1);
            if (handlerName.equals(matchedName)) {
                String fullMatch = matcher.group();
                return new ExistingProcedureMatch(fullMatch);
            }
        }
        return null;
    }

    /**
     * Holds the matched existing-procedure text for signature/body inspection. Kept
     * intentionally minimal: this plan only needs to know that a procedure of the target
     * name already exists (a same-name match is treated as "already handled, warn" per
     * Pitfall 3, regardless of whether the body/signature matches exactly or differs --
     * both are non-rollback-triggering outcomes).
     */
    private static final class ExistingProcedureMatch {
        private final String text;

        ExistingProcedureMatch(String text) {
            this.text = text;
        }
    }

    /**
     * The three possible outcomes of {@link #write(String, String, StubText, ScriptVariant)}.
     * 07-03's {@code EdtMetadataService} tail switches on this to decide success /
     * warn-summary / compensating rollback.
     */
    public enum StubWriteOutcome {
        /** The stub was appended and its presence was verified on re-read. */
        WRITTEN,
        /**
         * A procedure with the target name already existed (non-empty body, different
         * signature, or an exact idempotent match) -- left untouched, a benign warning,
         * NOT a failure and NOT a rollback trigger.
         */
        SKIPPED_EXISTING_WARN,
        /**
         * The injected writer's {@code write(...)} threw, or the post-write re-read did
         * not contain the handler name -- the both-or-neither signal 07-03 uses to trigger
         * the compensating model-slot rollback.
         */
        WRITE_FAILURE
    }
}
