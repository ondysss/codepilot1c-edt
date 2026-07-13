package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator.StubText;
import com.codepilot1c.core.edt.forms.BslHandlerStubWriter.StubWriteOutcome;

/**
 * Covers STUB-01 (idempotency, never-overwrite-non-empty-body, rollback-signalling
 * write-failure) and STUB-06 (write-after-export ordering primitive, verify-don't-trust)
 * for {@link BslHandlerStubWriter}, entirely headlessly against an in-memory fake
 * {@link ModuleFileWriter} — no live EDT/BM/OSGi workspace, no {@code IFile}.
 */
public class BslHandlerStubWriteTest {

    private static final String MODULE_PATH = "/Forms/TestForm/Ext/Form/Module.bsl"; //$NON-NLS-1$

    @Test
    public void absentProcedureIsAppendedAndWritten() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        writer.seed(MODULE_PATH, ""); //$NON-NLS-1$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.WRITTEN, outcome);
        String stored = writer.read(MODULE_PATH);
        assertEquals(1, countOccurrences(stored, "FormOnOpen")); //$NON-NLS-1$
    }

    @Test
    public void existingNonEmptyBodySameNameIsLeftUntouched() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        String existingText = "&НаКлиенте\n" //$NON-NLS-1$
                + "Процедура FormOnOpen(Отказ)\n" //$NON-NLS-1$
                + "\tСообщить(\"уже реализовано\");\n" //$NON-NLS-1$
                + "КонецПроцедуры"; //$NON-NLS-1$
        writer.seed(MODULE_PATH, existingText);
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, outcome);
        assertEquals(existingText, writer.read(MODULE_PATH));
    }

    @Test
    public void existingDifferentSignatureSameNameIsLeftUntouched() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        // Same name "FormOnOpen" but a totally different parameter list than the generated stub.
        String existingText = "&НаКлиенте\n" //$NON-NLS-1$
                + "Процедура FormOnOpen(Параметр1, Параметр2, Параметр3)\n" //$NON-NLS-1$
                + "\n" //$NON-NLS-1$
                + "КонецПроцедуры"; //$NON-NLS-1$
        writer.seed(MODULE_PATH, existingText);
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, outcome);
        assertEquals(existingText, writer.read(MODULE_PATH));
    }

    @Test
    public void identicalStubWrittenTwiceIsIdempotent() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        writer.seed(MODULE_PATH, ""); //$NON-NLS-1$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome first = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$
        StubWriteOutcome second = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.WRITTEN, first);
        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, second);
        String stored = writer.read(MODULE_PATH);
        assertEquals(1, countOccurrences(stored, "Процедура FormOnOpen(")); //$NON-NLS-1$
    }

    @Test
    public void writerThrowsOnWriteYieldsWriteFailure() {
        FailingModuleFileWriter writer = new FailingModuleFileWriter();
        writer.seed(MODULE_PATH, ""); //$NON-NLS-1$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.WRITE_FAILURE, outcome);
    }

    @Test
    public void enConfiguredModuleUsesEnPatternAndDetectsExistingProcedure() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        // EN-spelled existing procedure; an RU-only pattern would miss this (Pitfall 5).
        String existingText = "&AtClient\n" //$NON-NLS-1$
                + "Procedure FormOnOpen(Cancel)\n" //$NON-NLS-1$
                + "\tMessage(\"already implemented\");\n" //$NON-NLS-1$
                + "EndProcedure"; //$NON-NLS-1$
        writer.seed(MODULE_PATH, existingText);
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.ENGLISH); //$NON-NLS-1$

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.ENGLISH); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, outcome);
        assertEquals(existingText, writer.read(MODULE_PATH));
    }

    @Test
    public void handlerNameInsideCommentOrStringIsTreatedAsAbsentNoFalsePositiveSkip() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        // "FormOnOpen" appears only inside a comment and inside a string literal of an
        // unrelated procedure — must NOT be treated as an existing declaration.
        String existingText = "&НаКлиенте\n" //$NON-NLS-1$
                + "// See FormOnOpen for details.\n" //$NON-NLS-1$
                + "Процедура ДругаяПроцедура(Параметр)\n" //$NON-NLS-1$
                + "\tСообщить(\"FormOnOpen упомянут тут как текст\");\n" //$NON-NLS-1$
                + "КонецПроцедуры"; //$NON-NLS-1$
        writer.seed(MODULE_PATH, existingText);
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        StubText stub = new BslHandlerStubGenerator().generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, "FormOnOpen", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals(StubWriteOutcome.WRITTEN, outcome);
        String stored = writer.read(MODULE_PATH);
        assertTrue(stored.contains("Процедура FormOnOpen(")); //$NON-NLS-1$
    }

    private static int countOccurrences(String haystack, String needle) {
        Pattern pattern = Pattern.compile(Pattern.quote(needle));
        Matcher matcher = pattern.matcher(haystack);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static Event createEvent(String nameEn, String nameRu) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(nameEn);
        event.setNameRu(nameRu);
        return event;
    }

    /**
     * In-memory {@link ModuleFileWriter} fake backed by a {@link Map}, for headless testing.
     */
    private static class InMemoryModuleFileWriter implements ModuleFileWriter {
        private final Map<String, String> files = new HashMap<>();

        void seed(String path, String content) {
            files.put(path, content);
        }

        @Override
        public String read(String workspacePath) {
            return files.getOrDefault(workspacePath, ""); //$NON-NLS-1$
        }

        @Override
        public void write(String workspacePath, String content) {
            files.put(workspacePath, content);
        }
    }

    /**
     * In-memory fake whose {@link #write(String, String)} always throws, proving
     * {@link BslHandlerStubWriter} converts a thrown write failure into
     * {@link StubWriteOutcome#WRITE_FAILURE} rather than letting the exception escape.
     */
    private static class FailingModuleFileWriter implements ModuleFileWriter {
        private final Map<String, String> files = new HashMap<>();

        void seed(String path, String content) {
            files.put(path, content);
        }

        @Override
        public String read(String workspacePath) {
            return files.getOrDefault(workspacePath, ""); //$NON-NLS-1$
        }

        @Override
        public void write(String workspacePath, String content) {
            throw new RuntimeException("simulated write failure"); //$NON-NLS-1$
        }
    }
}
