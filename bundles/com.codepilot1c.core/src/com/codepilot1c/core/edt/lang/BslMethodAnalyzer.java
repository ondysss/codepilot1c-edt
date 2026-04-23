package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.Block;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.IfStatement;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.LoopStatement;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.TryExceptStatement;

/**
 * Typed structural analyzer for one BSL method.
 */
final class BslMethodAnalyzer {

    AnalysisSnapshot analyze(Method method, int startLine, int endLine) {
        int loc = Math.max(1, endLine - startLine + 1);
        int cyclomatic = 1;
        int branches = 0;
        int loops = 0;
        int tryExcepts = 0;
        List<BslMethodAnalysisResult.WarningItem> warnings = new ArrayList<>();
        List<BslMethodAnalysisResult.CallSite> serverCallsInLoops = new ArrayList<>();
        Set<String> warningKeys = new LinkedHashSet<>();
        Set<String> loopCallKeys = new LinkedHashSet<>();

        for (Statement statement : method.allStatements()) {
            if (statement instanceof IfStatement ifStatement) {
                branches++;
                cyclomatic += 1 + ifStatement.getElsIfParts().size();
            }
            if (statement instanceof LoopStatement loopStatement) {
                loops++;
                cyclomatic++;
                collectServerCallsInLoop(loopStatement, serverCallsInLoops, warnings, warningKeys, loopCallKeys);
            }
            if (statement instanceof TryExceptStatement tryExceptStatement) {
                tryExcepts++;
                cyclomatic++;
                if (tryExceptStatement.getExceptStatements().isEmpty()) {
                    addWarning(
                            warnings,
                            warningKeys,
                            "EMPTY_EXCEPT", //$NON-NLS-1$
                            "Empty EXCEPT block hides errors without handling them.", //$NON-NLS-1$
                            lineOf(tryExceptStatement));
                }
            }
        }

        Set<String> usedParams = collectUsedParams(method);
        List<String> unusedParams = new ArrayList<>();
        for (FormalParam param : method.getFormalParams()) {
            String name = param.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!usedParams.contains(normalize(name))) {
                unusedParams.add(name);
                addWarning(
                        warnings,
                        warningKeys,
                        "UNUSED_PARAMETER:" + normalize(name), //$NON-NLS-1$
                        "Parameter is declared but never used: " + name, //$NON-NLS-1$
                        lineOf(param));
            }
        }

        return new AnalysisSnapshot(
                loc,
                cyclomatic,
                branches,
                loops,
                tryExcepts,
                unusedParams,
                serverCallsInLoops,
                toMethodRefs(method.getCallees()),
                toMethodRefs(method.getCallers()),
                warnings);
    }

    private void collectServerCallsInLoop(
            LoopStatement loopStatement,
            List<BslMethodAnalysisResult.CallSite> callSites,
            List<BslMethodAnalysisResult.WarningItem> warnings,
            Set<String> warningKeys,
            Set<String> loopCallKeys) {
        TreeIterator<EObject> iterator = loopStatement.eAllContents();
        while (iterator.hasNext()) {
            EObject next = iterator.next();
            if (!(next instanceof Invocation invocation) || !invocation.isIsServerCall()) {
                continue;
            }
            String name = invocationName(invocation);
            int line = lineOf(invocation);
            String key = normalize(name) + ":" + line; //$NON-NLS-1$
            if (!loopCallKeys.add(key)) {
                continue;
            }
            callSites.add(new BslMethodAnalysisResult.CallSite(name, line));
            addWarning(
                    warnings,
                    warningKeys,
                    "SERVER_CALL_IN_LOOP:" + key, //$NON-NLS-1$
                    "Server call inside loop may cause performance issues: " + name, //$NON-NLS-1$
                    line);
        }
    }

    private Set<String> collectUsedParams(Method method) {
        Set<String> used = new LinkedHashSet<>();
        TreeIterator<EObject> iterator = method.eAllContents();
        while (iterator.hasNext()) {
            EObject next = iterator.next();
            if (!(next instanceof StaticFeatureAccess staticFeatureAccess)) {
                continue;
            }
            for (FeatureEntry featureEntry : staticFeatureAccess.getFeatureEntries()) {
                EObject feature = featureEntry.getFeature();
                if (feature instanceof FormalParam param && param.getName() != null) {
                    used.add(normalize(param.getName()));
                }
            }
        }
        return used;
    }

    private List<BslMethodAnalysisResult.MethodRef> toMethodRefs(List<? extends EObject> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<BslMethodAnalysisResult.MethodRef> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EObject block : blocks) {
            Method method = asMethod(block);
            if (method == null || method.getName() == null || method.getName().isBlank()) {
                continue;
            }
            int startLine = lineOf(method);
            int endLine = endLineOf(method);
            String key = normalize(method.getName()) + ":" + startLine; //$NON-NLS-1$
            if (!seen.add(key)) {
                continue;
            }
            result.add(new BslMethodAnalysisResult.MethodRef(
                    method.getName(),
                    method instanceof Procedure ? "procedure" : "function", //$NON-NLS-1$ //$NON-NLS-2$
                    startLine,
                    endLine));
        }
        return result;
    }

    private Method asMethod(EObject candidate) {
        EObject current = candidate;
        while (current != null) {
            if (current instanceof Method method) {
                return method;
            }
            current = current.eContainer();
        }
        return null;
    }

    private void addWarning(
            List<BslMethodAnalysisResult.WarningItem> warnings,
            Set<String> warningKeys,
            String code,
            String message,
            int line) {
        if (!warningKeys.add(code)) {
            return;
        }
        warnings.add(new BslMethodAnalysisResult.WarningItem(code, message, line));
    }

    private String invocationName(Invocation invocation) {
        EObject access = invocation.getMethodAccess();
        if (access instanceof StaticFeatureAccess staticFeatureAccess) {
            List<FeatureEntry> entries = staticFeatureAccess.getFeatureEntries();
            for (int i = entries.size() - 1; i >= 0; i--) {
                EObject feature = entries.get(i).getFeature();
                if (feature instanceof Method method && method.getName() != null && !method.getName().isBlank()) {
                    return method.getName();
                }
                if (feature instanceof FormalParam param && param.getName() != null && !param.getName().isBlank()) {
                    return param.getName();
                }
            }
        }
        INode node = NodeModelUtils.findActualNodeFor(invocation);
        if (node != null) {
            String text = NodeModelUtils.getTokenText(node).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "Invocation"; //$NON-NLS-1$
    }

    private int lineOf(EObject object) {
        INode node = NodeModelUtils.findActualNodeFor(object);
        return node != null ? node.getStartLine() : 0;
    }

    private int endLineOf(EObject object) {
        INode node = NodeModelUtils.findActualNodeFor(object);
        return node != null ? node.getEndLine() : lineOf(object);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    record AnalysisSnapshot(
            int loc,
            int cyclomatic,
            int branches,
            int loops,
            int tryExcepts,
            List<String> unusedParams,
            List<BslMethodAnalysisResult.CallSite> serverCallsInLoops,
            List<BslMethodAnalysisResult.MethodRef> callees,
            List<BslMethodAnalysisResult.MethodRef> callers,
            List<BslMethodAnalysisResult.WarningItem> warnings) {
    }
}
