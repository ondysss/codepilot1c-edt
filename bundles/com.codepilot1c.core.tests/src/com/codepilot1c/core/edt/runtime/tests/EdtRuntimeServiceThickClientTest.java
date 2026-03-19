package com.codepilot1c.core.edt.runtime.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;

/**
 * Unit tests for {@link EdtRuntimeService#resolveThickClientInfo} — new EDT 2025.2.0 API.
 *
 * <p>Tests verify that {@code getThickClientInfo()} (removed in EDT 2025.2.0) is replaced
 * with {@link IResolvableRuntimeInstallationManager} + {@link IRuntimeComponentManager#resolveExecutor}.
 */
public class EdtRuntimeServiceThickClientTest {

    // ─── Happy path: versionMask ──────────────────────────────────────────────

    @Test
    public void resolvesThickClientByVersionMask() {
        ILaunchableRuntimeComponent stubComponent = stubLaunchableComponent();
        IThickClientLauncher stubLauncher = stubThickClientLauncher();
        RuntimeInstallation stubInstallation = stubRuntimeInstallation();
        IResolvableRuntimeInstallation stubResolvable = stubResolvable(stubInstallation);

        EdtRuntimeService service = serviceWithGateway(
                installationManager_versionMask("8.3.25.*", stubResolvable),
                runtimeComponentManager(stubInstallation, stubComponent, stubLauncher),
                null /* assocManager not used when versionMask is set */);

        ThickClientInfo info = service.resolveThickClientInfo(null, "8.3.25.*"); //$NON-NLS-1$

        assertNotNull(info);
        assertSame(stubComponent, info.component());
        assertSame(stubLauncher, info.launcher());
    }

    // ─── Happy path: no versionMask, resolves project from infobase ───────────

    @Test
    public void resolvesThickClientByInfobaseAndProject() {
        ILaunchableRuntimeComponent stubComponent = stubLaunchableComponent();
        IThickClientLauncher stubLauncher = stubThickClientLauncher();
        RuntimeInstallation stubInstallation = stubRuntimeInstallation();
        IResolvableRuntimeInstallation stubResolvable = stubResolvable(stubInstallation);
        IProject stubProject = stubProject();
        InfobaseReference stubInfobase = stubInfobaseReference();

        IInfobaseAssociationManager assocManager = assocManager_returnsProject(stubInfobase, stubProject);
        IResolvableRuntimeInstallationManager installManager =
                installationManager_byProject(IRuntimeComponentTypes.THICK_CLIENT, stubProject, stubInfobase,
                        InfobaseAccessType.CLIENT_LAUNCH, stubResolvable);

        EdtRuntimeService service = serviceWithGateway(
                installManager,
                runtimeComponentManager(stubInstallation, stubComponent, stubLauncher),
                assocManager);

        ThickClientInfo info = service.resolveThickClientInfo(stubInfobase, null);

        assertNotNull(info);
        assertSame(stubComponent, info.component());
        assertSame(stubLauncher, info.launcher());
    }

    // ─── Error path: MatchingRuntimeNotFound → IllegalStateException ─────────

    @Test
    public void throwsIllegalStateWhenRuntimeNotFound() {
        IResolvableRuntimeInstallationManager installManager = new IResolvableRuntimeInstallationManager() {
            @Override
            public IResolvableRuntimeInstallation resolveByVersionOrMask(String typeId, String versionMask)
                    throws MatchingRuntimeNotFound {
                throw new MatchingRuntimeNotFound("no platform found for: " + versionMask); //$NON-NLS-1$
            }

            // unused methods
            @Override public Collection<IResolvableRuntimeInstallation> getAll(String t) { throw new UnsupportedOperationException(); }
            @Override public String serialize(IResolvableRuntimeInstallation r) { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation deserialize(String s) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<IResolvableRuntimeInstallation> findVersionCompatible(String t, IProject p, InfobaseReference i, InfobaseAccessType a) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<IResolvableRuntimeInstallation> findUsefulForProjectAndInfobase(String t, IProject p, InfobaseReference i, InfobaseAccessType a) { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveEnvironmentByMask(String t, String m) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveLatest(String t) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByFullVersion(String t, String v, Collection<String> ids, com._1c.g5.v8.dt.platform.services.model.AppArch a) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByVersionAndInfobase(String t, com._1c.g5.v8.dt.platform.version.Version v, InfobaseReference i, InfobaseAccessType a, Collection<String> ids) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByProjectAndInfobase(String t, IProject p, InfobaseReference i, InfobaseAccessType a) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
        };

        EdtRuntimeService service = serviceWithGateway(installManager, null, null);

        try {
            service.resolveThickClientInfo(null, "8.3.99.*"); //$NON-NLS-1$
            fail("Expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            // expected — MatchingRuntimeNotFound wrapped in IllegalStateException
            assertNotNull(e.getCause());
            assertEquals(MatchingRuntimeNotFound.class, e.getCause().getClass());
        }
    }

    // ─── Error path: component file is null ──────────────────────────────────

    @Test
    public void throwsWhenComponentFileIsNull() {
        ILaunchableRuntimeComponent componentWithNullFile = new ILaunchableRuntimeComponent() {
            @Override public java.io.File getFile() { return null; }
            @Override public String getName() { return "test"; } //$NON-NLS-1$
            @Override public java.net.URI getLocation() { return null; }
            @Override public com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentType getType() { return null; }
            @Override public RuntimeInstallation getInstallation() { return null; }
        };
        IThickClientLauncher stubLauncher = stubThickClientLauncher();
        RuntimeInstallation stubInstallation = stubRuntimeInstallation();
        IResolvableRuntimeInstallation stubResolvable = stubResolvable(stubInstallation);

        EdtRuntimeService service = serviceWithGateway(
                installationManager_versionMask("8.3.25.*", stubResolvable),
                runtimeComponentManager(stubInstallation, componentWithNullFile, stubLauncher),
                null);

        try {
            service.resolveThickClientInfo(null, "8.3.25.*"); //$NON-NLS-1$
            fail("Expected IllegalStateException for null component file"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            // expected
        }
    }

    // ─── Stubs ────────────────────────────────────────────────────────────────

    private EdtRuntimeService serviceWithGateway(IResolvableRuntimeInstallationManager installManager,
            IRuntimeComponentManager runtimeManager,
            IInfobaseAssociationManager assocManager) {
        return new EdtRuntimeService(new EdtRuntimeGateway() {
            @Override
            public IResolvableRuntimeInstallationManager getResolvableRuntimeInstallationManager() {
                return installManager;
            }
            @Override
            public IRuntimeComponentManager getRuntimeComponentManager() {
                return runtimeManager;
            }
            @Override
            public IInfobaseAssociationManager getInfobaseAssociationManager() {
                return assocManager;
            }
        });
    }

    private IResolvableRuntimeInstallationManager installationManager_versionMask(
            String expectedMask, IResolvableRuntimeInstallation result) {
        return new IResolvableRuntimeInstallationManager() {
            @Override
            public IResolvableRuntimeInstallation resolveByVersionOrMask(String typeId, String mask)
                    throws MatchingRuntimeNotFound {
                assertEquals(IRuntimeComponentTypes.THICK_CLIENT, typeId);
                assertEquals(expectedMask, mask);
                return result;
            }
            @Override public Collection<IResolvableRuntimeInstallation> getAll(String t) { throw new UnsupportedOperationException(); }
            @Override public String serialize(IResolvableRuntimeInstallation r) { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation deserialize(String s) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<IResolvableRuntimeInstallation> findVersionCompatible(String t, IProject p, InfobaseReference i, InfobaseAccessType a) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<IResolvableRuntimeInstallation> findUsefulForProjectAndInfobase(String t, IProject p, InfobaseReference i, InfobaseAccessType a) { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveEnvironmentByMask(String t, String m) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveLatest(String t) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByFullVersion(String t, String v, Collection<String> ids, AppArch a) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByVersionAndInfobase(String t, com._1c.g5.v8.dt.platform.version.Version v, InfobaseReference i, InfobaseAccessType a, Collection<String> ids) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByProjectAndInfobase(String t, IProject p, InfobaseReference i, InfobaseAccessType a) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
        };
    }

    private IResolvableRuntimeInstallationManager installationManager_byProject(
            String expectedTypeId, IProject expectedProject, InfobaseReference expectedInfobase,
            InfobaseAccessType expectedAccessType, IResolvableRuntimeInstallation result) {
        return new IResolvableRuntimeInstallationManager() {
            @Override
            public IResolvableRuntimeInstallation resolveByProjectAndInfobase(String typeId, IProject project,
                    InfobaseReference infobase, InfobaseAccessType accessType) throws MatchingRuntimeNotFound {
                assertEquals(expectedTypeId, typeId);
                assertSame(expectedProject, project);
                assertSame(expectedInfobase, infobase);
                assertEquals(expectedAccessType, accessType);
                return result;
            }
            @Override public IResolvableRuntimeInstallation resolveByVersionOrMask(String t, String m) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public Collection<IResolvableRuntimeInstallation> getAll(String t) { throw new UnsupportedOperationException(); }
            @Override public String serialize(IResolvableRuntimeInstallation r) { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation deserialize(String s) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<IResolvableRuntimeInstallation> findVersionCompatible(String t, IProject p, InfobaseReference i, InfobaseAccessType a) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<IResolvableRuntimeInstallation> findUsefulForProjectAndInfobase(String t, IProject p, InfobaseReference i, InfobaseAccessType a) { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveEnvironmentByMask(String t, String m) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveLatest(String t) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByFullVersion(String t, String v, Collection<String> ids, AppArch a) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
            @Override public IResolvableRuntimeInstallation resolveByVersionAndInfobase(String t, com._1c.g5.v8.dt.platform.version.Version v, InfobaseReference i, InfobaseAccessType a, Collection<String> ids) throws MatchingRuntimeNotFound { throw new UnsupportedOperationException(); }
        };
    }

    @SuppressWarnings("unchecked")
    private IRuntimeComponentManager runtimeComponentManager(RuntimeInstallation expectedInstallation,
            ILaunchableRuntimeComponent component, IThickClientLauncher launcher) {
        return new IRuntimeComponentManager() {
            @Override
            public <C extends com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponent,
                    E extends com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentExecutor>
            ComponentExecutorInfo<C, E> resolveExecutor(Class<C> componentClass, Class<E> executorClass,
                    RuntimeInstallation installation, String typeId) throws RuntimeExecutionException {
                assertEquals(ILaunchableRuntimeComponent.class, componentClass);
                assertEquals(IThickClientLauncher.class, executorClass);
                assertEquals(IRuntimeComponentTypes.THICK_CLIENT, typeId);
                return new ComponentExecutorInfo<>(installation, (C) component, (E) launcher);
            }
            @Override public boolean supportsExecution(RuntimeInstallation r, String t) { throw new UnsupportedOperationException(); }
            @Override public com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentType getType(String t) { throw new UnsupportedOperationException(); }
            @Override public Collection<com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentType> getTypes(Class<? extends com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentExecutor> c) { throw new UnsupportedOperationException(); }
            @Override public boolean hasComponent(RuntimeInstallation r, String... t) { throw new UnsupportedOperationException(); }
            @Override public com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponent getComponent(RuntimeInstallation r, String t) { throw new UnsupportedOperationException(); }
            @Override public Collection<com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponent> getComponents(RuntimeInstallation r) { throw new UnsupportedOperationException(); }
            @Override public Collection<String> idsToVisibleNames(Collection<String> ids) { throw new UnsupportedOperationException(); }
        };
    }

    private IInfobaseAssociationManager assocManager_returnsProject(InfobaseReference infobase, IProject project) {
        return (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class },
                (proxy, method, args) -> {
                    if ("getAssociation".equals(method.getName()) //$NON-NLS-1$
                            && args != null && args.length == 1
                            && args[0] instanceof InfobaseReference) {
                        IInfobaseAssociation assoc = new IInfobaseAssociation() {
                            @Override public IProject getProject() { return project; }
                            @Override public Collection<InfobaseReference> getInfobases() { return java.util.List.of(infobase); }
                            @Override public InfobaseReference getDefaultInfobase() { return infobase; }
                        };
                        return Optional.of(assoc);
                    }
                    throw new UnsupportedOperationException("Unexpected call: " + method.getName()); //$NON-NLS-1$
                });
    }

    private IResolvableRuntimeInstallation stubResolvable(RuntimeInstallation installation) {
        return new IResolvableRuntimeInstallation() {
            @Override public RuntimeInstallation resolve(Collection<String> typeIds, AppArch arch)
                    throws MatchingRuntimeNotFound { return installation; }
            @Override public String getName() { return "stub"; } //$NON-NLS-1$
            @Override public String getVersionMask() { return "8.3.25.*"; } //$NON-NLS-1$
            @Override public String getRuntimeTypeId() { return IRuntimeComponentTypes.THICK_CLIENT; }
            @Override public boolean isConsistent(IResolvableRuntimeInstallation other) { return true; }
            @Override public int compareTo(IResolvableRuntimeInstallation o) { return 0; }
        };
    }

    private RuntimeInstallation stubRuntimeInstallation() {
        return (RuntimeInstallation) Proxy.newProxyInstance(
                RuntimeInstallation.class.getClassLoader(),
                new Class<?>[] { RuntimeInstallation.class },
                (proxy, method, args) -> null);
    }

    private ILaunchableRuntimeComponent stubLaunchableComponent() {
        return new ILaunchableRuntimeComponent() {
            @Override public java.io.File getFile() { return new java.io.File("/stub/1cv8.exe"); } //$NON-NLS-1$
            @Override public String getName() { return "ThickClient"; } //$NON-NLS-1$
            @Override public java.net.URI getLocation() { return null; }
            @Override public com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentType getType() { return null; }
            @Override public RuntimeInstallation getInstallation() { return null; }
        };
    }

    private IThickClientLauncher stubThickClientLauncher() {
        return (IThickClientLauncher) Proxy.newProxyInstance(
                IThickClientLauncher.class.getClassLoader(),
                new Class<?>[] { IThickClientLauncher.class },
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }

    private InfobaseReference stubInfobaseReference() {
        return (InfobaseReference) Proxy.newProxyInstance(
                InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class },
                (proxy, method, args) -> null);
    }

    private IProject stubProject() {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> null);
    }
}
