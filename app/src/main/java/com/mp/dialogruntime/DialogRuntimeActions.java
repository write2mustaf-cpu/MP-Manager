package com.mp.dialogruntime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event/action interpreter used by {@link DialogRuntime}. */
final class DialogRuntimeActions {

    private static final String TAG = "MPDialogRuntime";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DialogRuntimeActions() {
    }

    static void attachAll(DialogRuntime rt) {
        JSONArray events = rt.spec().optJSONArray("events");
        if (events == null) return;
        Map<String, Set<String>> clickTriggers = new LinkedHashMap<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !e.optBoolean("enabled", true)) continue;
            String trigger = e.optString("trigger", "click");
            String nodeId = e.optString("nodeId", "");
            View view = nodeId.isEmpty() ? null : rt.views().get(nodeId);
            if ("timeout".equals(trigger)) {
                int delay = e.optInt("delayMs", 1500);
                MAIN.postDelayed(() -> runEvents(rt, "timeout", nodeId), Math.max(0, delay));
                continue;
            }
            if (view == null) continue;
            switch (trigger) {
                case "long_click":
                    view.setOnLongClickListener(v -> {
                        runEvents(rt, "long_click", nodeId);
                        return true;
                    });
                    break;
                case "double_click":
                    attachDoubleClick(rt, view, nodeId);
                    break;
                case "focus":
                    view.setOnFocusChangeListener((v, hasFocus) -> {
                        if (hasFocus) runEvents(rt, "focus", nodeId);
                    });
                    break;
                case "input_change":
                    if (view instanceof EditText) {
                        ((EditText) view).addTextChangedListener(new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                            }

                            @Override
                            public void onTextChanged(CharSequence s, int st, int b, int c) {
                            }

                            @Override
                            public void afterTextChanged(Editable s) {
                                runEvents(rt, "input_change", nodeId);
                            }
                        });
                    }
                    break;
                case "confirm":
                case "cancel":
                case "click":
                    if (!(view instanceof EditText) && !(view instanceof CheckBox)) {
                        clickTriggers.computeIfAbsent(nodeId,
                                k -> new LinkedHashSet<>()).add(trigger);
                    }
                    break;
                default:
                    break;
            }
        }
        for (Map.Entry<String, Set<String>> entry : clickTriggers.entrySet()) {
            View view = rt.views().get(entry.getKey());
            if (view == null) continue;
            Set<String> triggers = entry.getValue();
            triggers.add("click");
            view.setOnClickListener(v -> {
                for (String trigger : triggers) runEvents(rt, trigger, entry.getKey());
            });
        }
        attachConfirmCancel(rt, events);
    }

    private static void attachDoubleClick(DialogRuntime rt, View view, String nodeId) {
        final GestureDetector detector = new GestureDetector(rt.ctx(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        runEvents(rt, "double_click", nodeId);
                        return true;
                    }
                });
        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false;
        });
    }

    private static void attachConfirmCancel(DialogRuntime rt, JSONArray events) {
        boolean hasConfirm = false;
        boolean hasCancel = false;
        JSONObject confirmEvent = null;
        JSONObject cancelEvent = null;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !e.optBoolean("enabled", true)) continue;
            if ("confirm".equals(e.optString("trigger"))) {
                hasConfirm = true;
                confirmEvent = e;
            }
            if ("cancel".equals(e.optString("trigger"))) {
                hasCancel = true;
                cancelEvent = e;
            }
        }
        if (!hasConfirm && !hasCancel) return;
        View first = null;
        View last = null;
        for (View v : rt.views().values()) {
            if (!(v instanceof TextView) || v instanceof EditText) continue;
            if (v instanceof CheckBox) continue;
            if (!v.isClickable()) continue;
            if (first == null) first = v;
            last = v;
        }
        if (hasConfirm && confirmEvent != null && confirmEvent.optString("nodeId", "").isEmpty()
                && first != null) {
            View target = first;
            String id = String.valueOf(target.getTag());
            target.setOnClickListener(v -> {
                runEvents(rt, "confirm", "");
                if (!id.isEmpty() && !"null".equals(id)) runEvents(rt, "click", id);
            });
        }
        if (hasCancel && cancelEvent != null && cancelEvent.optString("nodeId", "").isEmpty()
                && last != null && last != first) {
            View target = last;
            String id = String.valueOf(target.getTag());
            target.setOnClickListener(v -> {
                runEvents(rt, "cancel", "");
                if (!id.isEmpty() && !"null".equals(id)) runEvents(rt, "click", id);
            });
        }
    }

    static void runEvents(DialogRuntime rt, String trigger, String nodeId) {
        JSONArray events = rt.spec().optJSONArray("events");
        if (events == null) return;
        List<JSONObject> matched = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !e.optBoolean("enabled", true)) continue;
            if (!trigger.equals(e.optString("trigger", ""))) continue;
            String declared = e.optString("nodeId", "");
            if (!declared.isEmpty() && nodeId != null && !nodeId.isEmpty()
                    && !declared.equals(nodeId)) continue;
            if (!declared.isEmpty() && (nodeId == null || nodeId.isEmpty())
                    && !"open".equals(trigger) && !"close".equals(trigger)
                    && !"timeout".equals(trigger) && !"success".equals(trigger)
                    && !"failure".equals(trigger)) continue;
            String skip = e.optString("skipIf", "");
            if (!skip.isEmpty() && !eval(rt, skip)) continue;
            matched.add(e);
        }
        Collections.sort(matched, Comparator.comparingInt(o -> o.optInt("priority", 0)));
        for (JSONObject e : matched) {
            runActionList(rt, e, e.optJSONArray("actions"), 0);
        }
    }

    private static void runActionList(DialogRuntime rt, JSONObject event, JSONArray actions, int index) {
        if (actions == null) return;
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = index; i < actions.length(); i++) {
            JSONObject o = actions.optJSONObject(i);
            if (o != null) ordered.add(o);
        }
        ordered.sort(Comparator.comparingInt(o -> o.optInt("priority", 0)));
        rt.beginAction();
        runActionSequence(rt, event, ordered, 0);
    }

    private static void runActionSequence(DialogRuntime rt, JSONObject event,
                                          List<JSONObject> actions, int index) {
        if (index >= actions.size()) {
            rt.endAction();
            return;
        }
        JSONObject a = actions.get(index);
        String type = a.optString("type", "");
        if ("delay".equals(type)) {
            int ms = parseInt(a.optString("value", "300"), 300);
            MAIN.postDelayed(() -> runActionSequence(rt, event, actions, index + 1), Math.max(0, ms));
            return;
        }
        boolean ok = execute(rt, a);
        boolean stop = a.optBoolean("stopOnFailure", event.optBoolean("stopOnFailure", true));
        if (!ok && stop) {
            rt.endAction();
            return;
        }
        runActionSequence(rt, event, actions, index + 1);
    }

    private static boolean execute(DialogRuntime rt, JSONObject a) {
        String type = a.optString("type", "");
        String condition = a.optString("condition", "");
        if (!condition.isEmpty() && !eval(rt, condition)) return false;
        String target = rt.resolve(a.optString("target", ""));
        String key = a.optString("key", "");
        String value = rt.resolve(a.optString("value", ""));
        try {
            switch (type) {
                case "condition":
                    return eval(rt, value.isEmpty() ? condition : value);
                case "read_field": {
                    String v = readNodeValue(rt, target);
                    rt.setVar(key.isEmpty() ? target : key, v);
                    return true;
                }
                case "call_function":
                    return callFunction(rt, a.optString("functionRef", ""), a.optJSONArray("args"));
                case "pass_data":
                case "set_variable":
                    rt.setVar(key.isEmpty() ? target : key, value);
                    return true;
                case "show_result": {
                    if (!target.isEmpty()) writeNodeText(rt, target, value.isEmpty() ? rt.getVar("__last_result") : value);
                    else toast(rt, value.isEmpty() ? rt.getVar("__last_result") : value);
                    return true;
                }
                case "open_dialog": {
                    String d = value.isEmpty() ? target : value;
                    if (d.startsWith("{")) {
                        DialogRuntime.showFromJson(rt.ctx(), d);
                    } else if (!d.isEmpty()) {
                        String nested = rt.dialogSpec(d);
                        if (nested != null) DialogRuntime.showFromJson(rt.ctx(), nested, true);
                        else DialogRuntime.showFromAsset(rt.ctx(), d, true);
                    }
                    return true;
                }
                case "update_screen":
                    return updateScreen(rt, a, target, key, value);
                case "notify_success":
                    toast(rt, value.isEmpty() ? rt.str("success", "Done") : value);
                    runEvents(rt, "success", "");
                    return true;
                case "notify_failure":
                    toast(rt, value.isEmpty() ? rt.str("failure", "Failed") : value);
                    runEvents(rt, "failure", "");
                    return false;
                case "dismiss":
                    if (rt.dialog() != null) rt.dialog().dismiss();
                    return true;
                case "open_url": {
                    String url = value.isEmpty() ? target : value;
                    if (!url.startsWith("https://") && !url.startsWith("http://")
                            && !url.startsWith("mailto:") && !url.startsWith("tel:")) {
                        Log.e(TAG, "blocked URL scheme: " + url);
                        return false;
                    }
                    if (!url.isEmpty()) {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        rt.ctx().startActivity(i);
                    }
                    return true;
                }
                case "copy_text": {
                    ClipboardManager cm = (ClipboardManager) rt.ctx()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dialog", value));
                    toast(rt, rt.str("copied", "Copied"));
                    return true;
                }
                case "toast":
                    toast(rt, value.isEmpty() ? target : value);
                    return true;
                case "hide_node":
                    return setVisibility(rt, target, View.GONE);
                case "show_node":
                    return setVisibility(rt, target, View.VISIBLE);
                case "refresh":
                    rt.refreshNodes(target);
                    return true;
                case "focus_node": {
                    View v = rt.views().get(target);
                    if (v != null) v.requestFocus();
                    return true;
                }
                default:
                    return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "action " + type + " failed", t);
            rt.setVar("__last_error", String.valueOf(t.getMessage()));
            return false;
        }
    }

    private static boolean updateScreen(DialogRuntime rt, JSONObject a, String target,
                                        String key, String value) {
        View v = rt.views().get(target);
        if (v == null) return false;
        if ("visible".equals(key)) {
            v.setVisibility("true".equals(value) ? View.VISIBLE : View.GONE);
        } else if ("progress".equals(key) && v instanceof ProgressBar) {
            ((ProgressBar) v).setProgress(parseInt(value, 0));
        } else if ("color".equals(key) && v instanceof TextView) {
            ((TextView) v).setTextColor(DialogRuntime.color(value, 0xFF000000));
        } else if ("alpha".equals(key)) {
            try {
                v.setAlpha(Float.parseFloat(value));
            } catch (Throwable ignored) {
            }
        } else {
            writeNodeText(rt, target, value);
        }
        return true;
    }

    private static boolean setVisibility(DialogRuntime rt, String nodeId, int visibility) {
        View v = rt.views().get(nodeId);
        if (v == null) return false;
        v.setVisibility(visibility);
        return true;
    }

    private static String readNodeValue(DialogRuntime rt, String nodeId) {
        View v = rt.views().get(nodeId);
        if (v == null) return rt.getVar(nodeId);
        if (v instanceof EditText) {
            Editable e = ((EditText) v).getText();
            return e == null ? "" : e.toString();
        }
        if (v instanceof CheckBox) return String.valueOf(((CheckBox) v).isChecked());
        if (v instanceof ProgressBar) return String.valueOf(((ProgressBar) v).getProgress());
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            return cs == null ? "" : cs.toString();
        }
        return "";
    }

    private static void writeNodeText(DialogRuntime rt, String nodeId, String text) {
        View v = rt.views().get(nodeId);
        if (v instanceof TextView) {
            ((TextView) v).setText(text == null ? "" : text);
        } else if (v instanceof ProgressBar) {
            ((ProgressBar) v).setProgress(parseInt(text, 0));
        }
    }

    private static boolean callFunction(DialogRuntime rt, String ref, JSONArray argArray) {
        JSONObject f = rt.functions().get(ref);
        if (f == null) return false;
        if (!f.optBoolean("isStatic", true) || !f.optBoolean("isPublic", true)
                || !f.optBoolean("safe", true)) {
            Log.e(TAG, "function rejected by whitelist: " + ref);
            return false;
        }
        String className = f.optString("className", "");
        String methodName = f.optString("methodName", "");
        JSONArray params = f.optJSONArray("params");
        if (className.isEmpty() || methodName.isEmpty() || params == null) return false;
        try {
            Class<?> cls = Class.forName(className, true, rt.ctx().getClassLoader());
            Class<?>[] types = new Class<?>[params.length()];
            Object[] args = new Object[params.length()];
            for (int i = 0; i < params.length(); i++) {
                String p = params.optString(i, "java.lang.String");
                types[i] = typeOf(p, rt.ctx().getClassLoader());
                String raw = argArray != null && i < argArray.length()
                        ? rt.resolve(argArray.optString(i, "")) : "";
                args[i] = convert(raw, types[i]);
            }
            java.lang.reflect.Method m = cls.getMethod(methodName, types);
            Object result = m.invoke(null, args);
            String out = result == null ? "" : String.valueOf(result);
            rt.setVar("__last_result", out);
            runEvents(rt, "success", "");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "callFunction failed " + className + "." + methodName, t);
            rt.setVar("__last_error", String.valueOf(t.getMessage()));
            runEvents(rt, "failure", "");
            return false;
        }
    }

    private static Class<?> typeOf(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name == null) return String.class;
        switch (name) {
            case "int":
            case "java.lang.Integer":
                return int.class;
            case "long":
            case "java.lang.Long":
                return long.class;
            case "float":
            case "java.lang.Float":
                return float.class;
            case "double":
            case "java.lang.Double":
                return double.class;
            case "boolean":
            case "java.lang.Boolean":
                return boolean.class;
            case "short":
            case "java.lang.Short":
                return short.class;
            case "byte":
            case "java.lang.Byte":
                return byte.class;
            case "char":
            case "java.lang.Character":
                return char.class;
            case "java.lang.String":
            case "String":
                return String.class;
            default:
                return Class.forName(name, true, loader);
        }
    }

    private static Object convert(String raw, Class<?> type) {
        if (type == int.class || type == Integer.class) return parseInt(raw, 0);
        if (type == long.class || type == Long.class) {
            try {
                return Long.parseLong(raw);
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        if (type == float.class || type == Float.class) {
            try {
                return Float.parseFloat(raw);
            } catch (Throwable ignored) {
                return 0f;
            }
        }
        if (type == double.class || type == Double.class) {
            try {
                return Double.parseDouble(raw);
            } catch (Throwable ignored) {
                return 0d;
            }
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true".equalsIgnoreCase(raw) || "1".equals(raw);
        }
        if (type == short.class || type == Short.class) {
            return (short) parseInt(raw, 0);
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) parseInt(raw, 0);
        }
        if (type == char.class || type == Character.class) {
            return raw == null || raw.isEmpty() ? '\0' : raw.charAt(0);
        }
        return raw;
    }

    private static boolean eval(DialogRuntime rt, String expression) {
        if (expression == null) return true;
        String e = expression.trim();
        if (e.isEmpty()) return true;
        if (e.contains("||")) {
            for (String part : e.split("\\|\\|")) if (eval(rt, part)) return true;
            return false;
        }
        if (e.contains("&&")) {
            for (String part : e.split("&&")) if (!eval(rt, part)) return false;
            return true;
        }
        if (e.startsWith("!")) return !eval(rt, e.substring(1));
        String[] ops = {">=", "<=", "!=", "==", ">", "<"};
        for (String op : ops) {
            int idx = e.indexOf(op);
            if (idx < 0) continue;
            String left = clean(rt.resolve(e.substring(0, idx)));
            String right = clean(rt.resolve(e.substring(idx + op.length())));
            int cmp = compare(left, right);
            switch (op) {
                case ">=":
                    return cmp >= 0;
                case "<=":
                    return cmp <= 0;
                case "!=":
                    return cmp != 0;
                case "==":
                    return cmp == 0;
                case ">":
                    return cmp > 0;
                case "<":
                    return cmp < 0;
                default:
                    break;
            }
        }
        String v = clean(rt.resolve(e));
        return "true".equalsIgnoreCase(v) || "1".equals(v)
                || (!v.isEmpty() && !"false".equalsIgnoreCase(v) && !"0".equals(v));
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

    private static int compare(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (Throwable ignored) {
            return a.compareTo(b);
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void toast(DialogRuntime rt, String text) {
        try {
            Toast.makeText(rt.ctx(), text == null ? "" : text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
