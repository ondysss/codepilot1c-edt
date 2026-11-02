package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class HelpPagePatchTest {
    private Map<String, Object> changes(Object value) {
        return Map.of("set", Map.of("help", value));
    }

    private void refused(Map<String, Object> value) {
        try {
            HelpPagePatch.fromChanges(value);
            fail("Expected an invalid help patch");
        } catch (MetadataOperationException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void ordinaryMetadataMutationIsUnaffected() {
        assertNull(HelpPagePatch.fromChanges(Map.of("set", Map.of("name", "Example"))));
        assertNull(HelpPagePatch.fromChanges(Map.of("unset", java.util.List.of("comment"))));
    }

    @Test public void acceptsMultipleLanguagesWithoutChangingHtml() {
        Map<String, String> patch = HelpPagePatch.fromChanges(changes(
                Map.of("RU", "<html><body>Справка \"Пример\"</body></html>\n", "en", "<p>Help</p>")));
        assertEquals(2, patch.size());
        assertEquals("<html><body>Справка \"Пример\"</body></html>\n", patch.get("ru"));
        assertEquals("<p>Help</p>", patch.get("en"));
    }

    @Test public void rejectsEmptyOrNonObjectPages() {
        refused(changes(Map.of()));
        refused(changes("<html>Missing language</html>"));
        refused(changes(java.util.List.of("ru")));
    }

    @Test public void rejectsEmptyAndNonTextHtml() {
        refused(changes(Map.of("ru", "  \n")));
        refused(changes(Map.of("ru", false)));
        refused(changes(Map.of("ru", "<p>a\0b</p>")));
    }

    @Test public void rejectsPathLikeLanguages() {
        for (String language : java.util.List.of("../ru", "ru/other", "ru\\other", "/ru", "ru.html", " ru")) {
            refused(changes(Map.of(language, "<p>Help</p>")));
        }
    }

    @Test public void rejectsDuplicateNormalizedLanguage() {
        refused(changes(Map.of("ru", "first", "RU", "second")));
    }

    @Test public void refusesMixedMutationBeforeWritingAnyPage() {
        refused(Map.of("set", Map.of("help", Map.of("ru", "<p>Help</p>"), "name", "Changed")));
        refused(Map.of("set", Map.of("help", Map.of("ru", "<p>Help</p>")),
                       "unset", java.util.List.of("comment")));
    }

    @Test public void invalidLaterLanguageDoesNotMutateTheRequest() {
        Map<String, Object> pages = new LinkedHashMap<>();
        pages.put("ru", "<p>Help</p>");
        pages.put("../../escape", "wrong");
        Map<String, Object> before = new LinkedHashMap<>(pages);
        refused(changes(pages));
        assertEquals(before, pages);
    }
}
