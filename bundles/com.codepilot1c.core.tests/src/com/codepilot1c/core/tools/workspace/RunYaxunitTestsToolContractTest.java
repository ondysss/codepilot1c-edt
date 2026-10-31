package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.*;
import com.codepilot1c.core.tools.*;
import com.google.gson.*;

/** The real MCP tool must reject a plausible but wrong or empty successful process result. */
public class RunYaxunitTestsToolContractTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private File workspace;
    private boolean started;
    private JsonObject capturedConfig;
    private CountDownLatch startedSignal;
    private CountDownLatch releaseSignal;
    private String previousFactory;
    @Before public void setup() throws Exception {
        workspace=temporary.newFolder("workspace");
        previousFactory=System.getProperty("javax.xml.parsers.DocumentBuilderFactory");
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory","com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");
    }
    @After public void restoreFactory() {
        if(previousFactory==null) System.clearProperty("javax.xml.parsers.DocumentBuilderFactory");
        else System.setProperty("javax.xml.parsers.DocumentBuilderFactory",previousFactory);
    }
    private ToolResult run(String body, int actualCount, Map<String,Object> arguments) throws Exception {
        EdtProjectResolver resolver=new EdtProjectResolver() {
            @Override public InfobaseReference resolveInfobase(String project,File root) {return null;}
        };
        EdtRuntimeService runtime=new EdtRuntimeService() {
            @Override public RuntimeExecutionCommandBuilder buildTestManagerCommand(String project,File epf,File va,
                    File root,boolean show,boolean quiet,boolean clear,File log) {
                return new RuntimeExecutionCommandBuilder(new File(root,"1cv8.exe"),ThickClientMode.ENTERPRISE);
            }
            public RuntimeExecutionCommandBuilder buildTestManagerCommand(String project,File epf,File va,
                    File root,boolean show,boolean quiet,boolean clear,File log,String version,
                    AccessSettings access,boolean feature) {
                assertFalse("YAxUnit must suppress the Vanessa startup option",feature);
                return new RuntimeExecutionCommandBuilder(new File(root,"1cv8.exe"),ThickClientMode.ENTERPRISE);
            }
        };
        RunYaxunitTestsTool tool=new RunYaxunitTestsTool(resolver,runtime,builder->{
            started=true;
            List<String> command=builder.command();
            String startup=command.get(command.indexOf("/C")+1);
            File config=new File(startup.substring("RunUnitTests=".length()));
            JsonObject json=JsonParser.parseString(Files.readString(config.toPath())).getAsJsonObject();
            capturedConfig=json;
            Files.writeString(new File(json.get("reportPath").getAsString()).toPath(),
                    "<testsuite tests='"+actualCount+"' failures='0' errors='0' skipped='0'>"+body+"</testsuite>",StandardCharsets.UTF_8);
            if (startedSignal != null) {
                startedSignal.countDown();
                return new FinishedProcess() {
                    @Override public boolean waitFor(long value,TimeUnit unit) throws InterruptedException {
                        return releaseSignal.await(value,unit);
                    }
                    @Override public boolean isAlive() {return releaseSignal.getCount()>0;}
                };
            }
            return new FinishedProcess();
        }) {
            @Override protected File getWorkspaceRoot() {return workspace;}
        };
        Map<String,Object> parameters=new LinkedHashMap<>(arguments);parameters.put("project_name","TestProject");
        return tool.doExecute(new ToolParameters(parameters)).get(10,TimeUnit.SECONDS);
    }
    @Test public void exactSelectionIsVerifiedByActualTool() throws Exception {
        ToolResult result=run("<testcase classname='Tests.One' name='One' context='Сервер'/>",1,
                Map.of("tests",List.of("Tests.One"),"expected_contexts",Map.of("Tests.One","Сервер")));
        assertTrue(result.getErrorMessage(),result.isSuccess());
        assertTrue(started);
        assertEquals("Tests.One",capturedConfig.getAsJsonObject("filter").getAsJsonArray("tests").get(0).getAsString());
    }
    @Test public void exitZeroWithWrongCaseIsToolFailure() throws Exception {
        ToolResult result=run("<testcase classname='Tests.Two' name='Two'/>",1,Map.of("tests",List.of("Tests.One")));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Missing test: Tests.One"));
    }
    @Test public void exitZeroWithNoCasesIsToolFailure() throws Exception {
        ToolResult result=run("",0,Map.of("tests",List.of("Tests.One")));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("No tests executed"));
    }
    @Test public void skippedCaseIsToolFailure() throws Exception {
        assertFalse(run("<testcase classname='Tests.One' name='One'><skipped/></testcase>",1,
                Map.of("tests",List.of("Tests.One"))).isSuccess());
    }
    @Test public void invalidSelectionCannotStartClient() throws Exception {
        ToolResult result=run("",0,Map.of("tests",List.of("Tests.One","Tests.One")));
        assertFalse(result.isSuccess());assertFalse(started);
    }
    @Test public void implicitUnfilteredRunCannotStartClient() throws Exception {
        assertFalse(run("",0,Map.of()).isSuccess());assertFalse(started);
    }
    @Test public void failedRunReleasesItsInfobase() throws Exception {
        assertFalse(run("",0,Map.of("tests",List.of("Tests.One"))).isSuccess());
        assertTrue(run("<testcase classname='Tests.One' name='One'/>",1,Map.of("tests",List.of("Tests.One"))).isSuccess());
    }
    @Test public void secondRunInSameInfobaseCannotStart() throws Exception {
        startedSignal=new CountDownLatch(1);releaseSignal=new CountDownLatch(1);
        CompletableFuture<ToolResult> first=CompletableFuture.supplyAsync(()->{
            try {return run("<testcase classname='Tests.One' name='One'/>",1,Map.of("tests",List.of("Tests.One")));}
            catch(Exception error){throw new RuntimeException(error);}
        });
        try {
            assertTrue(startedSignal.await(5,TimeUnit.SECONDS));
            ToolResult second=run("",0,Map.of("tests",List.of("Tests.One")));
            assertFalse(second.isSuccess());
            assertTrue(second.getErrorMessage().contains("already active"));
        } finally {releaseSignal.countDown();}
        assertTrue(first.get(10,TimeUnit.SECONDS).isSuccess());
    }
    private static class FinishedProcess extends Process {
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor(){return 0;}
        public boolean waitFor(long value,TimeUnit unit) throws InterruptedException {return true;}
        public int exitValue(){return 0;}
        public long pid(){return 424242;}
        public void destroy(){}
    }
}
