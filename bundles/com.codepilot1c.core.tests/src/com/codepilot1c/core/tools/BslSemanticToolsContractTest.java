package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.lang.BslMethodAnalysisRequest;
import com.codepilot1c.core.edt.lang.BslMethodAnalysisResult;
import com.codepilot1c.core.edt.lang.BslMethodBodyResult;
import com.codepilot1c.core.edt.lang.BslMethodCandidate;
import com.codepilot1c.core.edt.lang.BslMethodInfo;
import com.codepilot1c.core.edt.lang.BslMethodLookupException;
import com.codepilot1c.core.edt.lang.BslMethodParamInfo;
import com.codepilot1c.core.edt.lang.BslModuleContextResult;
import com.codepilot1c.core.edt.lang.BslModuleExportsResult;
import com.codepilot1c.core.edt.lang.BslModuleMethodsResult;
import com.codepilot1c.core.edt.lang.BslModuleRequest;
import com.codepilot1c.core.edt.lang.BslPositionRequest;
import com.codepilot1c.core.edt.lang.BslScopeMembersRequest;
import com.codepilot1c.core.edt.lang.BslScopeMembersResult;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.codepilot1c.core.edt.lang.BslSymbolResult;
import com.codepilot1c.core.edt.lang.BslTypeResult;
import com.codepilot1c.core.tools.bsl.BslAnalyzeMethodTool;
import com.codepilot1c.core.tools.bsl.BslGetMethodBodyTool;
import com.codepilot1c.core.tools.bsl.BslListMethodsTool;
import com.codepilot1c.core.tools.bsl.BslModuleContextTool;
import com.codepilot1c.core.tools.bsl.BslModuleExportsTool;
import com.codepilot1c.core.tools.bsl.BslSymbolAtPositionTool;
import com.codepilot1c.core.tools.bsl.BslTypeAtPositionTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BslSemanticToolsContractTest {

    @Test
    public void listMethodsSerializesUsedFlagAndDocumentation() {
        FakeBslSemanticService service = new FakeBslSemanticService();
        service.methodsResult = new BslModuleMethodsResult(
                "DemoConfiguration", //$NON-NLS-1$
                "CommonModules/Orders/Module.bsl", //$NON-NLS-1$
                1,
                false,
                List.of(sampleMethod(true)));

        ToolResult result = new BslListMethodsTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        JsonObject item = json.getAsJsonArray("items").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals(ToolResult.ToolResultType.SEARCH_RESULTS, result.getType());
        assertTrue(item.get("used").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Документация", item.get("documentation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void getMethodBodySerializesAmbiguousCandidates() {
        FakeBslSemanticService service = new FakeBslSemanticService();
        service.failure = new BslMethodLookupException(
                EdtAstErrorCode.AMBIGUOUS_METHOD,
                "Ambiguous method", //$NON-NLS-1$
                true,
                List.of(new BslMethodCandidate("Провести", "procedure", 10, 20))); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = new BslGetMethodBodyTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "Провести" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertFalse(result.isSuccess());
        assertEquals("AMBIGUOUS_METHOD", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, json.getAsJsonArray("candidates").size()); //$NON-NLS-1$
    }

    @Test
    public void symbolAndTypeToolsReturnStructuredJson() {
        FakeBslSemanticService service = new FakeBslSemanticService();
        service.symbolResult = new BslSymbolResult(
                "DemoConfiguration", "CommonModules/Orders/Module.bsl", 12, 8, 120, //$NON-NLS-1$ //$NON-NLS-2$
                "variable", "Сумма", "Сумма", "Variable", "uri://symbol", "Method", "Провести", 12, 8, 12, 13); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        service.typeResult = new BslTypeResult(
                "DemoConfiguration", "CommonModules/Orders/Module.bsl", 12, 8, 120, "Variable", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new BslTypeResult.TypeInfo("Number", "Число", "number"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        ToolResult symbol = new BslSymbolAtPositionTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
                "line", 12, //$NON-NLS-1$
                "column", 8 //$NON-NLS-1$
        )).join();
        ToolResult type = new BslTypeAtPositionTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
                "line", 12, //$NON-NLS-1$
                "column", 8 //$NON-NLS-1$
        )).join();

        JsonObject symbolJson = JsonParser.parseString(symbol.getContent()).getAsJsonObject();
        JsonObject typeJson = JsonParser.parseString(type.getContent()).getAsJsonObject();
        assertEquals("variable", symbolJson.get("symbolKind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number", typeJson.getAsJsonArray("types").get(0).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleContextAndExportsReturnStructuredPayloads() {
        FakeBslSemanticService service = new FakeBslSemanticService();
        service.moduleContextResult = new BslModuleContextResult(
                "DemoConfiguration", //$NON-NLS-1$
                "CommonModules/Orders/Module.bsl", //$NON-NLS-1$
                "COMMON MODULE", "CommonModule", "Orders", "uri://owner", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of("AtServer"), 3, 1, 1, 0); //$NON-NLS-1$
        service.moduleExportsResult = new BslModuleExportsResult(
                "DemoConfiguration", //$NON-NLS-1$
                "CommonModules/Orders/Module.bsl", //$NON-NLS-1$
                1,
                false,
                List.of(sampleMethod(false)));

        ToolResult context = new BslModuleContextTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();
        ToolResult exports = new BslModuleExportsTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        JsonObject contextJson = JsonParser.parseString(context.getContent()).getAsJsonObject();
        JsonObject exportsJson = JsonParser.parseString(exports.getContent()).getAsJsonObject();
        assertEquals("COMMON MODULE", contextJson.get("moduleType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, contextJson.get("exportedMethods").getAsInt()); //$NON-NLS-1$
        assertEquals(1, exportsJson.get("total").getAsInt()); //$NON-NLS-1$
        assertEquals("ПровестиЗаказ", exportsJson.getAsJsonArray("items").get(0).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void analyzeMethodReturnsStructuredMetricsAndWarnings() {
        FakeBslSemanticService service = new FakeBslSemanticService();
        service.methodAnalysisResult = new BslMethodAnalysisResult(
                "DemoConfiguration", //$NON-NLS-1$
                "CommonModules/Orders/Module.bsl", //$NON-NLS-1$
                "ПровестиЗаказ", //$NON-NLS-1$
                "procedure", //$NON-NLS-1$
                10,
                30,
                21,
                4,
                1,
                1,
                1,
                List.of("Ссылка"), //$NON-NLS-1$
                List.of(new BslMethodAnalysisResult.CallSite("ВыполнитьНаСервере", 18)), //$NON-NLS-1$
                List.of(new BslMethodAnalysisResult.MethodRef("Проверить", "procedure", 40, 48)), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(new BslMethodAnalysisResult.MethodRef("ПровестиДокумент", "procedure", 80, 92)), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(new BslMethodAnalysisResult.WarningItem("SERVER_CALL_IN_LOOP", "Server call inside loop", 18))); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = new BslAnalyzeMethodTool(service).execute(Map.of(
                "projectName", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "filePath", "CommonModules/Orders/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
                "methodName", "ПровестиЗаказ" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(result.isSuccess());
        assertEquals(4, json.get("cyclomatic").getAsInt()); //$NON-NLS-1$
        assertEquals("Ссылка", json.getAsJsonArray("unusedParams").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SERVER_CALL_IN_LOOP", json.getAsJsonArray("warnings").get(0).getAsJsonObject().get("code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static BslMethodInfo sampleMethod(boolean used) {
        return new BslMethodInfo(
                "ПровестиЗаказ", //$NON-NLS-1$
                "procedure", //$NON-NLS-1$
                10,
                20,
                true,
                false,
                false,
                used,
                List.of(new BslMethodParamInfo("Ссылка", true, null)), //$NON-NLS-1$
                List.of("AtServer"), //$NON-NLS-1$
                "Документация"); //$NON-NLS-1$
    }

    private static final class FakeBslSemanticService extends BslSemanticService {
        private RuntimeException failure;
        private BslSymbolResult symbolResult;
        private BslTypeResult typeResult;
        private BslScopeMembersResult scopeResult;
        private BslModuleMethodsResult methodsResult;
        private BslMethodBodyResult methodBodyResult;
        private BslMethodAnalysisResult methodAnalysisResult;
        private BslModuleContextResult moduleContextResult;
        private BslModuleExportsResult moduleExportsResult;

        @Override
        public BslSymbolResult getSymbolAtPosition(BslPositionRequest request) {
            return returnOrThrow(symbolResult);
        }

        @Override
        public BslTypeResult getTypeAtPosition(BslPositionRequest request) {
            return returnOrThrow(typeResult);
        }

        @Override
        public BslScopeMembersResult getScopeMembers(BslScopeMembersRequest request) {
            return returnOrThrow(scopeResult);
        }

        @Override
        public BslModuleMethodsResult listMethods(com.codepilot1c.core.edt.lang.BslModuleMethodsRequest request) {
            return returnOrThrow(methodsResult);
        }

        @Override
        public BslMethodBodyResult getMethodBody(com.codepilot1c.core.edt.lang.BslMethodBodyRequest request) {
            return returnOrThrow(methodBodyResult);
        }

        @Override
        public BslMethodAnalysisResult analyzeMethod(BslMethodAnalysisRequest request) {
            return returnOrThrow(methodAnalysisResult);
        }

        @Override
        public BslModuleContextResult getModuleContext(BslModuleRequest request) {
            return returnOrThrow(moduleContextResult);
        }

        @Override
        public BslModuleExportsResult getModuleExports(com.codepilot1c.core.edt.lang.BslModuleMethodsRequest request) {
            return returnOrThrow(moduleExportsResult);
        }

        private <T> T returnOrThrow(T value) {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
