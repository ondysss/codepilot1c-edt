package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.junit.Test;

import com.codepilot1c.core.tools.ToolResult;

@SuppressWarnings("nls")
public class EditFileToolSafetyTest {

    @Test
    public void fuzzyReplacementFailsAndDoesNotMutateFileWhenNewTextWouldRemoveRequiredCallArguments()
            throws Exception {
        String initialContent = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tЗапрос, \n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";
        String oldText = initialContent.replace("Запрос, ", "Запрос,");
        String newText = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";

        TestFile file = new TestFile(initialContent);

        ToolResult result = executeEdit(file, oldText, newText);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Unsafe fuzzy edit"));
        assertEquals(initialContent, file.content());
    }

    @Test
    public void fuzzyReplacementFailsAndDoesNotMutateFileWhenMatchedSliceContainsDuplicatedCallOnOneLine()
            throws Exception {
        String initialContent = "\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\t"
                + "СкладыСервер.ЗапланироватьОтгрузкуТоваров(\n"
                + "\tВозврат;";
        String oldText = initialContent.replace("\t", "");
        String newText = "\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\n"
                + "\tВозврат;";

        TestFile file = new TestFile(initialContent);

        ToolResult result = executeEdit(file, oldText, newText);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Unsafe fuzzy edit"));
        assertEquals(initialContent, file.content());
    }

    @Test
    public void fuzzyReplacementSucceedsWhenLineShapeIsStable() throws Exception {
        String initialContent = "\tТекстЗапроса = СтрЗаменить(ТекстЗапроса, \"A\", \"B\");\n"
                + "\tВозврат;";
        String oldText = initialContent.replace("\t", "");
        String newText = "\tТекстЗапроса = СтрЗаменить(ТекстЗапроса, \"A\", \"B, C\");\n"
                + "\tВозврат;";

        TestFile file = new TestFile(initialContent);

        ToolResult result = executeEdit(file, oldText, newText);

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("стратегия"));
        assertEquals(newText, file.content());
    }

    @Test
    public void fuzzyReplacementFailsAndDoesNotMutateFileWhenResultWouldGlueProcedureDeclarations()
            throws Exception {
        String initialContent = ""
                + "Процедура Текущая()\n"
                + "\tВозврат; \n"
                + "КонецПроцедуры";
        String oldText = initialContent.replace("Возврат; ", "Возврат;");
        String newText = ""
                + "Процедура Текущая()\n"
                + "\tВозврат;\n"
                + "Процедура Следующая()Процедура Следующая()\n"
                + "КонецПроцедуры";

        TestFile file = new TestFile(initialContent);

        ToolResult result = executeEdit(file, oldText, newText);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("glued BSL"));
        assertEquals(initialContent, file.content());
    }

    private ToolResult executeEdit(TestFile file, String oldText, String newText) {
        return new TestableEditFileTool(file.asIFile()).execute(Map.of(
                "path", "Project/Module.bsl",
                "old_text", oldText,
                "new_text", newText))
                .join();
    }

    private static final class TestableEditFileTool extends EditFileTool {

        private final IFile file;

        TestableEditFileTool(IFile file) {
            this.file = file;
        }

        @Override
        protected IFile findWorkspaceFile(String path) {
            return file;
        }
    }

    private static final class TestFile {

        private final AtomicReference<String> content;
        private final IFile file;

        TestFile(String content) {
            this.content = new AtomicReference<>(content);
            this.file = (IFile) Proxy.newProxyInstance(
                    IFile.class.getClassLoader(),
                    new Class<?>[] { IFile.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "exists" -> Boolean.TRUE;
                        case "getFullPath" -> new Path("/Project/Module.bsl");
                        case "getLocation" -> new Path("/tmp/Project/Module.bsl");
                        case "getCharset" -> StandardCharsets.UTF_8.name();
                        case "getContents" -> new ByteArrayInputStream(
                                this.content.get().getBytes(StandardCharsets.UTF_8));
                        case "setContents" -> {
                            try (InputStream stream = (InputStream) args[0]) {
                                this.content.set(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                            }
                            yield null;
                        }
                        case "toString" -> "/Project/Module.bsl";
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
        }

        IFile asIFile() {
            return file;
        }

        String content() {
            return content.get();
        }
    }
}
