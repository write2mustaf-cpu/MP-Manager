package com.mp.dialogengine;

import android.content.Context;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.abdurazaaqmohammed.MPManager.R;
import com.reandroid.apk.APKLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Injects a self-contained runtime dex plus a JSON spec into a target APK.
 * The original APK is never modified; a new APK is written next to it.
 */
public final class DialogRuntimeInjector {

    private static final String RUNTIME_PREFIX = "Lcom/mp/dialogruntime/";
    private static final String RUNTIME_CLASS = "Lcom/mp/dialogruntime/DialogRuntime;";

    private DialogRuntimeInjector() {
    }

    public static File inject(Context context, File inputApk,
                              List<String> activityClassNames,
                              DialogModels.Project project,
                              DialogModels.Model model,
                              APKLogger logger) throws Exception {
        if (inputApk == null || !inputApk.isFile()) throw new IOException(context.getString(R.string.de_apk_missing));
        if (activityClassNames == null || activityClassNames.isEmpty()) {
            throw new IOException(context.getString(R.string.de_no_activity));
        }
        String assetName = "mp_dialog_" + DialogJson.safeName(model.id) + ".json";
        String spec = buildSpec(context, project, model);
        File work = new File(context.getCacheDir(), "mp_dialog_inject_" + System.nanoTime());
        if (!work.mkdirs()) throw new IOException("Cannot create work directory");
        try {
            java.util.List<File> sources = new ArrayList<>();
            sources.add(new File(context.getApplicationInfo().sourceDir));
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (splits != null) {
                for (String split : splits) {
                    if (split != null && !split.isEmpty()) sources.add(new File(split));
                }
            }
            byte[] runtimeDex = buildRuntimeDex(sources.toArray(new File[0]));
            Map<String, byte[]> patched = patchActivities(inputApk,
                    activityClassNames, assetName, model.hookMethod, logger);
            if (patched.isEmpty()) throw new IOException("Could not patch any selected activity");
            File output = unusedOutput(inputApk);
            writeApk(inputApk, output, patched, runtimeDex, assetName, spec);
            return output;
        } finally {
            deleteTree(work);
        }
    }

    public static String buildSpec(DialogModels.Project project, DialogModels.Model model) {
        return buildSpec(null, project, model);
    }

    public static String buildSpec(Context context, DialogModels.Project project, DialogModels.Model model) {
        JsonObject spec = DialogJson.gson().toJsonTree(model).getAsJsonObject();
        JsonArray functions = new JsonArray();
        if (project != null && project.functions != null) {
            Set<String> used = new HashSet<>();
            if (model.events != null) {
                for (DialogModels.Event e : model.events) {
                    if (e == null || e.actions == null) continue;
                    for (DialogModels.Action a : e.actions) {
                        if (a != null && a.functionRef != null && !a.functionRef.isEmpty()) {
                            used.add(a.functionRef);
                        }
                    }
                }
            }
            for (DialogModels.Node n : flatten(model.nodes)) {
                if (n != null && n.functionRef != null && !n.functionRef.isEmpty()) used.add(n.functionRef);
            }
            for (DialogModels.FunctionRef f : project.functions) {
                if (f != null && used.contains(f.id)) {
                    functions.add(DialogJson.gson().toJsonTree(f));
                }
            }
        }
        spec.add("functions", functions);
        if (!spec.has("metadata")) spec.add("metadata", new JsonObject());
        JsonObject strings = new JsonObject();
        strings.addProperty("search", context == null ? "Search"
                : context.getString(R.string.de_search_hint));
        strings.addProperty("copied", context == null ? "Copied" : context.getString(R.string.de_success));
        strings.addProperty("success", context == null ? "Done" : context.getString(R.string.de_success));
        strings.addProperty("failure", context == null ? "Failed" : context.getString(R.string.de_error));
        strings.addProperty("dragHandle", "⋮⋮");
        strings.addProperty("ok", context == null ? "OK" : context.getString(R.string.de_ok));
        strings.addProperty("cancel", context == null ? "Cancel" : context.getString(R.string.de_cancel));
        strings.addProperty("close", context == null ? "Close" : context.getString(R.string.de_close));
        spec.add("strings", strings);
        return DialogJson.gson().toJson(spec);
    }

    private static List<DialogModels.Node> flatten(List<DialogModels.Node> nodes) {
        List<DialogModels.Node> out = new ArrayList<>();
        if (nodes == null) return out;
        for (DialogModels.Node n : nodes) {
            if (n == null) continue;
            out.add(n);
            out.addAll(flatten(n.children));
        }
        return out;
    }

    static byte[] buildRuntimeDex(File self) throws Exception {
        return buildRuntimeDex(new File[]{self});
    }

    static byte[] buildRuntimeDex(File[] sources) throws Exception {
        List<ClassDef> runtime = new ArrayList<>();
        for (File self : sources) {
            if (self == null || !self.isFile()) continue;
            try (ZipFile zip = new ZipFile(self)) {
                java.util.Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!isDex(e.getName())) continue;
                    byte[] bytes = readAll(zip.getInputStream(e));
                    DexBackedDexFile dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                    for (ClassDef c : dex.getClasses()) {
                        if (c.getType() != null && c.getType().startsWith(RUNTIME_PREFIX)) {
                            runtime.add(c);
                        }
                    }
                }
            }
        }
        if (runtime.isEmpty()) {
            throw new IOException("Runtime classes not found in the host APK");
        }
        Opcodes opcodes = Opcodes.forApi(23);
        DexPool pool = new DexPool(opcodes);
        for (ClassDef c : runtime) pool.internClass(c);
        MemoryDataStore store = new MemoryDataStore();
        pool.writeTo(store);
        return Arrays.copyOf(store.getData(), store.getSize());
    }

    static Map<String, byte[]> patchActivities(File inputApk,
                                                       List<String> activityClassNames,
                                                       String assetName, String hookMethod,
                                                       APKLogger logger) throws Exception {
        Set<String> descriptors = new LinkedHashSet<>();
        for (String name : activityClassNames) {
            if (name != null && !name.isEmpty()) {
                descriptors.add("L" + name.replace('.', '/') + ";");
            }
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(inputApk)) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!isDex(e.getName())) continue;
                byte[] bytes = readAll(zip.getInputStream(e));
                DexBackedDexFile dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                List<ClassDef> classes = new ArrayList<>();
                boolean changed = false;
                for (ClassDef c : dex.getClasses()) {
                    if (descriptors.contains(c.getType())) {
                        ClassDef patched = patchActivity(c, assetName, hookMethod);
                        if (patched != null) {
                            classes.add(patched);
                            changed = true;
                            if (logger != null) {
                                logger.logMessage("Dialog engine: hooked " + c.getType()
                                        + " (" + hookMethod + ")");
                            }
                            continue;
                        }
                    }
                    classes.add(c);
                }
                if (changed) {
                    DexPool pool = new DexPool(Opcodes.getDefault());
                    for (ClassDef c : classes) pool.internClass(c);
                    MemoryDataStore store = new MemoryDataStore();
                    pool.writeTo(store);
                    out.put(e.getName(), Arrays.copyOf(store.getData(), store.getSize()));
                }
            }
        }
        return out;
    }

    private static ClassDef patchActivity(ClassDef classDef, String assetName, String hook) {
        boolean resume = "onResume".equals(hook);
        String sig = resume ? "()V" : "(Landroid/os/Bundle;)V";
        String name = resume ? "onResume" : "onCreate";
        String renamed = name + "$mpdialog";
        int guard = 2;
        while (hasMethod(classDef, renamed, sig)) {
            renamed = name + "$mpdialog" + guard;
            guard++;
        }
        List<Method> direct = new ArrayList<>();
        List<Method> virtual = new ArrayList<>();
        boolean found = false;
        for (Method m : classDef.getDirectMethods()) {
            if (name.equals(m.getName()) && sig.equals(descriptorOf(m))) {
                if (m.getImplementation() == null) {
                    direct.add(m);
                    continue;
                }
                direct.add(rename(m, renamed));
                direct.add(wrapper(m, classDef, name, renamed, assetName, resume));
                found = true;
            } else {
                direct.add(m);
            }
        }
        for (Method m : classDef.getVirtualMethods()) {
            if (name.equals(m.getName()) && sig.equals(descriptorOf(m))) {
                if (m.getImplementation() == null) {
                    virtual.add(m);
                    continue;
                }
                direct.add(rename(m, renamed));
                virtual.add(wrapper(m, classDef, name, renamed, assetName, resume));
                found = true;
            } else {
                virtual.add(m);
            }
        }
        if (!found) {
            virtual.add(newMethod(classDef, name, resume, assetName));
        }
        return new ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getStaticFields(),
                classDef.getInstanceFields(),
                direct,
                virtual);
    }

    private static boolean hasMethod(ClassDef classDef, String name, String sig) {
        for (Method m : classDef.getMethods()) {
            if (name.equals(m.getName()) && sig.equals(descriptorOf(m))) return true;
        }
        return false;
    }
    private static Method rename(Method original, String renamed) {
        return new ImmutableMethod(
                original.getDefiningClass(),
                renamed,
                original.getParameters(),
                original.getReturnType(),
                AccessFlags.PRIVATE.getValue(),
                original.getAnnotations(),
                original.getHiddenApiRestrictions(),
                ImmutableMethodImplementation.of(original.getImplementation()));
    }

    private static Method wrapper(Method original, ClassDef classDef, String name,
                                  String renamed, String assetName, boolean resume) {
        int p0 = resume ? 1 : 1;
        int p1 = resume ? -1 : 2;
        int v0 = 0;
        int regCount = resume ? 2 : 3;
        List<Instruction> instructions = new ArrayList<>();
        List<String> params = new ArrayList<>();
        if (!resume) params.add("Landroid/os/Bundle;");
        ImmutableMethodReference renamedRef = new ImmutableMethodReference(
                classDef.getType(), renamed, params, "V");
        if (resume) {
            instructions.add(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1,
                    p0, 0, 0, 0, 0, renamedRef));
        } else {
            instructions.add(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2,
                    p0, p1, 0, 0, 0, renamedRef));
        }
        instructions.add(new BuilderInstruction21c(Opcode.CONST_STRING, v0,
                new ImmutableStringReference(assetName)));
        instructions.add(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                p0, v0, 0, 0, 0, new ImmutableMethodReference(
                        RUNTIME_CLASS, "showFromAsset",
                        Arrays.asList("Landroid/content/Context;", "Ljava/lang/String;"), "V")));
        instructions.add(new BuilderInstruction10x(Opcode.RETURN_VOID));
        ImmutableMethodImplementation impl = new ImmutableMethodImplementation(
                regCount, instructions, null, null);
        return new ImmutableMethod(
                classDef.getType(),
                name,
                original.getParameters(),
                original.getReturnType(),
                original.getAccessFlags(),
                original.getAnnotations(),
                original.getHiddenApiRestrictions(),
                impl);
    }

    private static Method newMethod(ClassDef classDef, String name, boolean resume,
                                    String assetName) {
        int regCount = resume ? 2 : 3;
        int p0 = 1;
        int p1 = 2;
        int v0 = 0;
        List<Instruction> instructions = new ArrayList<>();
        String superType = classDef.getSuperclass();
        if (superType == null) superType = "Ljava/lang/Object;";
        if (resume) {
            instructions.add(new BuilderInstruction35c(Opcode.INVOKE_SUPER, 1,
                    p0, 0, 0, 0, 0, new ImmutableMethodReference(
                            superType, "onResume", Collections.emptyList(), "V")));
        } else {
            instructions.add(new BuilderInstruction35c(Opcode.INVOKE_SUPER, 2,
                    p0, p1, 0, 0, 0, new ImmutableMethodReference(
                            superType, "onCreate",
                            Collections.singletonList("Landroid/os/Bundle;"), "V")));
        }
        instructions.add(new BuilderInstruction21c(Opcode.CONST_STRING, v0,
                new ImmutableStringReference(assetName)));
        instructions.add(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2,
                p0, v0, 0, 0, 0, new ImmutableMethodReference(
                        RUNTIME_CLASS, "showFromAsset",
                        Arrays.asList("Landroid/content/Context;", "Ljava/lang/String;"), "V")));
        instructions.add(new BuilderInstruction10x(Opcode.RETURN_VOID));
        ImmutableMethodImplementation impl = new ImmutableMethodImplementation(
                regCount, instructions, null, null);
        List<com.android.tools.smali.dexlib2.iface.MethodParameter> params = new ArrayList<>();
        if (!resume) {
            params.add(new com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(
                    "Landroid/os/Bundle;", Collections.emptySet(), "savedInstanceState"));
        }
        return new ImmutableMethod(
                classDef.getType(),
                name,
                params,
                "V",
                AccessFlags.PUBLIC.getValue(),
                Collections.emptySet(),
                Collections.emptySet(),
                impl);
    }

    private static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (MethodParameter p : m.getParameters()) sb.append(p.getType());
        sb.append(')').append(m.getReturnType());
        return sb.toString();
    }

    static void writeApk(File input, File output, Map<String, byte[]> replacements,
                                 byte[] runtimeDex, String assetName, String spec) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(input)) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) names.add(en.nextElement().getName());
        }
        boolean addRuntime = !containsRuntimeClass(input);
        String runtimeName = addRuntime ? nextDexName(names) : null;
        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("META-INF/") && (n.equals("META-INF/MANIFEST.MF")
                        || n.endsWith(".SF") || n.endsWith(".RSA")
                        || n.endsWith(".DSA") || n.endsWith(".EC"))) {
                    continue;
                }
                if (runtimeName != null && n.equals(runtimeName)) continue;
                ZipEntry outEntry = new ZipEntry(n);
                outEntry.setTime(e.getTime());
                byte[] patched = replacements.get(n);
                if (patched != null) {
                    outEntry.setMethod(ZipEntry.DEFLATED);
                } else {
                    outEntry.setMethod(e.getMethod());
                    if (e.getMethod() == ZipEntry.STORED) {
                        outEntry.setSize(e.getSize());
                        outEntry.setCrc(e.getCrc());
                    }
                }
                out.putNextEntry(outEntry);
                if (patched != null) {
                    out.write(patched);
                } else {
                    copy(zip.getInputStream(e), out);
                }
                out.closeEntry();
            }
            if (runtimeName != null) {
                ZipEntry dex = new ZipEntry(runtimeName);
                out.putNextEntry(dex);
                out.write(runtimeDex);
                out.closeEntry();
            }
            ZipEntry asset = new ZipEntry("assets/" + assetName);
            out.putNextEntry(asset);
            out.write(spec.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    static boolean containsRuntimeClass(File input) {
        try (ZipFile zip = new ZipFile(input)) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!isDex(e.getName())) continue;
                byte[] bytes = readAll(zip.getInputStream(e));
                DexBackedDexFile dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                for (ClassDef c : dex.getClasses()) {
                    if (RUNTIME_CLASS.equals(c.getType())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String nextDexName(Set<String> names) {
        int n = 1;
        while (true) {
            String name = n == 1 ? "classes.dex" : "classes" + n + ".dex";
            if (!names.contains(name)) {
                // If classes.dex exists, start at 2.
                if (n == 1 && names.contains("classes.dex")) {
                    n++;
                    continue;
                }
                return name;
            }
            n++;
        }
    }

    private static File unusedOutput(File input) {
        File dir = input.getParentFile();
        String base = input.getName();
        if (base.toLowerCase().endsWith(".apk")) base = base.substring(0, base.length() - 4);
        File out = new File(dir, base + "_dialogengine.apk");
        int i = 2;
        while (out.exists()) {
            out = new File(dir, base + "_dialogengine_" + i + ".apk");
            i++;
        }
        return out;
    }

    private static boolean isDex(String name) {
        return name != null && name.matches("classes[0-9]*\\.dex");
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

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream is = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
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
