package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class YaxunitRunContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void exactParameterizedCasesBecomeRegisteredMethodFilters() {
        var selection = YaxunitRunContract.selection(null,
                List.of("ТестыБанка.Проверка(Дата, 01.01.2020)", "ТестыБанка.Проверка(Адрес, example.org)"), null, 2);
        assertEquals(2, selection.expected().size());
        assertEquals(1, selection.filter().getAsJsonArray("tests").size());
        assertEquals("ТестыБанка.Проверка", selection.filter().getAsJsonArray("tests").get(0).getAsString());
    }
    @Test public void invalidOrDuplicateCasesAreRefused() {
        for (Object value : List.of(List.of(), List.of("Tests.One", "Tests.One"), List.of("One"),
                List.of("Tests.One.extra"), List.of("Tests.One(x)trailing"), List.of(123))) {
            assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.selection(null,value,null,null));
        }
    }
    @Test public void incompatibleModuleFilterIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.selection("Other",List.of("Tests.One"),null,null));
    }
    @Test public void malformedCountsAndContextsAreRefused() {
        for (Object count : List.of(0,-1,1.5,"2",3)) {
            assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.selection(null,List.of("Tests.One"),null,count));
        }
        assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.selection(null,List.of("Tests.One"),Map.of(),null));
        assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.selection(null,List.of("Tests.One"),Map.of("Tests.One","Other"),null));
    }
    @Test public void missingModesAndRepeatedStartupCannotLaunch() throws Exception {
        File config=temporary.newFile("config.json");
        String startup="RunUnitTests="+config.getAbsolutePath();
        for (List<String> command : List.of(List.of("1cv8","/C",startup),
                List.of("1cv8","/TESTMANAGER","/C",startup),
                List.of("1cv8","/TESTMANAGER","/RunModeManagedApplication","/C",startup,"/C","Other"),
                List.of("1cv8","/TESTMANAGER","/RunModeManagedApplication","/RunModeOrdinaryApplication","/C",startup))) {
            assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.validateCommand(command,config));
        }
        YaxunitRunContract.validateCommand(List.of("1cv8","/TESTMANAGER","/RunModeManagedApplication","/C",startup),config);
    }
    @Test public void nonAsciiStartupPathCannotLaunch() throws Exception {
        File config=new File(temporary.newFolder("Проверка"),"config.json");
        assertFalse(YaxunitRunContract.isAscii(config.toPath()));
        assertThrows(IllegalArgumentException.class, () -> YaxunitRunContract.validateCommand(List.of("1cv8",
                "/TESTMANAGER","/RunModeManagedApplication","/C","RunUnitTests="+config.getAbsolutePath()),config));
    }
    private List<String> verify(String body, List<String> expected, Map<String,String> contexts, int count) throws Exception {
        File xml=temporary.newFile();
        Files.writeString(xml.toPath(),"<testsuite>"+body+"</testsuite>",StandardCharsets.UTF_8);
        return YaxunitRunContract.verifyReport(xml,YaxunitRunContract.selection(null,expected,contexts,null),count);
    }
    @Test public void exactActualCaseAndContextAreRequired() throws Exception {
        String body="<testcase classname='Tests.One' name='One' context='Сервер'/>";
        assertTrue(verify(body,List.of("Tests.One"),Map.of("Tests.One","Сервер"),1).isEmpty());
        assertFalse(verify(body,List.of("Tests.Two"),null,1).isEmpty());
        assertFalse(verify(body,List.of("Tests.One"),Map.of("Tests.One","КлиентУправляемоеПриложение"),1).isEmpty());
        assertFalse(verify(body,List.of("Tests.One"),null,999).isEmpty());
    }
    @Test public void zeroDuplicateFailedAndSkippedCasesAreNotSuccess() throws Exception {
        assertFalse(verify("",List.of("Tests.One"),null,0).isEmpty());
        String body="<testcase classname='Tests.One' name='One'/>";
        assertFalse(verify(body+body,List.of("Tests.One"),null,2).isEmpty());
        for (String tag:List.of("error","failure","skipped")) {
            assertFalse(verify("<testcase classname='Tests.One' name='One'><"+tag+"/></testcase>",List.of("Tests.One"),null,1).isEmpty());
        }
    }
    @Test public void xmlExternalEntityIsRefused() throws Exception {
        File xml=temporary.newFile();
        Files.writeString(xml.toPath(),"<!DOCTYPE t [<!ENTITY x SYSTEM 'file:///not-readable'>]><testsuite>&x;</testsuite>");
        assertThrows(Exception.class, () -> YaxunitRunContract.verifyReport(xml,YaxunitRunContract.selection(null,null,null,null),0));
    }
}
