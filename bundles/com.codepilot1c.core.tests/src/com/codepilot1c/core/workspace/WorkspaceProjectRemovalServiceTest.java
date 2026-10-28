package com.codepilot1c.core.workspace;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.junit.Test;

public class WorkspaceProjectRemovalServiceTest {
    private static class Fixture {
        final Path directory;
        boolean registered = true;
        int deleted;
        List<String> blockers = List.of();
        IProject[] referring = new IProject[0];
        final IProject project;
        final IWorkspace workspace;
        final WorkspaceProjectRemovalService service;

        Fixture() throws Exception {
            directory = Files.createTempDirectory("codepilot-project-removal-");
            Files.writeString(directory.resolve(".project"), "<projectDescription/>");
            Files.writeString(directory.resolve("source.bsl"), "source retained");
            project = (IProject) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IProject.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> "TemporaryProject";
                        case "exists", "isOpen" -> registered;
                        case "getLocation" -> org.eclipse.core.runtime.Path.fromOSString(directory.toString());
                        case "getReferencingProjects" -> referring;
                        case "delete" -> { assertEquals(Boolean.FALSE,args[0]); assertEquals(Boolean.FALSE,args[1]);
                            deleted++; registered=false; yield null; }
                        case "equals" -> proxy==args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> null;
                    });
            IWorkspaceRoot root=(IWorkspaceRoot) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IWorkspaceRoot.class},
                    (proxy,method,args) -> method.getName().equals("getProject") ? project : null);
            workspace=(IWorkspace) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IWorkspace.class},
                    (proxy,method,args) -> switch(method.getName()) {
                        case "getRoot" -> root;
                        case "validateName" -> args[0].equals("TemporaryProject") ? Status.OK_STATUS : Status.CANCEL_STATUS;
                        case "run" -> { ((IWorkspaceRunnable)args[0]).run(new NullProgressMonitor()); yield null; }
                        default -> null;
                    });
            service=new WorkspaceProjectRemovalService() {
                @Override protected List<String> additionalBlockers(IProject p) { return blockers; }
            };
        }
    }

    @Test public void dryRunNeverUnregistersOrDeletesSources() throws Exception {
        Fixture f=new Fixture();var result=f.service.remove(f.workspace,"TemporaryProject",true,null);
        assertTrue(result.canRemove());assertTrue(result.registered());assertEquals(0,f.deleted);
        assertTrue(Files.exists(f.directory.resolve("source.bsl")));
    }
    @Test public void applyRequiresLocationFromPreview() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalArgumentException.class,
                ()->f.service.remove(f.workspace,"TemporaryProject",false,null));assertEquals(0,f.deleted);
    }
    @Test public void mismatchedLocationRefusesBeforeMutation() throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalArgumentException.class,
                ()->f.service.remove(f.workspace,"TemporaryProject",false,f.directory.resolve("other").toString()));
        assertTrue(f.registered);assertEquals(0,f.deleted);
    }
    @Test public void nativeRemovalUnregistersAndRetainsAllSourceFiles() throws Exception {
        Fixture f=new Fixture();var result=f.service.remove(f.workspace,"TemporaryProject",false,f.directory.toString());
        assertEquals("removed",result.status());assertFalse(f.registered);assertEquals(1,f.deleted);
        assertEquals("source retained",Files.readString(f.directory.resolve("source.bsl")));
        assertTrue(Files.exists(f.directory.resolve(".project")));assertTrue(result.sourceFilesPreserved());
    }
    @Test public void nativeReferenceBlocksPreviewAndMutation() throws Exception {
        Fixture f=new Fixture();f.referring=new IProject[]{f.project};
        assertFalse(f.service.remove(f.workspace,"TemporaryProject",true,null).canRemove());
        assertThrows(IllegalStateException.class,()->f.service.remove(f.workspace,"TemporaryProject",false,f.directory.toString()));
        assertEquals(0,f.deleted);
    }
    @Test public void runtimeDependencyOrUnsavedEditorBlocksRemoval() throws Exception {
        Fixture f=new Fixture();f.blockers=List.of("Unsaved editor: source.bsl");
        assertFalse(f.service.remove(f.workspace,"TemporaryProject",true,null).canRemove());
        assertThrows(IllegalStateException.class,()->f.service.remove(f.workspace,"TemporaryProject",false,f.directory.toString()));
        assertEquals(0,f.deleted);
    }
    @Test public void missingProjectIsReportedWithoutMutation() throws Exception {
        Fixture f=new Fixture();f.registered=false;
        assertEquals("not_found",f.service.remove(f.workspace,"TemporaryProject",false,null).status());assertEquals(0,f.deleted);
    }
    @Test public void namesCannotBePathsOrWorkspaceRoot() throws Exception {
        Fixture f=new Fixture();for(String name:List.of("", "../TemporaryProject", "/project/path")) {
            assertThrows(IllegalArgumentException.class,()->f.service.remove(f.workspace,name,false,f.directory.toString()));
        }
        assertEquals(0,f.deleted);
    }
}
