package com.mp.dialogengine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DialogRuntimeInjectorTest {

    private File findApk() {
        List<File> candidates = new ArrayList<>();
        candidates.add(new File("build/outputs/apk/full/debug/app-full-debug.apk"));
        candidates.add(new File("app/build/outputs/apk/full/debug/app-full-debug.apk"));
        for (File f : candidates) if (f.isFile()) return f;
        return null;
    }

    @Test
    public void mergesRuntimeClassesFromSplitDex() throws Exception {
        File apk = findApk();
        Assume.assumeTrue("debug APK must be built first", apk != null);
        byte[] dex = DialogRuntimeInjector.buildRuntimeDex(apk);
        assertTrue(dex.length > 0);
        DexBackedDexFile parsed = new DexBackedDexFile(Opcodes.getDefault(), dex);
        Set<String> types = new HashSet<>();
        for (ClassDef c : parsed.getClasses()) types.add(c.getType());
        assertTrue(types.contains("Lcom/mp/dialogruntime/DialogRuntime;"));
        assertTrue(types.contains("Lcom/mp/dialogruntime/DialogRuntimeActions;"));
    }

    @Test
    public void wrapsActivityOnCreate() throws Exception {
        File apk = findApk();
        Assume.assumeTrue("debug APK must be built first", apk != null);
        Map<String, byte[]> patched = DialogRuntimeInjector.patchActivities(apk,
                Collections.singletonList("io.github.abdurazaaqmohammed.ui.main.MainActivity"),
                "mp_dialog_test.json", "onCreate", null);
        assertFalse(patched.isEmpty());
        boolean foundRenamed = false;
        boolean foundWrapper = false;
        for (byte[] dex : patched.values()) {
            DexBackedDexFile parsed = new DexBackedDexFile(Opcodes.getDefault(), dex);
            for (ClassDef c : parsed.getClasses()) {
                if (!"Lio/github/abdurazaaqmohammed/ui/main/MainActivity;".equals(c.getType())) continue;
                for (Method m : c.getMethods()) {
                    if ("onCreate$mpdialog".equals(m.getName())) foundRenamed = true;
                    if ("onCreate".equals(m.getName())) foundWrapper = true;
                }
            }
        }
        assertTrue(foundRenamed);
        assertTrue(foundWrapper);
    }

    @Test
    public void repeatedInjectionKeepsUniqueHookNames() throws Exception {
        File apk = findApk();
        Assume.assumeTrue("debug APK must be built first", apk != null);
        File mini = File.createTempFile("dialog_mini2", ".apk");
        File first = File.createTempFile("dialog_first", ".apk");
        File second = File.createTempFile("dialog_second", ".apk");
        try {
            copyWithoutRuntime(apk, mini);
            byte[] runtime = DialogRuntimeInjector.buildRuntimeDex(apk);
            Map<String, byte[]> p1 = DialogRuntimeInjector.patchActivities(mini,
                    Collections.singletonList("io.github.abdurazaaqmohammed.ui.main.MainActivity"),
                    "mp_dialog_one.json", "onCreate", null);
            DialogRuntimeInjector.writeApk(mini, first, p1, runtime,
                    "mp_dialog_one.json", "{\"id\":\"one\"}");
            Map<String, byte[]> p2 = DialogRuntimeInjector.patchActivities(first,
                    Collections.singletonList("io.github.abdurazaaqmohammed.ui.main.MainActivity"),
                    "mp_dialog_two.json", "onCreate", null);
            assertFalse(p2.isEmpty());
            DialogRuntimeInjector.writeApk(first, second, p2, runtime,
                    "mp_dialog_two.json", "{\"id\":\"two\"}");
            List<String> names = new ArrayList<>();
            try (ZipFile zip = new ZipFile(second)) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (!e.getName().startsWith("classes") || !e.getName().endsWith(".dex")) continue;
                    DexBackedDexFile parsed = new DexBackedDexFile(Opcodes.getDefault(),
                            readAll(zip.getInputStream(e)));
                    for (ClassDef c : parsed.getClasses()) {
                        if (!"Lio/github/abdurazaaqmohammed/ui/main/MainActivity;".equals(c.getType())) continue;
                        for (Method m : c.getMethods()) {
                            String n = m.getName();
                            if (n.startsWith("onCreate")) names.add(n + descriptorOf(m));
                        }
                    }
                }
                assertNotNull(zip.getEntry("assets/mp_dialog_one.json"));
                assertNotNull(zip.getEntry("assets/mp_dialog_two.json"));
            }
            assertTrue(names.contains("onCreate(Landroid/os/Bundle;)V"));
            assertTrue(names.contains("onCreate$mpdialog(Landroid/os/Bundle;)V"));
            Set<String> unique = new HashSet<>(names);
            assertTrue("no duplicate method signatures", unique.size() == names.size());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            mini.delete();
            //noinspection ResultOfMethodCallIgnored
            first.delete();
            //noinspection ResultOfMethodCallIgnored
            second.delete();
        }
    }

    private static void copyWithoutRuntime(File apk, File target) throws Exception {
        try (ZipFile zip = new ZipFile(apk);
             java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(target))) {
            zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zos.write(new byte[]{0, 1, 2, 3});
            zos.closeEntry();
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (!e.getName().startsWith("classes") || !e.getName().endsWith(".dex")) continue;
                byte[] bytes = readAll(zip.getInputStream(e));
                DexBackedDexFile parsed = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                List<ClassDef> keep = new ArrayList<>();
                boolean hasRuntime = false;
                for (ClassDef c : parsed.getClasses()) {
                    if (c.getType().startsWith("Lcom/mp/dialogruntime/")) {
                        hasRuntime = true;
                        continue;
                    }
                    keep.add(c);
                }
                if (keep.isEmpty()) continue;
                byte[] out = bytes;
                if (hasRuntime) {
                    DexPool pool = new DexPool(Opcodes.getDefault());
                    for (ClassDef c : keep) pool.internClass(c);
                    MemoryDataStore store = new MemoryDataStore();
                    pool.writeTo(store);
                    out = java.util.Arrays.copyOf(store.getData(), store.getSize());
                }
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(out);
                zos.closeEntry();
            }
        }
    }

    private static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (com.android.tools.smali.dexlib2.iface.MethodParameter p : m.getParameters()) {
            sb.append(p.getType());
        }
        return sb.append(')').append(m.getReturnType()).toString();
    }

    @Test
    public void runtimeDexIsFrameworkOnly() throws Exception {
        File apk = findApk();
        Assume.assumeTrue("debug APK must be built first", apk != null);
        byte[] dex = DialogRuntimeInjector.buildRuntimeDex(apk);
        DexBackedDexFile parsed = new DexBackedDexFile(Opcodes.getDefault(), dex);
        for (ClassDef c : parsed.getClasses()) {
            String type = c.getType();
            assertTrue("runtime class must stay in its package: " + type,
                    type.startsWith("Lcom/mp/dialogruntime/"));
            assertFalse(type.startsWith("Lio/github/"));
            assertFalse(type.startsWith("Lcom/mp/dialogengine/"));
        }
    }

    @Test
    public void writesRuntimeDexAssetAndPatchedDex() throws Exception {
        File apk = findApk();
        Assume.assumeTrue("debug APK must be built first", apk != null);
        byte[] runtime = DialogRuntimeInjector.buildRuntimeDex(apk);
        assertTrue(DialogRuntimeInjector.containsRuntimeClass(apk));
        File mini = File.createTempFile("dialog_mini", ".apk");
        File out = File.createTempFile("dialog_inject", ".apk");
        try {
            copyWithoutRuntime(apk, mini);
            assertFalse(DialogRuntimeInjector.containsRuntimeClass(mini));
            String hash = DialogJson.sha256(mini);
            DialogRuntimeInjector.writeApk(mini, out, Collections.emptyMap(), runtime,
                    "mp_dialog_test.json", "{\"id\":\"test\",\"nodes\":[]}");
            assertTrue(out.length() > 0);
            assertTrue(hash.equals(DialogJson.sha256(mini)));
            boolean asset = false;
            boolean runtimeDex = false;
            try (ZipFile zip = new ZipFile(out)) {
                ZipEntry a = zip.getEntry("assets/mp_dialog_test.json");
                assertNotNull(a);
                asset = true;
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (!e.getName().startsWith("classes") || !e.getName().endsWith(".dex")) continue;
                    byte[] bytes = readAll(zip.getInputStream(e));
                    if (indexOf(bytes, "com/mp/dialogruntime/DialogRuntime".getBytes()) >= 0) {
                        runtimeDex = true;
                    }
                }
            }
            assertTrue("asset must be present", asset);
            assertTrue("runtime dex must be present", runtimeDex);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            mini.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
