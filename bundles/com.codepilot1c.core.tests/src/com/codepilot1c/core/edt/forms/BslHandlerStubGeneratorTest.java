package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Covers STUB-02/STUB-03/STUB-04/STUB-05: {@link BslHandlerStubGenerator}'s directive
 * derivation, EDT-compatible first-{@link ParamSet} signature reproduction, and BSL text
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

    /**
     * 2026-09-14: {@code add_event_handler} and form creation wrote {@code ПриСозданииНаСервере}
     * and {@code ПриЧтенииНаСервере} under {@code &НаКлиенте}. EDT diagnostics stay silent on such a
     * module, and the form fails only when it is opened. The EDT event model does not mark server
     * form events as server-only, so the environments cannot decide; EDT's own form editor
     * ({@code GotoEventHandlerHandler.getProceduresParameters}) decides by the event name.
     */
    @Test
    public void serverFormEventsGetAtServerWhenEnvironmentsAreUnset() {
        for (String[] names : new String[][] {
                {"OnCreateAtServer", "ПриСозданииНаСервере"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"OnReadAtServer", "ПриЧтенииНаСервере"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"BeforeWriteAtServer", "ПередЗаписьюНаСервере"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"OnWriteAtServer", "ПриЗаписиНаСервере"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"AfterWriteAtServer", "ПослеЗаписиНаСервере"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"FillCheckProcessingAtServer", "ОбработкаПроверкиЗаполненияНаСервере"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"OnLoadDataFromSettingsAtServer", "ПриЗагрузкеДанныхИзНастроекНаСервере"}}) { //$NON-NLS-1$ //$NON-NLS-2$
            Event event = createEvent(names[0], names[1]);
            event.setServerCallWithContextNotAllowed(false);
            // environments left unset: exactly the state in which the old derivation fell back to client

            BslHandlerStubGenerator.StubText stub = generator.generate(event, names[1], ScriptVariant.RUSSIAN);

            assertEquals(names[0], "&НаСервере", stub.directive()); //$NON-NLS-1$
        }
    }

    @Test
    public void serverFormEventGetsAtServerEvenWhenEnvironmentsIncludeClients() {
        Event event = createEvent("OnCreateAtServer", "ПриСозданииНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(false);
        event.setEnvironments(Environments.ALL);
        event.getParamSet().add(createParamSet(
                createParameter("Cancel", "Отказ", true), //$NON-NLS-1$ //$NON-NLS-2$
                createParameter("StandardProcessing", "СтандартнаяОбработка", true))); //$NON-NLS-1$ //$NON-NLS-2$

        BslHandlerStubGenerator.StubText ru = generator.generate(event, "ПриСозданииНаСервере", ScriptVariant.RUSSIAN); //$NON-NLS-1$
        BslHandlerStubGenerator.StubText en = generator.generate(event, "OnCreateAtServer", ScriptVariant.ENGLISH); //$NON-NLS-1$

        assertEquals("&НаСервере", ru.procedureText().split("\n", -1)[0]); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ru.procedureText().contains("Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)")); //$NON-NLS-1$
        assertEquals("&AtServer", en.directive()); //$NON-NLS-1$
    }

    @Test
    public void composeResultEventsGetAtServer() {
        for (String[] names : new String[][] {
                {"OnComposeResult", "ПриКомпоновкеРезультата"}, //$NON-NLS-1$ //$NON-NLS-2$
                {"AfterComposeResult", "ПослеКомпоновкиРезультата"}}) { //$NON-NLS-1$ //$NON-NLS-2$
            Event event = createEvent(names[0], names[1]);

            assertEquals(names[0], "&НаСервере", //$NON-NLS-1$
                    generator.generate(event, names[1], ScriptVariant.RUSSIAN).directive());
        }
    }

    @Test
    public void dynamicListOnGetDataAtServerGetsNoContextEvenWithoutTheFlag() {
        Type dynamicListTableExtension = McoreFactory.eINSTANCE.createType();
        dynamicListTableExtension.setName("FormTableExtensionForDynamicList"); //$NON-NLS-1$
        Event event = createEvent("OnGetDataAtServer", "ПриПолученииДанныхНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.setServerCallWithContextNotAllowed(false);
        dynamicListTableExtension.getEvents().add(event);

        BslHandlerStubGenerator.StubText stub = generator.generate(
                event, "СписокПриПолученииДанныхНаСервере", ScriptVariant.RUSSIAN, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.VISUAL_ITEM);

        assertEquals("&НаСервереБезКонтекста", stub.directive()); //$NON-NLS-1$
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
    public void firstParamSetIsSelectedWhenMultipleParamSetsExist() {
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

        assertEquals("A", stub.signatureText()); //$NON-NLS-1$
    }

    @Test
    public void visualItemPrependsImplicitItemParameter() {
        Event event = createEvent("StartChoice", "НачалоВыбора"); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(createParamSet(
                createParameter("ChoiceData", "ДанныеВыбора", false), //$NON-NLS-1$ //$NON-NLS-2$
                createParameter("StandardProcessing", "СтандартнаяОбработка", true))); //$NON-NLS-1$ //$NON-NLS-2$

        BslHandlerStubGenerator.StubText ru = generator.generate(
                event, "Handler", ScriptVariant.RUSSIAN, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.VISUAL_ITEM);
        BslHandlerStubGenerator.StubText en = generator.generate(
                event, "Handler", ScriptVariant.ENGLISH, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.VISUAL_ITEM);

        assertEquals("Элемент, ДанныеВыбора, СтандартнаяОбработка", ru.signatureText()); //$NON-NLS-1$
        assertEquals("Item, ChoiceData, StandardProcessing", en.signatureText()); //$NON-NLS-1$
    }

    @Test
    public void formEventDoesNotPrependImplicitItemParameter() {
        Event event = createEvent("OnOpen", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(createParamSet(createParameter(
                "Cancel", "Отказ", true))); //$NON-NLS-1$ //$NON-NLS-2$

        BslHandlerStubGenerator.StubText stub = generator.generate(
                event, "Handler", ScriptVariant.RUSSIAN, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.FORM);

        assertEquals("Отказ", stub.signatureText()); //$NON-NLS-1$
    }

    @Test
    public void ordinaryItemEventNamedOnGetDataAtServerStillPrependsItemParameter() {
        Event event = createEvent("OnGetDataAtServer", "ПриПолученииДанныхНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(createParamSet(createParameter(
                "Data", "Данные", false))); //$NON-NLS-1$ //$NON-NLS-2$

        BslHandlerStubGenerator.StubText stub = generator.generate(
                event, "Handler", ScriptVariant.RUSSIAN, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.VISUAL_ITEM);

        assertEquals("Элемент, Данные", stub.signatureText()); //$NON-NLS-1$
    }

    @Test
    public void dynamicListTableOnGetDataAtServerDoesNotPrependItemParameter() {
        Type dynamicListTableExtension = McoreFactory.eINSTANCE.createType();
        dynamicListTableExtension.setName("FormTableExtensionForDynamicList"); //$NON-NLS-1$
        Event event = createEvent("OnGetDataAtServer", "ПриПолученииДанныхНаСервере"); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(createParamSet(createParameter(
                "Data", "Данные", false))); //$NON-NLS-1$ //$NON-NLS-2$
        dynamicListTableExtension.getEvents().add(event);

        BslHandlerStubGenerator.StubText stub = generator.generate(
                event, "Handler", ScriptVariant.RUSSIAN, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.VISUAL_ITEM);

        assertEquals("Данные", stub.signatureText()); //$NON-NLS-1$
    }

    @Test
    public void commandContextPrependsImplicitCommandParameter() {
        Event event = createEvent("Action", "Действие"); //$NON-NLS-1$ //$NON-NLS-2$
        event.getParamSet().add(createParamSet(createParameter(
                "Parameter", "Параметр", false))); //$NON-NLS-1$ //$NON-NLS-2$

        BslHandlerStubGenerator.StubText ru = generator.generate(
                event, "Handler", ScriptVariant.RUSSIAN, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.COMMAND);
        BslHandlerStubGenerator.StubText en = generator.generate(
                event, "Handler", ScriptVariant.ENGLISH, //$NON-NLS-1$
                BslHandlerStubGenerator.TargetContext.COMMAND);

        assertEquals("Команда, Параметр", ru.signatureText()); //$NON-NLS-1$
        assertEquals("Command, Parameter", en.signatureText()); //$NON-NLS-1$
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
