package com.codepilot1c.core.edt.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.ContainedObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class EdtExtensionCreateCompatibilityTest {

    @Test
    public void rootInheritsBaseModeAndKeepsIndependentExtensionMode() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        new EdtExtensionService(gateway).createProject(request("8.3.14")); //$NON-NLS-1$
        assertSame(CompatibilityMode.VERSION8_324, gateway.created.getCompatibilityMode());
        assertSame(CompatibilityMode.VERSION8_314,
                gateway.created.getConfigurationExtensionCompatibilityMode());
        assertSame(ConfigurationExtensionPurpose.CUSTOMIZATION,
                gateway.created.getConfigurationExtensionPurpose());
        assertEquals("Tests", gateway.created.getName()); //$NON-NLS-1$
        assertEquals(Version.create("8.3.27"), gateway.createdVersion); //$NON-NLS-1$
        assertSame(CompatibilityMode.VERSION8_324, gateway.base.getCompatibilityMode());
    }

    @Test
    public void omittedExtensionModeStillInheritsBaseRootMode() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        new EdtExtensionService(gateway).createProject(request(null));
        assertSame(CompatibilityMode.VERSION8_324, gateway.created.getCompatibilityMode());
    }

    @Test
    public void olderBaseModeIsNotReplacedByModelFactoryDefault() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_310);
        new EdtExtensionService(gateway).createProject(request("8.3.10")); //$NON-NLS-1$
        assertSame(CompatibilityMode.VERSION8_310, gateway.created.getCompatibilityMode());
    }

    @Test
    public void newerExtensionModeDoesNotOverwriteBaseRootMode() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        new EdtExtensionService(gateway).createProject(request("8.3.27")); //$NON-NLS-1$
        assertSame(CompatibilityMode.VERSION8_324, gateway.created.getCompatibilityMode());
        assertSame(CompatibilityMode.VERSION8_327,
                gateway.created.getConfigurationExtensionCompatibilityMode());
    }

    @Test
    public void rootIsAdoptedForPlatformExtensionImport() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        new EdtExtensionService(gateway).createProject(request("8.3.24")); //$NON-NLS-1$
        assertSame(ObjectBelonging.ADOPTED, gateway.created.getObjectBelonging());
    }

    @Test
    public void rootHasIndependentUuidBeforeProjectCreation() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        new EdtExtensionService(gateway).createProject(request("8.3.24")); //$NON-NLS-1$
        assertNotNull(gateway.created.getUuid());
        assertNotEquals(gateway.base.getUuid(), gateway.created.getUuid());
    }

    @Test
    public void internalTypesKeepClassIdsWithIndependentObjectIds() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        new EdtExtensionService(gateway).createProject(request("8.3.24")); //$NON-NLS-1$
        assertEquals(7, gateway.created.getContainedObjects().size());
        for (int i = 0; i < 7; i++) {
            ContainedObject original = gateway.base.getContainedObjects().get(i);
            ContainedObject copied = gateway.created.getContainedObjects().get(i);
            assertNotSame(original, copied);
            assertEquals(original.getClassId(), copied.getClassId());
            assertNotNull(copied.getObjectId());
            assertNotEquals(original.getObjectId(), copied.getObjectId());
        }
    }

    @Test
    public void unavailableBaseConfigurationRefusesCreation() {
        FakeGateway gateway = new FakeGateway(CompatibilityMode.VERSION8_324);
        gateway.configurationAvailable = false;
        MetadataOperationException error = assertThrows(MetadataOperationException.class,
                () -> new EdtExtensionService(gateway).createProject(request("8.3.24"))); //$NON-NLS-1$
        assertEquals(MetadataOperationCode.METADATA_NOT_FOUND, error.getCode());
        assertFalse(gateway.projectCreated);
    }

    private ExtensionCreateProjectRequest request(String extensionMode) {
        return new ExtensionCreateProjectRequest("Demo", "Demo.Tests", null, //$NON-NLS-1$ //$NON-NLS-2$
                null, "Tests", "CUSTOMIZATION", extensionMode); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class FakeGateway extends EdtMetadataGateway {
        final Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        Configuration created;
        Version createdVersion;
        boolean projectCreated;
        boolean configurationAvailable = true;

        FakeGateway(CompatibilityMode mode) {
            base.setUuid(UUID.randomUUID());
            base.setCompatibilityMode(mode);
            String[] classes = {
                "9cd510cd-abfc-11d4-9434-004095e12fc7", "9fcd25a0-4822-11d4-9414-008048da11f9", //$NON-NLS-1$ //$NON-NLS-2$
                "e3687481-0a87-462c-a166-9f34594f9bba", "9de14907-ec23-4a07-96f0-85521cb6b53b", //$NON-NLS-1$ //$NON-NLS-2$
                "51f2d5d8-ea4d-4064-8892-82951750031e", "e68182ea-4237-4383-967f-90c1e3370bc7", //$NON-NLS-1$ //$NON-NLS-2$
                "fb282519-d103-4dd3-bc12-cb271d631dfc" //$NON-NLS-1$
            };
            for (String classId : classes) {
                ContainedObject object = MdClassFactory.eINSTANCE.createContainedObject();
                object.setClassId(UUID.fromString(classId));
                object.setObjectId(UUID.randomUUID());
                base.getContainedObjects().add(object);
            }
        }

        @Override
        public void ensureExtensionRuntimeAvailable() {
        }

        @Override
        public IProject resolveProject(String name) {
            boolean baseProject = "Demo".equals(name); //$NON-NLS-1$
            return proxy(IProject.class, (p, m, a) -> switch (m.getName()) {
                case "exists" -> baseProject || projectCreated; //$NON-NLS-1$
                case "getName" -> name; //$NON-NLS-1$
                case "getLocation" -> new Path("/workspace/" + name); //$NON-NLS-1$ //$NON-NLS-2$
                default -> null;
            });
        }

        @Override
        public IConfigurationProvider getConfigurationProvider() {
            return proxy(IConfigurationProvider.class, (p, m, a) ->
                    "getConfiguration".equals(m.getName()) && configurationAvailable ? base : null); //$NON-NLS-1$
        }

        @Override
        public IV8ProjectManager getV8ProjectManager() {
            return proxy(IV8ProjectManager.class, (p, m, a) -> {
                if ("getProjects".equals(m.getName())) return List.of(); //$NON-NLS-1$
                if (!"getProject".equals(m.getName())) return null; //$NON-NLS-1$
                if (a[0] instanceof String) {
                    return proxy(IExtensionProject.class, (q, n, b) -> switch (n.getName()) {
                        case "getConfiguration" -> created; //$NON-NLS-1$
                        case "getProject" -> resolveProject("Demo.Tests"); //$NON-NLS-1$ //$NON-NLS-2$
                        default -> null;
                    });
                }
                return proxy(IV8Project.class, (q, n, b) ->
                        "getVersion".equals(n.getName()) ? Version.create("8.3.27") : null); //$NON-NLS-1$ //$NON-NLS-2$
            });
        }

        @Override
        public IExtensionProjectManager getExtensionProjectManager() {
            return proxy(IExtensionProjectManager.class, (p, m, a) -> {
                if (!"create".equals(m.getName())) return null; //$NON-NLS-1$
                createdVersion = (Version) a[2];
                created = (Configuration) a[3];
                projectCreated = true;
                return resolveProject((String) a[0]);
            });
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
        }
    }
}
