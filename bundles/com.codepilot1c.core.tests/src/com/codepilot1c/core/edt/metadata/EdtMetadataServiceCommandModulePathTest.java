package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import sun.misc.Unsafe;

/** Tests the path selector without an Eclipse workbench or platform transaction. */
public class EdtMetadataServiceCommandModulePathTest {
    private static final String SERVICE = "com.codepilot1c.core.edt.metadata.EdtMetadataService";

    @Test
    public void documentCommandNeverFallsBackToItsObjectModule() throws Exception {
        assertCommand("Document", "DocumentCommand", "Documents", ModuleArtifactKind.AUTO);
    }

    @Test
    public void catalogCommandUsesItsOwnFolder() throws Exception {
        assertCommand("Catalog", "CatalogCommand", "Catalogs", ModuleArtifactKind.AUTO);
    }

    @Test
    public void reportCommandUsesItsOwnFolder() throws Exception {
        assertCommand("Report", "ReportCommand", "Reports", ModuleArtifactKind.AUTO);
    }

    @Test
    public void dataProcessorCommandUsesItsOwnFolder() throws Exception {
        assertCommand("DataProcessor", "DataProcessorCommand", "DataProcessors", ModuleArtifactKind.AUTO);
    }

    @Test
    public void explicitCommandKindStillUsesTheNestedFolder() throws Exception {
        assertCommand("Document", "DocumentCommand", "Documents", ModuleArtifactKind.COMMAND);
    }

    @Test
    public void genericModuleAliasStillTargetsTheCommand() throws Exception {
        assertCommand("Document", "DocumentCommand", "Documents", ModuleArtifactKind.MODULE);
    }

    @Test
    public void objectModuleRequestForACommandIsRejected() throws Exception {
        try {
            candidates("DocumentCommand", "src/Documents/Order/Order.mdo", "Document", "Order", "Complete",
                    ModuleArtifactKind.OBJECT);
            fail("A command must not select its parent's object module");
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_KIND, e.getCode());
        }
    }

    @Test
    public void missingCommandNameIsRejectedInsteadOfSelectingParent() throws Exception {
        try {
            candidates("DocumentCommand", "src/Documents/Order/Order.mdo", "Document", "Order", null,
                    ModuleArtifactKind.AUTO);
            fail("A missing command name must not select a parent module");
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_KIND, e.getCode());
        }
    }

    @Test
    public void existingFormPathRemainsUnchanged() throws Exception {
        List<String> paths = candidates("DocumentForm", "src/Documents/Order/Forms/Main/Form.form", "Document",
                "Order", "Main", ModuleArtifactKind.AUTO);
        assertEquals(List.of("src/Documents/Order/Forms/Main/Module.bsl"), paths);
    }

    @Test
    public void topLevelCommonCommandRemainsUnchanged() throws Exception {
        assertEquals(List.of("src/CommonCommands/Run/CommandModule.bsl"),
                candidates("CommonCommand", "src/CommonCommands/Run/Run.mdo", "CommonCommand", "Run", null,
                        ModuleArtifactKind.AUTO));
    }

    @Test
    public void parentDocumentObjectModuleRemainsUnchanged() throws Exception {
        assertEquals(List.of("src/Documents/Order/ObjectModule.bsl"),
                candidates("Document", "src/Documents/Order/Order.mdo", "Document", "Order", null,
                        ModuleArtifactKind.AUTO));
    }

    private void assertCommand(String kind, String className, String folder, ModuleArtifactKind moduleKind)
            throws Exception {
        List<String> paths = candidates(className, "src/" + folder + "/Order/Order.mdo", kind, "Order", "Complete",
                moduleKind);
        assertEquals(List.of("src/" + folder + "/Order/Commands/Complete/CommandModule.bsl"), paths);
        assertFalse(paths.stream().anyMatch(p -> p.endsWith("/ObjectModule.bsl")));
    }

    @SuppressWarnings("unchecked")
    private List<String> candidates(String className, String resourcePath, String topKind, String topName,
            String nestedName, ModuleArtifactKind kind) throws Exception {
        Class<?> serviceType = Class.forName(SERVICE);
        Class<?> targetType = Class.forName(SERVICE + "$ModuleTarget");
        Constructor<?> ctor = targetType.getDeclaredConstructor(String.class, String.class, String.class,
                String.class, String.class);
        ctor.setAccessible(true);
        Object target = ctor.newInstance(className, resourcePath, topKind, topName, nestedName);
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object service = ((Unsafe) field.get(null)).allocateInstance(serviceType);
        Method method = serviceType.getDeclaredMethod("buildModuleCandidates", targetType, ModuleArtifactKind.class);
        method.setAccessible(true);
        try {
            return (List<String>) method.invoke(service, target, kind);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
