package com.codepilot1c.core.tools;

import static org.junit.Assert.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;
import com.codepilot1c.core.edt.runtime.EdtExtensionUpdateService;
import com.codepilot1c.core.edt.runtime.EdtExtensionUpdateService.Target;
import com.codepilot1c.core.edt.runtime.EdtExtensionUpdateServiceTest;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.tools.workspace.EdtUpdateExtensionTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EdtUpdateExtensionToolTest {
    static class Service extends EdtExtensionUpdateService {
        Target target=new Target(EdtExtensionUpdateServiceTest.project("Base.Extension",true),
                EdtExtensionUpdateServiceTest.project("Base",true),EdtExtensionUpdateServiceTest.infobase("File=test;"),"unit-binding");
        int updates;boolean updated=true;Exception error;boolean keep,override;
        CountDownLatch entered,release;
        @Override public Target resolve(String extension,String base){return target;}
        @Override public boolean update(Target t,boolean k,boolean o,IProgressMonitor monitor) throws Exception {
            updates++;keep=k;override=o;
            if(entered!=null){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}
            if(error!=null)throw error;return updated;
        }
    }
    static JsonObject json(ToolResult result){return JsonParser.parseString(result.isSuccess()?result.getContent():result.getErrorMessage()).getAsJsonObject();}
    static BackgroundJobRegistry.JobStatus terminal(BackgroundJobRegistry registry,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until){var s=registry.getStatus(id).orElseThrow();if(s.getState()==BackgroundJobRegistry.JobState.DONE||s.getState()==BackgroundJobRegistry.JobState.FAILED)return s;Thread.sleep(10);}
        throw new AssertionError("No completion status");
    }
    @Test public void dryRunNeverReloadsOrQueues() {
        var service=new Service();var registry=BackgroundJobRegistry.createForTest();
        try {var result=new EdtUpdateExtensionTool(service,registry).execute(Map.of("extension_project","Base.Extension","dry_run",true)).join();
            assertTrue(result.isSuccess());assertFalse(json(result).get("updated").getAsBoolean());assertEquals(0,service.updates);assertEquals(0,registry.size());
            assertFalse(result.getContent().contains("File="));assertEquals("Base",json(result).get("base_project").getAsString());
        } finally {registry.shutdown();}
    }
    @Test public void syncSuccessReportsActualReload() {
        var service=new Service();var registry=BackgroundJobRegistry.createForTest();
        try {var result=new EdtUpdateExtensionTool(service,registry).execute(Map.of("extension_project","Base.Extension","async",false)).join();
            assertTrue(result.isSuccess());assertTrue(json(result).get("updated").getAsBoolean());assertEquals(1,service.updates);assertFalse(service.keep);assertFalse(service.override);
        } finally {registry.shutdown();}
    }
    @Test public void asyncIsDefaultAndReportsCompletion() throws Exception {
        var service=new Service();service.entered=new CountDownLatch(1);service.release=new CountDownLatch(1);var registry=BackgroundJobRegistry.createForTest();
        try {var result=new EdtUpdateExtensionTool(service,registry).execute(Map.of("extension_project","Base.Extension")).join();
            assertTrue(result.isSuccess());assertTrue(service.entered.await(5,TimeUnit.SECONDS));assertFalse(json(result).get("updated").getAsBoolean());
            String id=json(result).get("job_id").getAsString();service.release.countDown();var status=terminal(registry,id);
            assertEquals(BackgroundJobRegistry.JobState.DONE,status.getState());assertTrue(JsonParser.parseString(status.getResult()).getAsJsonObject().get("updated").getAsBoolean());
        } finally {service.release.countDown();registry.shutdown();}
    }
    @Test public void asyncFalseResultIsFailedJob() throws Exception {
        var service=new Service();service.updated=false;var registry=BackgroundJobRegistry.createForTest();
        try {var result=new EdtUpdateExtensionTool(service,registry).execute(Map.of("extension_project","Base.Extension")).join();
            assertEquals(BackgroundJobRegistry.JobState.FAILED,terminal(registry,json(result).get("job_id").getAsString()).getState());
        } finally {registry.shutdown();}
    }
    @Test public void syncFalseResultFails() {
        var service=new Service();service.updated=false;var registry=BackgroundJobRegistry.createForTest();
        try {var result=new EdtUpdateExtensionTool(service,registry).execute(Map.of("extension_project","Base.Extension","async",false)).join();
            assertFalse(result.isSuccess());assertFalse(json(result).get("updated").getAsBoolean());
        } finally {registry.shutdown();}
    }
    @Test public void simultaneousLoadsIntoSameInfobaseAreRejectedAndLockIsReleased() throws Exception {
        var service=new Service();service.entered=new CountDownLatch(1);service.release=new CountDownLatch(1);var registry=BackgroundJobRegistry.createForTest();
        try {var tool=new EdtUpdateExtensionTool(service,registry);var first=tool.execute(Map.of("extension_project","Base.Extension")).join();
            assertTrue(service.entered.await(5,TimeUnit.SECONDS));var second=tool.execute(Map.of("extension_project","Base.Extension")).join();
            assertFalse(second.isSuccess());assertEquals(1,service.updates);service.release.countDown();terminal(registry,json(first).get("job_id").getAsString());
            var again=tool.execute(Map.of("extension_project","Base.Extension","async",false)).join();assertTrue(again.isSuccess());assertEquals(2,service.updates);
        } finally {service.release.countDown();registry.shutdown();}
    }
    @Test public void exceptionCannotExposeCredentialsAndFailureReleasesLock() {
        var service=new Service();service.error=new Exception("File=test;Pwd=secret; /Pcredential");var registry=BackgroundJobRegistry.createForTest();
        try {var tool=new EdtUpdateExtensionTool(service,registry);var failed=tool.execute(Map.of("extension_project","Base.Extension","async",false)).join();
            assertFalse(failed.isSuccess());assertFalse(failed.getErrorMessage().contains("secret"));assertFalse(failed.getErrorMessage().contains("credential"));
            service.error=null;assertTrue(tool.execute(Map.of("extension_project","Base.Extension","async",false)).join().isSuccess());
        } finally {registry.shutdown();}
    }
    @Test public void stringFlagsReachRuntime() {
        var service=new Service();var registry=BackgroundJobRegistry.createForTest();
        try {var result=new EdtUpdateExtensionTool(service,registry).execute(Map.of("extension_project","Base.Extension","async","false","keep_connected","true","allow_conflict_override","true")).join();
            assertTrue(result.isSuccess());assertTrue(service.keep);assertTrue(service.override);
        } finally {registry.shutdown();}
    }
}
