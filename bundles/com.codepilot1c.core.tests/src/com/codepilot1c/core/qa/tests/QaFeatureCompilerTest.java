package com.codepilot1c.core.qa.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.qa.QaCompiledFeature;
import com.codepilot1c.core.qa.QaFeatureCompiler;
import com.codepilot1c.core.qa.QaFeatureValidationResult;
import com.codepilot1c.core.qa.QaScenarioPlan;
import com.codepilot1c.core.qa.QaScenarioPlanner;
import com.codepilot1c.core.qa.QaStepRegistry;
import com.codepilot1c.core.qa.QaStepsCatalog;

public class QaFeatureCompilerTest {

    @Test
    public void plansAndCompilesDocumentDraftUsingCanonicalCatalogSteps() throws Exception {
        QaStepRegistry registry = QaStepRegistry.loadDefault();
        QaScenarioPlanner planner = new QaScenarioPlanner();
        QaScenarioPlan plan = planner.plan(new QaScenarioPlanner.PlanRequest(
                "Подготовь smoke тест создания документа поступления товаров", //$NON-NLS-1$
                "Создание документа поступления", //$NON-NLS-1$
                null,
                "ПоступлениеТоваров", //$NON-NLS-1$
                "Document", //$NON-NLS-1$
                "Поступление товаров", //$NON-NLS-1$
                "Товары", //$NON-NLS-1$
                List.of("Склад", "Поставщик"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(),
                List.of(
                        Map.of("action", "pick_from_list", "table", "Товары", "field", "Номенклатура"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        Map.of("action", "set_text", "table", "Товары", "field", "Количество", "value", "10")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                true,
                true,
                Map.of(),
                List.of("smoke")), registry); //$NON-NLS-1$

        assertTrue(plan.unresolvedBindings().isEmpty());
        QaCompiledFeature compiled = new QaFeatureCompiler().compile(plan, registry,
                QaStepsCatalog.loadFromResource("com/codepilot1c/core/qa/steps_catalog.json", //$NON-NLS-1$
                        QaFeatureCompilerTest.class.getClassLoader()));

        assertTrue(compiled.ready());
        assertEquals(12, compiled.steps().size());
        assertEquals("Дано Я открываю основную форму списка документа \"ПоступлениеТоваров\"", //$NON-NLS-1$
                compiled.steps().get(2));
        assertEquals("И я нажимаю на кнопку \"Создать\"", compiled.steps().get(3)); //$NON-NLS-1$
        assertEquals("Тогда открылось окно \"Поступление товаров (создание)\"", compiled.steps().get(4)); //$NON-NLS-1$
        assertEquals("И Я закрываю текущее окно", compiled.steps().get(compiled.steps().size() - 2)); //$NON-NLS-1$
        assertEquals("И я закрываю TestClient \"Этот клиент\"", compiled.steps().get(compiled.steps().size() - 1)); //$NON-NLS-1$

        QaFeatureValidationResult validation = QaFeatureCompiler.validateFeatureLines(compiled.steps(), registry,
                QaStepsCatalog.loadFromResource("com/codepilot1c/core/qa/steps_catalog.json", //$NON-NLS-1$
                        QaFeatureCompilerTest.class.getClassLoader()));
        assertTrue(validation.ready());
    }

    @Test
    public void plannerPrefersObjectDisplayNameForCreateWindowTitle() throws Exception {
        QaScenarioPlanner planner = new QaScenarioPlanner();
        QaScenarioPlan plan = planner.plan(new QaScenarioPlanner.PlanRequest(
                "Создать документ поступления товара на склад", //$NON-NLS-1$
                "Smoke тест поступления товара на склад", //$NON-NLS-1$
                null,
                "ПоступлениеТоваров", //$NON-NLS-1$
                "Document", //$NON-NLS-1$
                "Продажи", //$NON-NLS-1$
                "Товары", //$NON-NLS-1$
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                Map.of("object_display_name", "Поступление товаров"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("smoke")), QaStepRegistry.loadDefault()); //$NON-NLS-1$

        assertEquals("Поступление товаров (создание)", plan.context().get("create_window_title")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void plannerAddsCloseTestClientByDefaultForUiRecipes() throws Exception {
        QaScenarioPlanner planner = new QaScenarioPlanner();
        QaScenarioPlan plan = planner.plan(new QaScenarioPlanner.PlanRequest(
                "Открыть документ поступления и убедиться, что форма создания доступна", //$NON-NLS-1$
                "Smoke тест открытия документа", //$NON-NLS-1$
                "create_document_draft", //$NON-NLS-1$
                "ПоступлениеТоваров", //$NON-NLS-1$
                "Document", //$NON-NLS-1$
                "Поступление товаров", //$NON-NLS-1$
                "Товары", //$NON-NLS-1$
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                Map.of("object_display_name", "Поступление товаров"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("smoke")), QaStepRegistry.loadDefault()); //$NON-NLS-1$

        assertEquals("client.close_named", plan.steps().get(plan.steps().size() - 1).intent()); //$NON-NLS-1$
    }
}
