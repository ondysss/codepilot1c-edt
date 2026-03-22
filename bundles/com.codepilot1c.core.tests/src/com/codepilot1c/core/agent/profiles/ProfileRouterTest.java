package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProfileRouterTest {

    private final ProfileRouter router = new ProfileRouter();

    @Test
    public void routesSingleDomainMetadataPromptToMetadataProfile() {
        assertEquals(MetadataBuildProfile.ID,
                router.route("Создай справочник Товары и форму списка")); //$NON-NLS-1$
    }

    @Test
    public void routesCrossDomainPromptToOrchestrator() {
        assertEquals(OrchestratorProfile.ID,
                router.route("Создай справочник Товары и напиши QA сценарий для Vanessa")); //$NON-NLS-1$
    }

    @Test
    public void routesPlanningPromptToPlanProfileWhenNoDomainMatched() {
        assertEquals(PlanAgentProfile.ID,
                router.route("Составь план рефакторинга модуля и оценку рисков")); //$NON-NLS-1$
    }

    @Test
    public void routesDiscoveryPromptToExploreProfileWhenNoDomainMatched() {
        assertEquals(ExploreAgentProfile.ID,
                router.route("Найди где в проекте вызывается загрузка данных")); //$NON-NLS-1$
    }

    @Test
    public void resolvesBuildToAutoRoutedDomain() {
        assertEquals(QABuildProfile.ID,
                router.resolveRequestedProfile("Подготовь и запусти тесты YAxUnit", "build")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void normalizesDelegateAliases() {
        assertEquals(ExtensionBuildProfile.ID, router.normalizeProfileId("расширения")); //$NON-NLS-1$
        assertEquals(RecoveryProfile.ID, router.normalizeProfileId("diag")); //$NON-NLS-1$
        assertEquals("auto", router.normalizeProfileId("auto")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
