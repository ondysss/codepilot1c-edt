package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Covers STUB-02/STUB-03/STUB-04/STUB-05: {@link BslHandlerStubGenerator}'s directive
 * derivation, verbatim widest-{@link ParamSet} signature reproduction, and BSL text
 * assembly.
 *
 * <p>Runs entirely against fake {@link Event}/{@link ParamSet}/{@link Parameter}
 * instances built via {@link McoreFactory}, mirroring the McoreFactory fixture
 * convention already established by {@code EventHandlerWiringTest#createEvent}
 * ({@code com.codepilot1c.core.edt.metadata} package) — no live EDT/BM/OSGi
 * dependency, no IFile, no EMF-transaction.</p>
 */
public class BslHandlerStubGeneratorTest {

    private final BslHandlerStubGenerator generator = new BslHandlerStubGenerator();

    @Test
    public void resolveDirectiveServerCallWithContextNotAllowedWinsOverEnvironments() {
        Event event = createEvent("OnGetDataAtServer", "ПриПолученииДанныхНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(true);
        event.setEnvironments(Environments.ALL_CLIENTS); // must be ignored once the flag is true

        BslHandlerStubGenerator.StubText stub = generator.generate(event, "Handler", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertTrue(stub.directive().contains("НаСервереБезКонтекста")); //$NON-NLS-1$
    }

    @Test
    public void resolveDirectiveServerOnlyEnvironmentsYieldsAtServer() {
        Event event = createEvent("OnWrite", "ПриЗаписи"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(false);
        event.setEnvironments(Environments.SERVER);

        BslHandlerStubGenerator.StubText stub = generator.generate(event, "Handler", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals("&НаСервере", stub.directive()); //$NON-NLS-1$
    }

    @Test
    public void resolveDirectiveAllClientsEnvironmentsYieldsAtClient() {
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(false);
        event.setEnvironments(Environments.ALL_CLIENTS);

        BslHandlerStubGenerator.StubText stub = generator.generate(event, "Handler", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals("&НаКлиенте", stub.directive()); //$NON-NLS-1$
    }

    @Test
    public void resolveDirectiveUnsetEnvironmentsFallsBackToAtClientNeverNpe() {
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(false);
        // environments left unset/null deliberately

        BslHandlerStubGenerator.StubText stub = generator.generate(event, "Handler", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        assertEquals("&НаКлиенте", stub.directive()); //$NON-NLS-1$
    }

    @Test
    public void directiveLiteralRendersRuAndEnSpellingsForAtServerNoContext() {
        Event event = createEvent("OnGetDataAtServer", "ПриПолученииДанныхНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(true);

        BslHandlerStubGenerator.StubText ru = generator.generate(event, "Handler", ScriptVariant.RUSSIAN); //$NON-NLS-1$
        BslHandlerStubGenerator.StubText en = generator.generate(event, "Handler", ScriptVariant.ENGLISH); //$NON-NLS-1$

        assertEquals("&НаСервереБезКонтекста", ru.directive()); //$NON-NLS-1$
        assertEquals("&AtServerNoContext", en.directive()); //$NON-NLS-1$
    }

    @Test
    public void signatureReproducesParameterNamesIncludingOutParamInBothScriptVariants() {
        Event event = createEvent("OnGetDataAtServer", "ПриПолученииДанныхНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.SERVER);
        ParamSet paramSet = createParamSet(
                createParameter("Item", "Элемент", false), //$NON-NLS-1$ //$NON-NLS-2$
                createParameter("StandardProcessing", "СтандартнаяОбработка", true)); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(paramSet);

        BslHandlerStubGenerator.StubText ru = generator.generate(event, "Handler", ScriptVariant.RUSSIAN); //$NON-NLS-1$
        BslHandlerStubGenerator.StubText en = generator.generate(event, "Handler", ScriptVariant.ENGLISH); //$NON-NLS-1$

        assertTrue(ru.procedureText().contains("Элемент")); //$NON-NLS-1$
        assertTrue(ru.procedureText().contains("СтандартнаяОбработка")); //$NON-NLS-1$
        assertTrue(en.procedureText().contains("Item")); //$NON-NLS-1$
        assertTrue(en.procedureText().contains("StandardProcessing")); //$NON-NLS-1$
    }

    @Test
    public void widestParamSetByMaxParamsIsSelectedWhenMultipleParamSetsExist() {
        Event event = createEvent("OnChange", "ПриИзменении"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);
        ParamSet narrow = createParamSet(createParameter("A", "А", false)); //$NON-NLS-1$ //$NON-NLS-2$
        ParamSet wide = createParamSet(
                createParameter("A", "А", false), //$NON-NLS-1$ //$NON-NLS-2$
                createParameter("B", "Б", false), //$NON-NLS-1$ //$NON-NLS-2$
                createParameter("C", "В", false)); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(narrow);
        event.getParamSet().add(wide);

        BslHandlerStubGenerator.StubText stub = generator.generate(event, "Handler", ScriptVariant.ENGLISH); //$NON-NLS-1$

        assertTrue(stub.procedureText().contains("A")); //$NON-NLS-1$
        assertTrue(stub.procedureText().contains("B")); //$NON-NLS-1$
        assertTrue(stub.procedureText().contains("C")); //$NON-NLS-1$
    }

    @Test
    public void procedureTextAssemblesDirectiveSignatureCommentAndEndProcedureWithoutRegion() {
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setEnvironments(Environments.ALL_CLIENTS);

        BslHandlerStubGenerator.StubText stub = generator.generate(event, "FormOnOpen", ScriptVariant.RUSSIAN); //$NON-NLS-1$
        String text = stub.procedureText();

        String[] lines = text.split("\n", -1); //$NON-NLS-1$
        assertEquals("&НаКлиенте", lines[0].trim()); //$NON-NLS-1$
        assertTrue(lines[1].contains("Процедура FormOnOpen(")); //$NON-NLS-1$
        assertTrue(text.contains("// Вставить содержимое обработчика.")); //$NON-NLS-1$
        assertTrue(text.contains("КонецПроцедуры")); //$NON-NLS-1$
        assertFalse(text.contains("#Область")); //$NON-NLS-1$
        assertFalse(text.contains("#Region")); //$NON-NLS-1$
    }

    private static Event createEvent(String nameEn, String nameRu) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(nameEn);
        event.setNameRu(nameRu);
        return event;
    }

    private static ParamSet createParamSet(Parameter... parameters) {
        ParamSet paramSet = McoreFactory.eINSTANCE.createParamSet();
        for (Parameter parameter : parameters) {
            paramSet.getParams().add(parameter);
        }
        paramSet.setMinParams(0);
        paramSet.setMaxParams(parameters.length);
        return paramSet;
    }

    private static Parameter createParameter(String nameEn, String nameRu, boolean out) {
        Parameter parameter = McoreFactory.eINSTANCE.createParameter();
        parameter.setName(nameEn);
        parameter.setNameRu(nameRu);
        parameter.setOut(out);
        return parameter;
    }
}
