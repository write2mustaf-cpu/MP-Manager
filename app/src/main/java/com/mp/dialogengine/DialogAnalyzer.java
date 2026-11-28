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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

 /** Static validation for a dialog model. Pure Java so it is unit-testable. */
public final class DialogAnalyzer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Set<String> KINDS = new HashSet<>();
    private static final Set<String> TRIGGERS = new HashSet<>();
    private static final Set<String> ACTION_TYPES = new HashSet<>();

    static {
        KINDS.add("text");
        KINDS.add("title");
        KINDS.add("subtitle");
        KINDS.add("message");
        KINDS.add("image");
        KINDS.add("button");
        KINDS.add("input");
        KINDS.add("toggle");
        KINDS.add("list");
        KINDS.add("progress");
        KINDS.add("divider");
        KINDS.add("spacer");
        KINDS.add("card");
        KINDS.add("collapsible");
        KINDS.add("toolbar");
        KINDS.add("pages");

        TRIGGERS.add("click");
        TRIGGERS.add("long_click");
        TRIGGERS.add("double_click");
        TRIGGERS.add("open");
        TRIGGERS.add("close");
        TRIGGERS.add("confirm");
        TRIGGERS.add("cancel");
        TRIGGERS.add("success");
        TRIGGERS.add("failure");
        TRIGGERS.add("input_change");
        TRIGGERS.add("timeout");
        TRIGGERS.add("focus");
        TRIGGERS.add("selection");

        ACTION_TYPES.add("condition");
        ACTION_TYPES.add("read_field");
        ACTION_TYPES.add("call_function");
        ACTION_TYPES.add("pass_data");
        ACTION_TYPES.add("show_result");
        ACTION_TYPES.add("open_dialog");
        ACTION_TYPES.add("update_screen");
        ACTION_TYPES.add("notify_success");
        ACTION_TYPES.add("notify_failure");
        ACTION_TYPES.add("set_variable");
        ACTION_TYPES.add("dismiss");
        ACTION_TYPES.add("open_url");
        ACTION_TYPES.add("copy_text");
        ACTION_TYPES.add("toast");
        ACTION_TYPES.add("hide_node");
        ACTION_TYPES.add("show_node");
        ACTION_TYPES.add("focus_node");
        ACTION_TYPES.add("delay");
    }

    private DialogAnalyzer() {
    }

    public static List<Finding> analyze(Project project, Model model) {
        List<Finding> out = new ArrayList<>();
        if (model == null) return out;
        Map<String, Node> nodes = new HashMap<>();
        Map<String, Integer> nodeCounts = new HashMap<>();
        collectNodes(model.nodes, nodes, nodeCounts);
        for (Map.Entry<String, Integer> e : nodeCounts.entrySet()) {
            if (e.getValue() > 1) {
                Finding f = finding("error", "duplicate_node_id",
                        "معرّف العنصر مكرر: " + e.getKey(), true,
                        "إعادة تسمية المعرف المكرر وتحديث كل المراجع");
                f.nodeId = e.getKey();
                out.add(f);
            }
        }
        if (model.nodes == null || model.nodes.isEmpty()) {
            out.add(finding("error", "empty_dialog",
                    "الدايلوك لا يحتوي على أي عنصر.", false, ""));
        }
        if (DialogDesigns.find(model.design) == null && !"custom".equals(model.design)) {
            out.add(finding("warning", "unknown_design",
                    "التصميم غير معروف: " + model.design, true,
                    "الرجوع إلى تصميم Material"));
        }
        checkColor(model.backgroundColor, "خلفية الدايلوك", out);
        checkColor(model.borderColor, "حدود الدايلوك", out);
        if (model.countdownSeconds < 0 || model.countdownSeconds > 3600) {
            out.add(finding("warning", "bad_countdown",
                    "مدة الإغلاق التلقائي خارج النطاق المسموح.", true,
                    "تقييد القيمة بين 0 و3600 ثانية"));
        }
        if (model.widthDp < 0 || model.widthDp > 4096) {
            out.add(finding("warning", "bad_width",
                    "عرض الدايلوك غير صالح: " + model.widthDp, true,
                    "تقييد العرض بين 0 و4096"));
        }
        if (model.cornerRadiusDp < 0 || model.cornerRadiusDp > 1000) {
            out.add(finding("warning", "bad_radius",
                    "نصف قطر الزوايا غير صالح.", true, "تقييد القيمة بين 0 و1000"));
        }
        Set<String> eventIds = new HashSet<>();
        Map<String, Integer> eventCounts = new HashMap<>();
        if (model.events != null) {
            for (Event e : model.events) {
                if (e == null) continue;
                eventCounts.merge(e.id, 1, Integer::sum);
                if (!eventIds.add(e.id)) {
                    Finding f = finding("error", "duplicate_event_id",
                            "معرّف الحدث مكرر: " + e.id, true, "إعادة تسمية الحدث المكرر");
                    f.eventId = e.id;
                    out.add(f);
                }
                if (!TRIGGERS.contains(e.trigger)) {
                    Finding f = finding("error", "unknown_trigger",
                            "نوع حدث غير مدعوم: " + e.trigger, true,
                            "تحويل الحدث إلى click");
                    f.eventId = e.id;
                    out.add(f);
                }
                if (e.nodeId != null && !e.nodeId.isEmpty() && !nodes.containsKey(e.nodeId)) {
                    Finding f = finding("error", "missing_node",
                            "الحدث " + e.trigger + " يشير إلى عنصر غير موجود: " + e.nodeId,
                            true, "تعطيل الحدث وإزالة الربط الناقص");
                    f.eventId = e.id;
                    out.add(f);
                }
                if (e.actions == null || e.actions.isEmpty()) {
                    Finding f = finding("warning", "empty_event",
                            "الحدث " + e.trigger + " بلا إجراءات.", false, "");
                    f.eventId = e.id;
                    out.add(f);
                } else {
                    for (Action a : e.actions) {
                        if (a == null) continue;
                        if (!ACTION_TYPES.contains(a.type)) {
                            out.add(finding("error", "unknown_action",
                                    "نوع إجراء غير مدعوم: " + a.type, true,
                                    "تحويل الإجراء إلى toast"));
                        }
                        if ("call_function".equals(a.type)) {
                            FunctionRef fn = function(project, a.functionRef);
                            if (fn == null) {
                                out.add(finding("error", "missing_function",
                                        "إجراء call_function بلا وظيفة صالحة.", true,
                                        "تحويل الإجراء إلى toast"));
                            } else if (!fn.safe || !fn.isStatic || !fn.isPublic) {
                                out.add(finding("error", "unsafe_function",
                                        "الوظيفة " + fn.displayName + " خارج القائمة البيضاء.", true,
                                        "إزالة الاستدعاء غير الآمن"));
                            }
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Integer> e : eventCounts.entrySet()) {
            if (e.getValue() > 1) {
                Finding f = finding("warning", "duplicate_event_binding",
                        "أكثر من حدث مرتبط بالمعرّف " + e.getKey(), false, "");
                f.eventId = e.getKey();
                out.add(f);
            }
        }
        if (model.variables != null) {
            Set<String> varNames = new HashSet<>();
            for (Variable v : model.variables) {
                if (v == null || v.name == null || v.name.trim().isEmpty()) {
                    out.add(finding("error", "empty_variable",
                            "متغير بلا اسم.", true, "حذف المتغير الفارغ"));
                    continue;
                }
                if (!varNames.add(v.name)) {
                    out.add(finding("warning", "duplicate_variable",
                            "متغير مكرر: " + v.name, true, "دمج المتغير المكرر"));
                }
                if (!isVariableType(v.type)) {
                    out.add(finding("warning", "bad_variable_type",
                            "نوع متغير غير معروف: " + v.type, true, "تحويله إلى string"));
                }
            }
        }
        Set<String> knownVars = new HashSet<>();
        if (model.variables != null) for (Variable v : model.variables) if (v != null) knownVars.add(v.name);
        for (Node n : nodes.values()) {
            if (n == null) continue;
            if (!KINDS.contains(n.kind)) {
                Finding f = finding("error", "unknown_node_kind",
                        "نوع عنصر غير مدعوم: " + n.kind, true, "تحويله إلى text");
                f.nodeId = n.id;
                out.add(f);
            }
            checkColor(n.textColor, "لون نص العنصر " + n.id, out);
            checkColor(n.backgroundColor, "خلفية العنصر " + n.id, out);
            checkColor(n.borderColor, "حدود العنصر " + n.id, out);
            if (n.kind.equals("list")) {
                if (n.options == null || n.options.isEmpty()) {
                    Finding f = finding("error", "empty_list",
                            "قائمة بلا خيارات: " + n.id, false, "");
                    f.nodeId = n.id;
                    out.add(f);
                } else if (n.selectedIndices != null) {
                    for (Integer idx : n.selectedIndices) {
                        if (idx == null || idx < 0 || idx >= n.options.size()) {
                            Finding f = finding("warning", "bad_selection",
                                    "اختيار خارج نطاق القائمة: " + n.id, true,
                                    "إزالة الاختيار غير الصالح");
                            f.nodeId = n.id;
                            out.add(f);
                        }
                    }
                }
            }
            if (n.progress < 0 || n.progress > 100) {
                Finding f = finding("warning", "bad_progress",
                        "قيمة التقدم خارج 0..100: " + n.id, true, "تقييد القيمة");
                f.nodeId = n.id;
                out.add(f);
            }
            if (n.functionRef != null && !n.functionRef.isEmpty()) {
                FunctionRef fn = function(project, n.functionRef);
                if (fn == null) {
                    Finding f = finding("error", "missing_function",
                            "العنصر " + n.id + " مرتبط بوظيفة غير موجودة.", true,
                            "إزالة الربط");
                    f.nodeId = n.id;
                    out.add(f);
                }
            }
            checkPlaceholders(n.text, knownVars, n.id, out);
            checkPlaceholders(n.hint, knownVars, n.id, out);
            if (n.dataBinding != null && !n.dataBinding.isEmpty() && !knownVars.contains(n.dataBinding)) {
                Finding f = finding("warning", "missing_binding",
                        "ربط بيانات لمتغير غير معروف: " + n.dataBinding, true,
                        "إنشاء المتغير أو إزالة الربط");
                f.nodeId = n.id;
                out.add(f);
            }
        }
        // Circular open_dialog references between project dialogs.
        if (project != null && project.dialogs != null) {
            Map<String, Set<String>> edges = new HashMap<>();
            for (Model m : project.dialogs) {
                if (m == null) continue;
                Set<String> targets = new HashSet<>();
                if (m.events != null) for (Event e : m.events) {
                    if (e == null || e.actions == null) continue;
                    for (Action a : e.actions) {
                        if (a != null && "open_dialog".equals(a.type) && a.target != null) {
                            for (Model other : project.dialogs) {
                                if (other != null && a.target.equals(other.id)) targets.add(other.id);
                            }
                        }
                    }
                }
                edges.put(m.id, targets);
            }
            Set<String> visiting = new HashSet<>();
            Set<String> done = new HashSet<>();
            for (String id : edges.keySet()) {
                if (hasCycle(id, edges, visiting, done)) {
                    Finding f = finding("error", "circular_dialog",
                            "استدعاء دائري بين الدايلوكات عند " + id, false, "");
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean hasCycle(String id, Map<String, Set<String>> edges,
                                    Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return false;
        if (!visiting.add(id)) return true;
        Set<String> next = edges.get(id);
        if (next != null) {
            for (String n : next) if (hasCycle(n, edges, visiting, done)) return true;
        }
        visiting.remove(id);
        done.add(id);
        return false;
    }

    private static void collectNodes(List<Node> list, Map<String, Node> out,
                                     Map<String, Integer> counts) {
        if (list == null) return;
        for (Node n : list) {
            if (n == null) continue;
            if (n.id == null || n.id.isEmpty()) n.id = DialogModels.newId("node");
            out.put(n.id, n);
            counts.merge(n.id, 1, Integer::sum);
            collectNodes(n.children, out, counts);
        }
    }

    private static FunctionRef function(Project project, String id) {
        if (project == null || id == null || project.functions == null) return null;
        for (FunctionRef f : project.functions) if (f != null && id.equals(f.id)) return f;
        return null;
    }

    private static void checkColor(String color, String label, List<Finding> out) {
        if (color == null || color.isEmpty()) return;
        if (!color.startsWith("#") || (color.length() != 7 && color.length() != 9)) {
            out.add(finding("warning", "bad_color",
                    "قيمة لون غير صالحة في " + label + ": " + color, true,
                    "استبدالها بلون افتراضي"));
        }
    }

    private static void checkPlaceholders(String text, Set<String> vars, String nodeId,
                                          List<Finding> out) {
        if (text == null || text.isEmpty()) return;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!vars.contains(m.group(1))) {
                Finding f = finding("warning", "missing_placeholder",
                        "Placeholder بلا متغير: ${" + m.group(1) + "}", false, "");
                f.nodeId = nodeId;
                out.add(f);
            }
        }
    }

    private static boolean isVariableType(String type) {
        return "string".equals(type) || "number".equals(type)
                || "boolean".equals(type) || "list".equals(type);
    }

    private static Finding finding(String severity, String type, String message,
                                   boolean fixable, String fix) {
        Finding f = new Finding();
        f.severity = severity;
        f.type = type;
        f.message = message;
        f.fixable = fixable;
        f.fixDescription = fix;
        return f;
    }

    /**
     * Applies only deterministic, loss-free repairs. Returns the number of
     * changes; ambiguous findings stay untouched for the user to decide.
     */
    public static int applySafeFixes(Project project, Model model, List<Finding> findings) {
        if (model == null || findings == null) return 0;
        int changes = 0;
        Set<String> seen = new HashSet<>();
        Map<String, String> renames = new HashMap<>();
        if (model.nodes != null) changes += dedupeNodes(model.nodes, seen, renames);
        if (model.events != null) {
            Set<String> seenEvents = new HashSet<>();
            for (Event e : model.events) {
                if (e == null) continue;
                if (!seenEvents.add(e.id)) {
                    e.id = DialogModels.newId("evt");
                    changes++;
                }
                if (e.nodeId != null && !e.nodeId.isEmpty() && model.node(e.nodeId) == null) {
                    e.enabled = false;
                    changes++;
                }
                if (!TRIGGERS.contains(e.trigger)) {
                    e.trigger = "click";
                    changes++;
                }
                if (e.actions != null) {
                    for (Action a : e.actions) {
                        if (a == null) continue;
                        if (!ACTION_TYPES.contains(a.type)) {
                            a.type = "toast";
                            a.value = "إجراء غير مدعوم";
                            changes++;
                        }
                        if ("call_function".equals(a.type)) {
                            FunctionRef fn = function(project, a.functionRef);
                            if (fn == null || !fn.safe || !fn.isStatic || !fn.isPublic) {
                                a.type = "toast";
                                a.value = "وظيفة غير مصرح بها";
                                changes++;
                            }
                        }
                    }
                }
            }
        }
        if (model.variables != null) {
            Set<String> names = new HashSet<>();
            List<Variable> kept = new ArrayList<>();
            for (Variable v : model.variables) {
                if (v == null || v.name == null || v.name.trim().isEmpty()) {
                    changes++;
                    continue;
                }
                if (!names.add(v.name)) {
                    changes++;
                    continue;
                }
                if (!isVariableType(v.type)) {
                    v.type = "string";
                    changes++;
                }
                kept.add(v);
            }
            model.variables = kept;
        }
        if (model.nodes != null) {
            List<Node> kept = new ArrayList<>();
            for (Node n : model.nodes) {
                if (n == null) {
                    changes++;
                    continue;
                }
                if (!KINDS.contains(n.kind)) {
                    n.kind = "text";
                    changes++;
                }
                if (n.kind.equals("list") && n.options != null && n.selectedIndices != null) {
                    List<Integer> valid = new ArrayList<>();
                    for (Integer idx : n.selectedIndices) {
                        if (idx != null && idx >= 0 && idx < n.options.size()) valid.add(idx);
                        else changes++;
                    }
                    n.selectedIndices = valid;
                }
                if (n.progress < 0 || n.progress > 100) {
                    n.progress = Math.max(0, Math.min(100, n.progress));
                    changes++;
                }
                checkColorFix(n, findings, () -> {
                });
                kept.add(n);
            }
            model.nodes = kept;
        }
        if (DialogDesigns.find(model.design) == null && !"custom".equals(model.design)) {
            model.design = "material";
            changes++;
        }
        if (model.countdownSeconds < 0) {
            model.countdownSeconds = 0;
            changes++;
        } else if (model.countdownSeconds > 3600) {
            model.countdownSeconds = 3600;
            changes++;
        }
        if (model.widthDp < 0) {
            model.widthDp = 320;
            changes++;
        } else if (model.widthDp > 4096) {
            model.widthDp = 4096;
            changes++;
        }
        if (model.cornerRadiusDp < 0) {
            model.cornerRadiusDp = 0;
            changes++;
        } else if (model.cornerRadiusDp > 1000) {
            model.cornerRadiusDp = 1000;
            changes++;
        }
        if (!isColor(model.backgroundColor) && model.backgroundColor != null && !model.backgroundColor.isEmpty()) {
            model.backgroundColor = "#F2FFFFFF";
            changes++;
        }
        if (!isColor(model.borderColor) && model.borderColor != null && !model.borderColor.isEmpty()) {
            model.borderColor = "#00000000";
            changes++;
        }
        // Re-point eventIds after renames.
        if (model.events != null && model.nodes != null) {
            for (Event e : model.events) {
                if (e == null) continue;
                if (renames.containsKey(e.nodeId)) {
                    e.nodeId = renames.get(e.nodeId);
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int dedupeNodes(List<Node> list, Set<String> seen, Map<String, String> renames) {
        int changes = 0;
        for (Node n : list) {
            if (n == null) continue;
            if (n.id == null || n.id.isEmpty()) {
                n.id = DialogModels.newId("node");
                changes++;
            }
            if (!seen.add(n.id)) {
                String old = n.id;
                n.id = DialogModels.newId("node");
                renames.put(old, n.id);
                changes++;
            }
            changes += dedupeNodes(n.children, seen, renames);
        }
        return changes;
    }

    private static void checkColorFix(Node n, List<Finding> findings, Runnable noop) {
        if (!isColor(n.textColor) && n.textColor != null && !n.textColor.isEmpty()) n.textColor = "";
        if (!isColor(n.backgroundColor) && n.backgroundColor != null && !n.backgroundColor.isEmpty()) n.backgroundColor = "";
        if (!isColor(n.borderColor) && n.borderColor != null && !n.borderColor.isEmpty()) n.borderColor = "";
    }

    private static boolean isColor(String c) {
        if (c == null || c.isEmpty()) return true;
        return c.startsWith("#") && (c.length() == 7 || c.length() == 9);
    }

    public static String summarize(List<Finding> findings) {
        int errors = 0, warnings = 0;
        if (findings != null) {
            for (Finding f : findings) {
                if (f == null) continue;
                if ("error".equals(f.severity)) errors++;
                else warnings++;
            }
        }
        return String.format(Locale.US, "%d أخطاء، %d تحذيرات", errors, warnings);
    }

    public static int reconnectFunctions(Project project, Model model) {
        if (project == null || project.functions == null || model == null) return 0;
        Map<String, FunctionRef> safe = new LinkedHashMap<>();
        for (FunctionRef f : project.functions) {
            if (f == null || !f.safe) continue;
            safe.put(f.id, f);
        }
        int changed = 0;
        if (model.events != null) {
            for (Event e : model.events) {
                if (e == null || e.actions == null) continue;
                for (Action a : e.actions) {
                    if (a == null || !"call_function".equals(a.type)) continue;
                    if (a.functionRef != null && !a.functionRef.isEmpty()
                            && safe.containsKey(a.functionRef)) continue;
                    String hint = a.value == null || a.value.isEmpty() ? a.target : a.value;
                    FunctionRef match = matchFunction(safe, hint);
                    if (match != null) {
                        a.functionRef = match.id;
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private static FunctionRef matchFunction(Map<String, FunctionRef> safe, String hint) {
        if (hint == null || hint.trim().isEmpty()) return null;
        String h = hint.trim();
        int hash = h.lastIndexOf('#');
        if (hash >= 0) h = h.substring(hash + 1);
        int dot = h.lastIndexOf('.');
        if (dot >= 0) h = h.substring(dot + 1);
        int paren = h.indexOf('(');
        if (paren > 0) h = h.substring(0, paren);
        h = h.trim();
        if (h.isEmpty()) return null;
        FunctionRef found = null;
        for (FunctionRef f : safe.values()) {
            if (f.methodName != null && f.methodName.equalsIgnoreCase(h)) {
                if (found != null && !found.id.equals(f.id)) return null;
                found = f;
            }
        }
        if (found != null) return found;
        for (FunctionRef f : safe.values()) {
            if (f.displayName != null && f.displayName.equalsIgnoreCase(h)) {
                if (found != null && !found.id.equals(f.id)) return null;
                found = f;
            }
        }
        return found;
    }
}
