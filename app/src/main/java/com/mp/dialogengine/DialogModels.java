package com.mp.dialogengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serialisable model shared by the dialog engineering UI, the preview runtime
 * and the JSON spec that is injected into a target APK.
 *
 * <p>Field names are part of the wire format read by {@code com.mp.dialogruntime}
 * through {@code org.json}; keep them stable or bump {@link Project#version}.</p>
 */
public final class DialogModels {

    public static final int SCHEMA_VERSION = 1;

    private DialogModels() {
    }

    public static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static final class Project {
        public int version = SCHEMA_VERSION;
        public String apkPath = "";
        public String apkHash = "";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
        public String selectedDialogId = "";
        public List<Model> dialogs = new ArrayList<>();
        public List<FunctionRef> functions = new ArrayList<>();
        public Map<String, String> metadata = new LinkedHashMap<>();

        public Model selected() {
            if (dialogs == null) return null;
            for (Model m : dialogs) {
                if (m != null && m.id != null && m.id.equals(selectedDialogId)) return m;
            }
            return dialogs.isEmpty() ? null : dialogs.get(0);
        }
    }

    public static final class Model {
        public String id = newId("dlg");
        public String name = "";
        public String screen = "";
        public String title = "";
        public String message = "";
        public String design = "material";
        public String designName = "";
        public int widthDp = 320;
        public int heightDp = 0;
        public String backgroundColor = "#F2FFFFFF";
        public String backgroundEndColor = "";
        public int gradientOrientation = 0;
        public float cornerRadiusDp = 24f;
        public float borderWidthDp = 0f;
        public String borderColor = "#00000000";
        public float paddingDp = 20f;
        public float blurRadiusDp = 0f;
        public float dimAmount = 0.4f;
        public String animation = "fade_scale";
        public String titleColor = "";
        public String messageColor = "";
        public String buttonColor = "";
        public String buttonTextColor = "";
        public String font = "";
        public String fontPath = "";
        public boolean fullWidth = false;
        public boolean bottomSheet = false;
        public boolean glass = false;
        public boolean dark = false;
        public float elevationDp = 8f;
        public float maxWidthPercent = 0.92f;
        public boolean cancelable = true;
        public boolean draggable = false;
        public int countdownSeconds = 0;
        public String showMode = "always";
        public String hookMethod = "onCreate";
        public int priority = 0;
        public boolean preventDuplicate = true;
        public boolean saveState = false;
        public boolean preventCloseDuringAction = false;
        public SourceRef sourceRef = new SourceRef();
        public List<Variable> variables = new ArrayList<>();
        public List<Node> nodes = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public Map<String, String> metadata = new LinkedHashMap<>();

        public Node node(String nodeId) {
            if (nodes == null || nodeId == null) return null;
            return findNode(nodes, nodeId);
        }

        public Event event(String eventId) {
            if (events == null) return null;
            for (Event e : events) {
                if (e != null && eventId != null && eventId.equals(e.id)) return e;
            }
            return null;
        }

        public Variable variable(String name) {
            if (variables == null) return null;
            for (Variable v : variables) {
                if (v != null && name != null && name.equals(v.name)) return v;
            }
            return null;
        }

        private static Node findNode(List<Node> list, String nodeId) {
            for (Node n : list) {
                if (n == null) continue;
                if (nodeId.equals(n.id)) return n;
                Node child = findNode(n.children, nodeId);
                if (child != null) return child;
            }
            return null;
        }
    }

    public static final class Node {
        public String id = newId("node");
        public String kind = "text";
        public String text = "";
        public String hint = "";
        public float textSizeSp = 16f;
        public String textColor = "";
        public String backgroundColor = "";
        public String backgroundColor2 = "";
        public float cornerRadiusDp = 0f;
        public float borderWidthDp = 0f;
        public String borderColor = "";
        public float paddingDp = 0f;
        public int widthDp = -1;
        public int heightDp = -2;
        public int xDp = 0;
        public int yDp = 0;
        public String gravity = "start";
        public boolean visible = true;
        public boolean enabled = true;
        public float alpha = 1f;
        public String imageBase64 = "";
        public String font = "";
        public String fontPath = "";
        public int fontStyle = 0;
        public boolean checked = false;
        public List<String> options = new ArrayList<>();
        public List<Integer> selectedIndices = new ArrayList<>();
        public boolean multiSelect = false;
        public boolean searchable = false;
        public int progress = 0;
        public boolean indeterminate = true;
        public String dataBinding = "";
        public String functionRef = "";
        public String tag = "";
        public List<String> eventIds = new ArrayList<>();
        public List<Node> children = new ArrayList<>();
    }

    public static final class Event {
        public String id = newId("evt");
        public String trigger = "click";
        public String nodeId = "";
        public boolean enabled = true;
        public int priority = 0;
        public boolean stopOnFailure = true;
        public String skipIf = "";
        public int delayMs = 1500;
        public List<Action> actions = new ArrayList<>();
    }

    public static final class Action {
        public String id = newId("act");
        public String type = "dismiss";
        public String target = "";
        public String key = "";
        public String value = "";
        public String condition = "";
        public int priority = 0;
        public boolean stopOnFailure = true;
        public String functionRef = "";
        public List<String> args = new ArrayList<>();
    }

    public static final class Variable {
        public String name = "";
        public String type = "string";
        public String defaultValue = "";
        public String currentValue = "";
        public String source = "static";
        public String sourceKey = "";
        public String description = "";
    }

    public static final class SourceRef {
        public String kind = "";
        public String container = "";
        public String path = "";
        public String className = "";
        public String methodName = "";
        public String methodDescriptor = "";
        public int line = 0;
        public String snippet = "";
        public float confidence = 1f;
    }

    public static final class FunctionRef {
        public String id = newId("fn");
        public String className = "";
        public String methodName = "";
        public String descriptor = "";
        public List<String> params = new ArrayList<>();
        public String returnType = "void";
        public boolean isStatic = true;
        public boolean isPublic = true;
        public boolean safe = true;
        public String sourceEntry = "";
        public String displayName = "";
        public String reason = "";
    }

    public static final class Finding {
        public String id = newId("find");
        public String severity = "warning";
        public String type = "";
        public String message = "";
        public String nodeId = "";
        public String eventId = "";
        public boolean fixable = false;
        public String fixDescription = "";
        public SourceRef sourceRef = new SourceRef();
    }

    public static final class Backup {
        public String id = "";
        public String originalPath = "";
        public String backupPath = "";
        public String sha256 = "";
        public long size = 0L;
        public long timestamp = 0L;
        public String note = "";
    }

    public static final class Descriptor {
        public String id = newId("desc");
        public String name = "";
        public String screen = "";
        public String type = "smali";
        public String title = "";
        public String message = "";
        public List<String> buttons = new ArrayList<>();
        public float confidence = 0.7f;
        public SourceRef sourceRef = new SourceRef();
        public Model model;
    }

    public static final class ScanResult {
        public String apkPath = "";
        public String workDir = "";
        public int dexCount = 0;
        public int layoutCount = 0;
        public int styleCount = 0;
        public long durationMs = 0L;
        public List<Descriptor> dialogs = new ArrayList<>();
        public List<FunctionRef> functions = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }
}
