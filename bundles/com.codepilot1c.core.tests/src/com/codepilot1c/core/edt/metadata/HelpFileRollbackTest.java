package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

public class HelpFileRollbackTest {
    private final EdtMetadataService service = new EdtMetadataService();

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class FileState {
        byte[] content;
        boolean failRestore;
        IFile file;
        FileState(String language, String current, List<String> events) {
            content = bytes(current);
            file = (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[]{IFile.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> language;
                        case "exists" -> content != null;
                        case "getFullPath", "getProjectRelativePath" -> new Path("/Sample/Help/" + language + ".html");
                        case "getContents" -> new ByteArrayInputStream(content);
                        case "setContents", "create" -> {
                            events.add("restore-" + language);
                            if (failRestore) throw new CoreException(new Status(IStatus.ERROR, "test", "write failed"));
                            content = ((InputStream) args[0]).readAllBytes();
                            yield null;
                        }
                        case "delete" -> { events.add("delete-" + language); content = null; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private void restore(Map<IFile, byte[]> backups, RuntimeException original) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("restoreHelpFiles", Map.class, RuntimeException.class);
        method.setAccessible(true);
        method.invoke(service, backups, original);
    }

    private void verify(Map<String, String> pages, Map<IFile, byte[]> backups) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("verifyHelpFiles", Map.class, Map.class);
        method.setAccessible(true);
        method.invoke(service, pages, backups);
    }

    @Test public void restoresOriginalBytesAfterReplacingExistingHelp() throws Exception {
        FileState state = new FileState("ru", "new", new ArrayList<>());
        RuntimeException original = new RuntimeException("original failure");
        restore(Map.of(state.file, bytes("Старая справка\n")), original);
        assertArrayEquals(bytes("Старая справка\n"), state.content);
        assertEquals(0, original.getSuppressed().length);
    }

    @Test public void removesOnlyPagesCreatedByThisRequest() throws Exception {
        FileState state = new FileState("ru", "new", new ArrayList<>());
        Map<IFile, byte[]> backups = new LinkedHashMap<>();
        backups.put(state.file, null);
        restore(backups, new RuntimeException("original failure"));
        assertNull(state.content);
    }

    @Test public void restoresInReverseOrderAndContinuesAfterRestoreFailure() throws Exception {
        List<String> events = new ArrayList<>();
        FileState ru = new FileState("ru", "new-ru", events);
        FileState en = new FileState("en", "new-en", events);
        en.failRestore = true;
        Map<IFile, byte[]> backups = new LinkedHashMap<>();
        backups.put(ru.file, bytes("old-ru"));
        backups.put(en.file, bytes("old-en"));
        RuntimeException original = new RuntimeException("original failure");
        restore(backups, original);
        assertEquals(List.of("restore-en", "restore-ru"), events);
        assertArrayEquals(bytes("old-ru"), ru.content);
        assertEquals(1, original.getSuppressed().length);
        assertEquals("original failure", original.getMessage());
    }

    @Test public void verifiesExactUtf8ContentIncludingQuotesAndNewline() throws Exception {
        String html = "<html>Справка \"Пример\"</html>\n";
        FileState state = new FileState("ru", html, new ArrayList<>());
        verify(Map.of("ru", html), Map.of(state.file, bytes("old")));
    }

    @Test public void rejectsDifferentFileContent() throws Exception {
        FileState state = new FileState("ru", "different", new ArrayList<>());
        try {
            verify(Map.of("ru", "expected"), Map.of(state.file, bytes("old")));
            fail("Different HTML cannot be reported as persisted");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof MetadataOperationException);
        }
    }

    @Test public void refusesMissingWrittenPage() throws Exception {
        try {
            verify(Map.of("ru", "expected"), Map.of());
            fail("A request without a written page cannot pass verification");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof MetadataOperationException);
        }
    }
}
