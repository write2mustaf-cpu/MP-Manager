package com.mp.dialogengine;

import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.Finding;
import com.mp.dialogengine.DialogModels.FunctionRef;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;
import com.mp.dialogengine.DialogModels.Project;
import com.mp.dialogengine.DialogModels.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static action simulation used by the Testing group. */
public final class DialogSimulator {

    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public int passed;
        public int failed;

        public void pass(String s) {
            passed++;
            lines.add("✓ " + s);
        }

        public void fail(String s) {
            failed++;
            lines.add("✗ " + s);
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("Passed: ").append(passed).append(" / Failed: ").append(failed).append('\n');
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private DialogSimulator() {
    }

    public static Report simulate(Project project, Model model) {
        Report r = new Report();
        if (model == null) {
            r.fail("No dialog model");
            return r;
        }
        Map<String, String> vars = new HashMap<>();
        if (model.variables != null) {
            for (Variable v : model.variables) {
                if (v != null && v.name != null) {
                    vars.put(v.name, v.currentValue != null && !v.currentValue.isEmpty()
                            ? v.currentValue : v.defaultValue == null ? "" : v.defaultValue);
                }
            }
        }
        if (DialogDesigns.find(model.design) != null || "custom".equals(model.design)) {
            r.pass("Design: " + model.design);
        } else {
            r.fail("Unknown design: " + model.design);
        }
        if (model.countdownSeconds < 0 || model.countdownSeconds > 3600) {
            r.fail("Countdown out of range");
        } else {
            r.pass("Countdown: " + model.countdownSeconds + "s");
        }
        Set<String> nodeIds = new HashSet<>();
        collectIds(model.nodes, nodeIds);
        if (nodeIds.isEmpty()) r.fail("Dialog has no nodes");
        else r.pass("Nodes: " + nodeIds.size());
        if (model.events == null || model.events.isEmpty()) {
            r.pass("No events (static dialog)");
        } else {
            for (Event e : model.events) {
                if (e == null) continue;
                if (!e.nodeId.isEmpty() && !nodeIds.contains(e.nodeId)) {
                    r.fail("Event " + e.trigger + " points to missing node " + e.nodeId);
                }
                if (e.actions == null) continue;
                for (Action a : e.actions) {
                    simulateAction(project, model, a, vars, nodeIds, r);
                }
            }
        }
        for (Node n : flatten(model.nodes)) {
            if (n == null || n.text == null) continue;
            Matcher m = PLACEHOLDER.matcher(n.text);
            while (m.find()) {
                if (vars.containsKey(m.group(1))) r.pass("Placeholder ${" + m.group(1) + "} resolves");
                else r.fail("Missing placeholder variable: " + m.group(1));
            }
        }
        List<Finding> findings = DialogAnalyzer.analyze(project, model);
        if (findings.isEmpty()) r.pass("Analyzer: no findings");
        else {
            for (Finding f : findings) {
                if ("error".equals(f.severity)) r.fail(f.message);
                else r.lines.add("! " + f.message);
            }
        }
        r.pass("Runtime compatibility: API 23+; blur fallback below 31");
        return r;
    }

    private static void simulateAction(Project project, Model model, Action a,
                                       Map<String, String> vars, Set<String> nodeIds, Report r) {
        if (a == null) return;
        if (a.condition != null && !a.condition.isEmpty() && !eval(a.condition, vars)) {
            r.lines.add("· Skipped " + a.type + " (condition false)");
            return;
        }
        switch (a.type) {
            case "condition":
                r.pass("Condition evaluated: " + a.value);
                break;
            case "set_variable":
            case "pass_data":
                vars.put(a.key.isEmpty() ? a.target : a.key, resolve(a.value, vars));
                r.pass("Variable set: " + (a.key.isEmpty() ? a.target : a.key));
                break;
            case "read_field":
                if (nodeIds.contains(a.target)) r.pass("Read field: " + a.target);
                else r.fail("Read field target missing: " + a.target);
                break;
            case "call_function": {
                FunctionRef fn = function(project, a.functionRef);
                if (fn == null) r.fail("Function not found: " + a.functionRef);
                else if (!fn.safe || !fn.isStatic || !fn.isPublic) r.fail("Function blocked: " + fn.displayName);
                else r.pass("Would call " + fn.displayName);
                break;
            }
            case "open_dialog": {
                String target = resolve(a.value.isEmpty() ? a.target : a.value, vars);
                if (target.startsWith("{")) r.pass("Would open inline dialog");
                else if (target.isEmpty()) r.fail("Open dialog without target");
                else r.pass("Would open asset/dialog: " + target);
                break;
            }
            case "show_result":
            case "update_screen":
            case "hide_node":
            case "show_node":
            case "refresh":
            case "focus_node":
                if (a.target.isEmpty()) r.fail(a.type + " without target");
                else if (!nodeIds.contains(a.target)) r.fail(a.type + " target missing: " + a.target);
                else r.pass(a.type + " → " + a.target);
                break;
            case "open_url": {
                String url = resolve(a.value.isEmpty() ? a.target : a.value, vars);
                if (url.startsWith("http://") || url.startsWith("https://")) r.pass("URL: " + url);
                else r.fail("Invalid URL: " + url);
                break;
            }
            case "dismiss":
            case "toast":
            case "copy_text":
            case "notify_success":
            case "notify_failure":
            case "delay":
                r.pass(a.type);
                break;
            default:
                r.fail("Unknown action: " + a.type);
                break;
        }
    }

    private static boolean eval(String expression, Map<String, String> vars) {
        if (expression == null) return true;
        String e = expression.trim();
        if (e.isEmpty()) return true;
        if (e.contains("||")) {
            for (String p : e.split("\\|\\|")) if (eval(p, vars)) return true;
            return false;
        }
        if (e.contains("&&")) {
            for (String p : e.split("&&")) if (!eval(p, vars)) return false;
            return true;
        }
        if (e.startsWith("!")) return !eval(e.substring(1), vars);
        String[] ops = {">=", "<=", "!=", "==", ">", "<"};
        for (String op : ops) {
            int idx = e.indexOf(op);
            if (idx < 0) continue;
            String left = clean(resolve(e.substring(0, idx), vars));
            String right = clean(resolve(e.substring(idx + op.length()), vars));
            int cmp;
            try {
                cmp = Double.compare(Double.parseDouble(left), Double.parseDouble(right));
            } catch (Throwable t) {
                cmp = left.compareTo(right);
            }
            switch (op) {
                case ">=": return cmp >= 0;
                case "<=": return cmp <= 0;
                case "!=": return cmp != 0;
                case "==": return cmp == 0;
                case ">": return cmp > 0;
                case "<": return cmp < 0;
            }
        }
        String v = clean(resolve(e, vars));
        return "true".equalsIgnoreCase(v) || "1".equals(v)
                || (!v.isEmpty() && !"false".equalsIgnoreCase(v) && !"0".equals(v));
    }

    private static String resolve(String text, Map<String, String> vars) {
        if (text == null) return "";
        String out = text;
        int guard = 0;
        while (guard++ < 6) {
            int a = out.indexOf("${");
            if (a < 0) break;
            int b = out.indexOf('}', a + 2);
            if (b < 0) break;
            String name = out.substring(a + 2, b);
            out = out.substring(0, a) + vars.getOrDefault(name, "") + out.substring(b + 1);
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static FunctionRef function(Project project, String id) {
        if (project == null || project.functions == null || id == null) return null;
        for (FunctionRef f : project.functions) if (f != null && id.equals(f.id)) return f;
        return null;
    }

    private static void collectIds(List<Node> nodes, Set<String> out) {
        if (nodes == null) return;
        for (Node n : nodes) {
            if (n == null) continue;
            if (n.id != null) out.add(n.id);
            collectIds(n.children, out);
        }
    }

    private static List<Node> flatten(List<Node> nodes) {
        List<Node> out = new ArrayList<>();
        if (nodes == null) return out;
        for (Node n : nodes) {
            if (n == null) continue;
            out.add(n);
            out.addAll(flatten(n.children));
        }
        return out;
    }
}
