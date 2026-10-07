package com.codepilot1c.core.workspace;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import com.codepilot1c.core.workspace.EdtProjectContextService.ContextException;

public class EdtProjectContextServiceTest {
    private static class Fake implements EdtProjectContextService.Gateway {
        List<String> persistent = List.of("old"), effective = persistent;
        int writes, builds; boolean ignoreWrite, failBuild;
        public void validateProject(String p) { if (p.equals("missing")) throw new ContextException("PROJECT_NOT_READY", p); }
        public List<String> readPersistent(String p) { return persistent; }
        public List<String> readEffective(String p) { return effective; }
        public void write(String p, Set<String> c) { writes++; if (!ignoreWrite) persistent = effective = List.copyOf(c); }
        public void build(String p) { builds++; if (failBuild) throw new IllegalStateException("build failed"); }
        public String pluginVersion() { return "1.1.22"; }
    }
    private static void rejected(Fake f, String project, String action, List<?> c, boolean build, String code) {
        try { new EdtProjectContextService(f).execute(project, action, c, false, build); fail("must reject"); }
        catch (ContextException e) { assertEquals(code, e.code); }
        assertEquals(0, f.writes); assertEquals(0, f.builds); assertEquals(List.of("old"), f.persistent);
    }
    @Test public void inspectDoesNotWriteOrBuild() {
        Fake f=new Fake(); var r=new EdtProjectContextService(f).execute("tests","inspect",null,true,false);
        assertEquals("inspected",r.status()); assertTrue(r.after().cacheMatchesPersistent()); assertEquals(0,f.writes);
    }
    @Test public void dryRunPreservesSettings() {
        Fake f=new Fake(); var r=new EdtProjectContextService(f).execute("tests","set",List.of("core"),true,true);
        assertEquals("dry_run",r.status()); assertEquals(List.of("core"),r.requestedContexts()); assertEquals(0,f.writes);assertEquals(0,f.builds);
    }
    @Test public void replacementReadbackAndTargetBuild() {
        Fake f=new Fake(); var r=new EdtProjectContextService(f).execute("tests","set",List.of("core","product"),false,true);
        assertEquals(List.of("core","product"),r.after().persistentContexts()); assertTrue(r.settingsChanged()); assertTrue(r.buildCompleted());assertEquals(1,f.builds);
    }
    @Test public void explicitEmptyArrayClears() {
        Fake f=new Fake(); var r=new EdtProjectContextService(f).execute("tests","set",List.of(),false,false);
        assertTrue(r.after().persistentContexts().isEmpty());assertEquals(1,f.writes);
    }
    @Test public void missingListDoesNotImplicitlyClear() { rejected(new Fake(),"tests","set",null,false,"INVALID_ARGUMENT"); }
    @Test public void missingLinkedProjectCannotPartiallySave() { rejected(new Fake(),"tests","set",List.of("core","missing"),false,"PROJECT_NOT_READY"); }
    @Test public void selfContextRejected() { rejected(new Fake(),"tests","set",List.of("tests"),false,"INVALID_ARGUMENT"); }
    @Test public void duplicatesRejected() { rejected(new Fake(),"tests","set",List.of("core","core"),false,"INVALID_ARGUMENT"); }
    @Test public void malformedNamesRejected() { rejected(new Fake(),"tests","set",List.of(" core"),false,"INVALID_ARGUMENT"); }
    @Test public void nonStringNamesRejected() { rejected(new Fake(),"tests","set",List.of(1),false,"INVALID_ARGUMENT"); }
    @Test public void inspectRejectsBuild() { rejected(new Fake(),"tests","inspect",null,true,"INVALID_ARGUMENT"); }
    @Test public void inspectRejectsReplacement() { rejected(new Fake(),"tests","inspect",List.of(),false,"INVALID_ARGUMENT"); }
    @Test public void cacheMismatchVisibleAndRepaired() {
        Fake f=new Fake();f.effective=List.of();var r=new EdtProjectContextService(f).execute("tests","set",List.of("old"),false,false);
        assertFalse(r.before().cacheMatchesPersistent());assertTrue(r.after().cacheMatchesPersistent());assertTrue(r.settingsChanged());
    }
    @Test public void ignoredSetterFailsReadbackAndRollsBack() {
        Fake f=new Fake();f.ignoreWrite=true;
        try {new EdtProjectContextService(f).execute("tests","set",List.of("core"),false,true);fail();}
        catch(ContextException e){assertEquals("READBACK_MISMATCH",e.code);}
        assertEquals(2,f.writes);assertEquals(0,f.builds);assertEquals(List.of("old"),f.persistent);
    }
    @Test public void buildFailureDoesNotPretendSettingsWereRolledBack() {
        Fake f=new Fake();f.failBuild=true;
        try {new EdtProjectContextService(f).execute("tests","set",List.of("core"),false,true);fail();}
        catch(ContextException e){assertEquals("PROJECT_BUILD_FAILED",e.code);assertTrue(e.getMessage().contains("saved and verified"));}
        assertEquals(List.of("core"),f.persistent);assertEquals(1,f.writes);
    }
    @Test public void persistentPropertyUsesNewlinesAndUnicode() {
        assertEquals(List.of("Ядро","Продукт"),EdtProjectContextService.parsePersistent("Ядро\r\nПродукт\n"));
        assertTrue(EdtProjectContextService.parsePersistent(null).isEmpty());
    }
}
