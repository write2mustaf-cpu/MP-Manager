package com.mp.dialogengine;

import android.content.Context;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

import com.apk.axml.ResourceTableParser;
import com.apk.axml.aXMLDecoder;
import com.apk.axml.serializableItems.ResEntry;

import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Descriptor;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.FunctionRef;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;
import com.mp.dialogengine.DialogModels.ScanResult;
import com.mp.dialogengine.DialogModels.SourceRef;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans an APK for dialog definitions in AXML layouts and dex/smali call sites,
 * and builds the index of callable public static functions used by the bridge.
 */
public final class DialogSourceScanner {

    public interface Progress {
        void onProgress(String message, int current, int total);
    }

    private static final int MAX_LAYOUTS = 4000;
    private static final int MAX_DESCRIPTORS = 1200;
    private static final int MAX_FUNCTIONS = 6000;

    private static final Set<String> DIALOG_METHODS = new HashSet<>(Arrays.asList(
            "setTitle", "setMessage", "setPositiveButton", "setNegativeButton",
            "setNeutralButton", "setView", "setContentView", "setItems",
            "setMultiChoiceItems", "setSingleChoiceItems", "setAdapter",
            "setCustomTitle", "setIcon", "show", "<init>"));

    private DialogSourceScanner() {
    }

    public static ScanResult scan(Context context, File apk, Progress progress) {
        long start = System.currentTimeMillis();
        ScanResult result = new ScanResult();
        result.apkPath = apk == null ? "" : apk.getAbsolutePath();
        if (apk == null || !apk.isFile()) {
            result.warnings.add("APK غير موجود");
            return result;
        }
        File work = new File(context.getFilesDir(),
                "dialog_engine/work/" + Integer.toHexString(apk.getAbsolutePath().hashCode()));
        deleteTree(work);
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        result.workDir = work.getAbsolutePath();
        File layoutDir = new File(work, "layouts");
        //noinspection ResultOfMethodCallIgnored
        layoutDir.mkdirs();
        List<ResEntry> resources = readResources(apk);
        Map<String, String> resourceMap = buildResourceMap(resources);

        try (ZipFile zip = new ZipFile(apk)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            int total = 0;
            for (ZipEntry e : entries) if (isLayout(e.getName())) total++;
            int current = 0;
            for (ZipEntry e : entries) {
                String name = e.getName();
                if (isLayout(name)) {
                    current++;
                    if (current > MAX_LAYOUTS) {
                        result.warnings.add("تم تجاهل بقية ملفات XML بعد " + MAX_LAYOUTS);
                        break;
                    }
                    if (progress != null) progress.onProgress("فحص " + name, current, total);
                    try {
                        byte[] bytes = readAll(zip.getInputStream(e));
                        String xml;
                        try {
                            xml = new aXMLDecoder(new ByteArrayInputStream(bytes), resources)
                                    .decodeAsString();
                        } catch (Throwable t) {
                            xml = new String(bytes, StandardCharsets.UTF_8);
                        }
                        if (xml == null || xml.isEmpty()) continue;
                        File saved = new File(layoutDir,
                                DialogJson.safeName(name.replace('/', '_')));
                        DialogJson.writeFile(saved, xml);
                        result.layoutCount++;
                        if (result.dialogs.size() >= MAX_DESCRIPTORS) continue;
                        if (!DialogXmlParser.looksLikeDialog(xml, name)) continue;
                        Model model = DialogXmlParser.parse(xml, name, saved.getAbsolutePath(),
                                name, value -> resolveResource(value, resourceMap));
                        if (model == null) continue;
                        Descriptor d = new Descriptor();
                        d.id = model.id;
                        d.name = model.name;
                        d.screen = model.sourceRef.path;
                        d.type = "xml";
                        d.title = model.title;
                        d.message = model.message;
                        d.confidence = 0.9f;
                        d.sourceRef = model.sourceRef;
                        d.model = model;
                        result.dialogs.add(d);
                    } catch (Throwable t) {
                        result.warnings.add("تعذر فك " + name + ": " + t.getMessage());
                    }
                } else if (isDex(name)) {
                    result.dexCount++;
                    try {
                        byte[] bytes = readAll(zip.getInputStream(e));
                        DexBackedDexFile dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                        indexFunctions(dex, name, result);
                        findDialogCallSites(dex, name, result, progress);
                    } catch (Throwable t) {
                        result.warnings.add("تعذر فحص " + name + ": " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            result.warnings.add("فشل فتح APK: " + t.getMessage());
        }
        result.durationMs = System.currentTimeMillis() - start;
        Collections.sort(result.dialogs, Comparator
                .comparing((Descriptor d) -> d.type == null ? "" : d.type)
                .thenComparing(d -> d.name == null ? "" : d.name));
        return result;
    }

    private static List<ResEntry> readResources(File apk) {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry("resources.arsc");
            if (e == null) return Collections.emptyList();
            try (InputStream in = zip.getInputStream(e)) {
                return new ResourceTableParser(in).parse();
            }
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static Map<String, String> buildResourceMap(List<ResEntry> entries) {
        Map<String, String> map = new HashMap<>();
        if (entries == null) return map;
        for (ResEntry e : entries) {
            if (e == null) continue;
            if (e.getName() != null) map.put(e.getName(), e.getResAttr());
            String value = e.getValue();
            if (value != null) {
                if (value.startsWith("#")) map.putIfAbsent(value, value);
            }
        }
        return map;
    }

    private static String resolveResource(String value, Map<String, String> map) {
        if (value == null || value.isEmpty()) return value;
        String v = value;
        if (v.startsWith("@string/") || v.startsWith("@android:string/")) {
            String key = v.substring(v.lastIndexOf('/') + 1);
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey().endsWith(key)) return e.getValue();
            }
        } else if (v.startsWith("@color/") || v.startsWith("@android:color/")) {
            String key = v.substring(v.lastIndexOf('/') + 1);
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey().endsWith(key) && e.getValue().startsWith("#")) return e.getValue();
            }
        }
        if (v.startsWith("@") || v.startsWith("?")) return "";
        return v;
    }

    private static void indexFunctions(DexBackedDexFile dex, String entryName, ScanResult result) {
        if (result.functions.size() >= MAX_FUNCTIONS) return;
        for (ClassDef c : dex.getClasses()) {
            if (c == null || c.getType() == null) continue;
            String className = c.getType();
            if (className.startsWith("Landroid/") || className.startsWith("Ljava/")
                    || className.startsWith("Landroidx/") || className.startsWith("Lkotlin/")) {
                continue;
            }
            for (Method m : c.getMethods()) {
                if (m == null) continue;
                String name = m.getName();
                if (name == null || name.startsWith("<")) continue;
                int flags = m.getAccessFlags();
                if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
                if (AccessFlags.SYNTHETIC.isSet(flags) || AccessFlags.BRIDGE.isSet(flags)) continue;
                boolean safe = isSafeName(name);
                List<String> params = new ArrayList<>();
                for (MethodParameter p : m.getParameters()) {
                    params.add(p.getType());
                    if (!allowedParam(p.getType())) safe = false;
                }
                String ret = m.getReturnType();
                if (!allowedReturn(ret)) safe = false;
                FunctionRef f = new FunctionRef();
                f.className = descriptorToClass(className);
                f.methodName = name;
                f.descriptor = descriptor(m);
                f.params = params;
                f.returnType = ret;
                f.isStatic = true;
                f.isPublic = true;
                f.safe = safe;
                f.sourceEntry = entryName;
                f.displayName = simpleName(f.className) + "." + name + "(" + readable(params) + ")";
                f.reason = safe ? "public static; safe name and whitelist parameters/return"
                        : "خارج القائمة البيضاء (اسم أو نوع معامل/إرجاع غير مسموح)";
                result.functions.add(f);
                if (result.functions.size() >= MAX_FUNCTIONS) return;
            }
        }
    }

    private static void findDialogCallSites(DexBackedDexFile dex, String entryName,
                                            ScanResult result, Progress progress) {
        if (result.dialogs.size() >= MAX_DESCRIPTORS) return;
        for (ClassDef c : dex.getClasses()) {
            if (c == null || c.getType() == null) continue;
            boolean dialogFragment = c.getSuperclass() != null
                    && (c.getSuperclass().contains("DialogFragment")
                    || c.getSuperclass().contains("BottomSheet"));
            for (Method m : c.getMethods()) {
                if (m == null || m.getImplementation() == null) continue;
                List<Instruction> ins = instructions(m);
                if (ins.isEmpty()) continue;
                String title = "", message = "", pos = "", neg = "", neu = "";
                boolean hit = dialogFragment;
                for (int i = 0; i < ins.size(); i++) {
                    Instruction in = ins.get(i);
                    if (!(in instanceof ReferenceInstruction ri)) continue;
                    if (!(ri.getReference() instanceof MethodReference mr)) continue;
                    String def = mr.getDefiningClass();
                    if (def == null) continue;
                    String name = mr.getName();
                    boolean dialogClass = def.contains("AlertDialog") || def.contains("BottomSheet")
                            || def.contains("DialogFragment") || def.contains("PopupWindow")
                            || def.endsWith("Landroid/app/Dialog;")
                            || def.contains("MaterialAlertDialog");
                    if (!dialogClass || name == null || !DIALOG_METHODS.contains(name)) continue;
                    hit = true;
                    String s = nearestStringBefore(ins, i, 40);
                    if ("setTitle".equals(name) && title.isEmpty()) title = s;
                    else if ("setMessage".equals(name) && message.isEmpty()) message = s;
                    else if ("setPositiveButton".equals(name) && pos.isEmpty()) pos = s;
                    else if ("setNegativeButton".equals(name) && neg.isEmpty()) neg = s;
                    else if ("setNeutralButton".equals(name) && neu.isEmpty()) neu = s;
                }
                if (!hit) continue;
                if (result.dialogs.size() >= MAX_DESCRIPTORS) return;
                Descriptor d = new Descriptor();
                d.type = "smali";
                d.name = simpleName(descriptorToClass(c.getType())) + "." + m.getName();
                d.screen = descriptorToClass(c.getType());
                d.title = title == null ? "" : title;
                d.message = message == null ? "" : message;
                if (pos != null && !pos.isEmpty()) d.buttons.add(pos);
                if (neg != null && !neg.isEmpty()) d.buttons.add(neg);
                if (neu != null && !neu.isEmpty()) d.buttons.add(neu);
                d.confidence = hit && dialogFragment ? 0.85f : 0.7f;
                SourceRef r = new SourceRef();
                r.kind = "smali";
                r.container = entryName;
                r.className = d.screen;
                r.methodName = m.getName();
                r.methodDescriptor = descriptor(m);
                r.snippet = d.name + " " + r.methodDescriptor;
                d.sourceRef = r;
                d.model = modelFromCallSite(d);
                result.dialogs.add(d);
                if (progress != null) {
                    progress.onProgress("فحص " + d.name, result.dialogs.size(), MAX_DESCRIPTORS);
                }
            }
        }
    }

    private static Model modelFromCallSite(Descriptor d) {
        Model m = new Model();
        m.name = d.name;
        m.screen = d.screen;
        m.title = d.title == null ? "" : d.title;
        m.message = d.message == null ? "" : d.message;
        m.sourceRef = d.sourceRef;
        m.metadata.put("extracted", "true");
        if (m.title.isEmpty() && m.message.isEmpty() && d.buttons.isEmpty()) {
            Node n = new Node();
            n.kind = "text";
            n.text = "Dialog";
            m.nodes.add(n);
        }
        for (int i = 0; i < d.buttons.size(); i++) {
            Node b = new Node();
            b.kind = "button";
            b.text = d.buttons.get(i);
            b.id = "button" + (i + 1);
            m.nodes.add(b);
            Event e = new Event();
            e.trigger = "click";
            e.nodeId = b.id;
            Action a = new Action();
            a.type = "dismiss";
            e.actions.add(a);
            m.events.add(e);
            b.eventIds.add(e.id);
        }
        return m;
    }

    private static List<Instruction> instructions(Method m) {
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return Collections.emptyList();
        List<Instruction> out = new ArrayList<>();
        for (Instruction i : impl.getInstructions()) out.add(i);
        return out;
    }

    private static String nearestStringBefore(List<Instruction> ins, int index, int window) {
        int from = Math.max(0, index - window);
        for (int i = index - 1; i >= from; i--) {
            Instruction in = ins.get(i);
            if (in instanceof ReferenceInstruction ri
                    && ri.getReference() instanceof StringReference sr) {
                return sr.getString();
            }
        }
        return "";
    }

    private static boolean isLayout(String name) {
        return name != null && name.startsWith("res/layout") && name.endsWith(".xml");
    }

    private static boolean isDex(String name) {
        return name != null && name.matches("classes[0-9]*\\.dex");
    }

    private static final String[] UNSAFE_NAMES = {
            "delete", "remove", "clear", "wipe", "format", "reset", "factory",
            "uninstall", "kill", "exit", "shutdown", "write", "save", "commit",
            "exec", "run", "eval", "load", "install", "update", "drop", "truncate",
            "erase", "destroy", "revoke", "disable", "hide", "block", "ban",
            "logout", "signout", "purge", "overwrite", "move", "rename", "chmod",
            "chown", "grant", "request", "pay", "purchase", "order"
    };

    private static final String[] SAFE_PREFIXES = {
            "show", "open", "close", "dismiss", "toast", "copy", "share", "refresh",
            "reload", "set", "get", "is", "has", "toggle", "switch", "launch",
            "start", "select", "pick", "search", "filter", "sort", "navigate",
            "go", "send", "check", "validate", "apply", "change", "call"
    };

    private static boolean isSafeName(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String bad : UNSAFE_NAMES) {
            if (n.contains(bad)) return false;
        }
        for (String prefix : SAFE_PREFIXES) {
            if (n.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean allowedParam(String type) {
        if (type == null) return false;
        return "Z".equals(type) || "B".equals(type) || "S".equals(type)
                || "C".equals(type) || "I".equals(type) || "J".equals(type)
                || "F".equals(type) || "D".equals(type)
                || "Ljava/lang/String;".equals(type)
                || "Ljava/lang/CharSequence;".equals(type);
    }

    private static boolean allowedReturn(String type) {
        if (type == null) return false;
        if ("V".equals(type)) return true;
        return allowedParam(type);
    }

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (MethodParameter p : m.getParameters()) sb.append(p.getType());
        sb.append(')').append(m.getReturnType());
        return sb.toString();
    }

    private static String descriptorToClass(String descriptor) {
        if (descriptor == null) return "";
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return descriptor;
    }

    private static String simpleName(String className) {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String readable(List<String> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeName(params.get(i)));
        }
        return sb.toString();
    }

    private static String typeName(String d) {
        if (d == null) return "?";
        switch (d) {
            case "Z": return "boolean";
            case "B": return "byte";
            case "S": return "short";
            case "C": return "char";
            case "I": return "int";
            case "J": return "long";
            case "F": return "float";
            case "D": return "double";
            case "V": return "void";
            default:
                if (d.startsWith("L") && d.endsWith(";")) {
                    String c = d.substring(1, d.length() - 1).replace('/', '.');
                    int dot = c.lastIndexOf('.');
                    return dot >= 0 ? c.substring(dot + 1) : c;
                }
                return d;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
