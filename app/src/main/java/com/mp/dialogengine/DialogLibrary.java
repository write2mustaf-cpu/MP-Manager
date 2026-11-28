package com.mp.dialogengine;

import android.content.Context;

import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;
import com.mp.dialogengine.DialogModels.Project;
import com.mp.dialogengine.DialogModels.Variable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** File-backed templates/projects plus safe merge and replace helpers. */
public final class DialogLibrary {

    private static final String ROOT = "dialog_engine/library";

    private DialogLibrary() {
    }

    public static File root(Context context) {
        return new File(context.getFilesDir(), ROOT);
    }

    public static File saveTemplate(Context context, Model model) throws IOException {
        File dir = new File(root(context), "templates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, DialogJson.safeName(model.name.isEmpty() ? model.id : model.name) + ".json");
        int i = 2;
        while (f.exists()) {
            f = new File(dir, DialogJson.safeName(model.name.isEmpty() ? model.id : model.name) + "_" + i + ".json");
            i++;
        }
        DialogJson.writeFile(f, DialogJson.toJson(model));
        return f;
    }

    public static List<Model> listTemplates(Context context) {
        List<Model> out = new ArrayList<>();
        File dir = new File(root(context), "templates");
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;
            try {
                Model m = DialogJson.fromJson(DialogJson.readFile(f), Model.class);
                if (m != null) {
                    m.metadata.put("library_file", f.getAbsolutePath());
                    out.add(m);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static boolean deleteTemplate(Model model) {
        if (model == null || model.metadata == null) return false;
        String path = model.metadata.get("library_file");
        if (path == null) return false;
        File f = new File(path);
        return f.isFile() && f.delete();
    }

    public static File saveProject(Context context, Project project) throws IOException {
        File dir = new File(root(context), "projects");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        project.updatedAt = System.currentTimeMillis();
        File f = new File(dir, "project.json");
        if (f.isFile()) {
            try {
                DialogJson.writeFile(new File(dir, "project.json.bak"), DialogJson.readFile(f));
            } catch (Exception ignored) {
            }
        }
        DialogJson.writeFile(f, DialogJson.toJson(project));
        return f;
    }

    public static Project loadProject(Context context) {
        File dir = new File(root(context), "projects");
        File f = new File(dir, "project.json");
        if (!f.isFile()) return null;
        try {
            return DialogJson.fromJson(DialogJson.readFile(f), Project.class);
        } catch (Exception e) {
            File bak = new File(dir, "project.json.bak");
            if (bak.isFile()) {
                try {
                    return DialogJson.fromJson(DialogJson.readFile(bak), Project.class);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    public static File export(Context context, Object value, String name) throws IOException {
        File dir = new File(root(context), "exports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, DialogJson.safeName(name) + ".json");
        int i = 2;
        while (f.exists()) {
            f = new File(dir, DialogJson.safeName(name) + "_" + i + ".json");
            i++;
        }
        DialogJson.writeFile(f, DialogJson.toJson(value));
        return f;
    }

    public static Model importModel(File file) throws IOException {
        return DialogJson.fromJson(DialogJson.readFile(file), Model.class);
    }

    public static Project importProject(File file) throws IOException {
        return DialogJson.fromJson(DialogJson.readFile(file), Project.class);
    }

    public static List<Model> search(List<Model> models, String query) {
        List<Model> out = new ArrayList<>();
        if (models == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return new ArrayList<>(models);
        for (Model m : models) {
            if (m == null) continue;
            if (contains(m.name, q) || contains(m.id, q) || contains(m.title, q)
                    || contains(m.message, q) || contains(m.screen, q)) {
                out.add(m);
                continue;
            }
            if (m.nodes != null) {
                boolean hit = false;
                for (Node n : m.nodes) {
                    if (n != null && (contains(n.text, q) || contains(n.id, q)
                            || contains(n.hint, q))) {
                        hit = true;
                        break;
                    }
                }
                if (hit) out.add(m);
            }
        }
        return out;
    }

    public static final class MergeResult {
        public Model model;
        public final List<String> warnings = new ArrayList<>();
    }

    /**
     * Concatenates two dialogs, remapping ids so nothing collides. Event
     * bindings and placeholders are kept; only ambiguous id collisions are
     * reported instead of guessed.
     */
    public static MergeResult merge(Model a, Model b) {
        MergeResult out = new MergeResult();
        if (a == null && b == null) {
            out.warnings.add("لا يوجد دايلوك للدمج");
            return out;
        }
        if (a == null) {
            out.model = DialogJson.cloneModel(b);
            return out;
        }
        if (b == null) {
            out.model = DialogJson.cloneModel(a);
            return out;
        }
        Model m = DialogJson.cloneModel(a);
        Set<String> nodeIds = collectNodeIds(m.nodes);
        Map<String, String> nodeMap = new HashMap<>();
        Set<String> eventIds = collectEventIds(m.events);
        Map<String, String> eventMap = new HashMap<>();
        if (b.nodes != null) {
            List<Node> copy = copyNodes(b.nodes, nodeIds, nodeMap);
            m.nodes.addAll(copy);
        }
        if (b.events != null) {
            for (Event e : b.events) {
                if (e == null) continue;
                Event c = DialogJson.gson().fromJson(DialogJson.toJson(e), Event.class);
                if (c == null) continue;
                String oldEvent = c.id;
                if (eventIds.contains(c.id)) {
                    c.id = DialogModels.newId("evt");
                }
                eventIds.add(c.id);
                eventMap.put(oldEvent, c.id);
                if (c.nodeId != null && nodeMap.containsKey(c.nodeId)) {
                    c.nodeId = nodeMap.get(c.nodeId);
                }
                m.events.add(c);
            }
        }
        remapEventIds(m.nodes, eventMap);
        if (b.variables != null) {
            Set<String> names = new HashSet<>();
            if (m.variables != null) for (Variable v : m.variables) if (v != null) names.add(v.name);
            for (Variable v : b.variables) {
                if (v == null) continue;
                if (names.contains(v.name)) {
                    out.warnings.add("تم تجاهل متغير مكرر: " + v.name);
                    continue;
                }
                names.add(v.name);
                m.variables.add(DialogJson.gson().fromJson(DialogJson.toJson(v), Variable.class));
            }
        }
        if (m.metadata == null) m.metadata = new LinkedHashMap<>();
        m.metadata.put("merged_from", a.id + "," + b.id);
        m.id = DialogModels.newId("dlg");
        m.name = (a.name == null ? "dialog" : a.name) + " + " + (b.name == null ? "dialog" : b.name);
        out.model = m;
        return out;
    }

    /** Replaces b's content while preserving a's id and external references. */
    public static Model replace(Model target, Model replacement) {
        if (target == null) return DialogJson.cloneModel(replacement);
        if (replacement == null) return target;
        Model m = DialogJson.cloneModel(replacement);
        m.id = target.id;
        m.name = target.name;
        m.screen = target.screen;
        m.sourceRef = target.sourceRef;
        if (m.metadata == null) m.metadata = new LinkedHashMap<>();
        m.metadata.put("replaced", target.id);
        return m;
    }

    public static List<String> relations(Project project) {
        List<String> out = new ArrayList<>();
        if (project == null || project.dialogs == null) return out;
        Map<String, String> names = new HashMap<>();
        for (Model m : project.dialogs) if (m != null) names.put(m.id, m.name);
        for (Model m : project.dialogs) {
            if (m == null || m.events == null) continue;
            for (Event e : m.events) {
                if (e == null || e.actions == null) continue;
                for (Action a : e.actions) {
                    if (a != null && "open_dialog".equals(a.type) && a.target != null
                            && names.containsKey(a.target)) {
                        out.add(names.get(m.id) + " → " + names.get(a.target));
                    }
                }
            }
        }
        return out;
    }

    private static void remapEventIds(List<Node> nodes, Map<String, String> map) {
        if (nodes == null || map.isEmpty()) return;
        for (Node n : nodes) {
            if (n == null) continue;
            if (n.eventIds != null) {
                for (int i = 0; i < n.eventIds.size(); i++) {
                    String mapped = map.get(n.eventIds.get(i));
                    if (mapped != null) n.eventIds.set(i, mapped);
                }
            }
            remapEventIds(n.children, map);
        }
    }

    private static Set<String> collectNodeIds(List<Node> nodes) {
        Set<String> out = new HashSet<>();
        if (nodes == null) return out;
        for (Node n : nodes) {
            if (n == null) continue;
            out.add(n.id);
            out.addAll(collectNodeIds(n.children));
        }
        return out;
    }

    private static Set<String> collectEventIds(List<Event> events) {
        Set<String> out = new HashSet<>();
        if (events == null) return out;
        for (Event e : events) if (e != null) out.add(e.id);
        return out;
    }

    private static List<Node> copyNodes(List<Node> nodes, Set<String> used,
                                        Map<String, String> map) {
        List<Node> out = new ArrayList<>();
        if (nodes == null) return out;
        for (Node n : nodes) {
            if (n == null) continue;
            Node c = DialogJson.gson().fromJson(DialogJson.toJson(n), Node.class);
            if (c == null) continue;
            if (used.contains(c.id)) {
                String old = c.id;
                c.id = DialogModels.newId("node");
                map.put(old, c.id);
            }
            used.add(c.id);
            c.children = copyNodes(c.children, used, map);
            out.add(c);
        }
        return out;
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }
}
