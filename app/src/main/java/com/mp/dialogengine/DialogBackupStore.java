package com.mp.dialogengine;

import android.content.Context;

import com.mp.dialogengine.DialogModels.Backup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Copy-on-write backup store. Nothing here ever overwrites the original file:
 * callers pass an explicit destination for restore, and injection always writes
 * a new APK.
 */
public final class DialogBackupStore {

    private static final String ROOT = "dialog_engine/backups";
    private static final String MANIFEST = "manifest.json";

    private DialogBackupStore() {
    }

    public static File root(Context context) {
        return new File(context.getFilesDir(), ROOT);
    }

    public static Backup backup(Context context, File source, String note) throws IOException {
        if (source == null || !source.isFile()) throw new IOException("Source file not found");
        File dir = new File(root(context), String.format(Locale.US, "b_%d_%04d",
                System.currentTimeMillis(), (int) (Math.random() * 10000)));
        if (!dir.mkdirs()) throw new IOException("Cannot create backup directory");
        File copy = new File(dir, DialogJson.safeName(source.getName()));
        copyFile(source, copy);
        Backup b = new Backup();
        b.id = dir.getName();
        b.originalPath = source.getAbsolutePath();
        b.backupPath = copy.getAbsolutePath();
        b.sha256 = DialogJson.sha256(source);
        b.size = source.length();
        b.timestamp = System.currentTimeMillis();
        b.note = note == null ? "" : note;
        DialogJson.writeFile(new File(dir, MANIFEST), DialogJson.toJson(b));
        return b;
    }

    public static List<Backup> list(Context context) {
        List<Backup> out = new ArrayList<>();
        File root = root(context);
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File manifest = new File(dir, MANIFEST);
            if (!manifest.isFile()) continue;
            try {
                Backup b = DialogJson.fromJson(DialogJson.readFile(manifest), Backup.class);
                if (b != null) out.add(b);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static File restore(Backup backup, File destination) throws IOException {
        if (backup == null || backup.backupPath == null) throw new IOException("Invalid backup");
        File src = new File(backup.backupPath);
        if (!src.isFile()) throw new IOException("Backup file missing");
        if (destination == null) throw new IOException("Destination is required");
        if (destination.getAbsolutePath().equals(src.getAbsolutePath())) {
            throw new IOException("Destination must be different from the backup");
        }
        copyFile(src, destination);
        return destination;
    }

    public static void delete(Backup backup) {
        if (backup == null || backup.backupPath == null) return;
        File f = new File(backup.backupPath);
        File dir = f.getParentFile();
        if (dir != null) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) //noinspection ResultOfMethodCallIgnored
                    c.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    /** Unified diff for text sources; binary files fall back to hash/size. */
    public static String diff(File left, File right) {
        if (left == null || right == null) return "";
        try {
            String a = DialogJson.readFile(left);
            String b = DialogJson.readFile(right);
            if (a.indexOf('\0') >= 0 || b.indexOf('\0') >= 0) {
                return "binary\nsha256(left)=" + safeSha(left) + "\nsha256(right)=" + safeSha(right);
            }
            List<String> al = Arrays.asList(a.split("\\r?\\n", -1));
            List<String> bl = Arrays.asList(b.split("\\r?\\n", -1));
            boolean aNl = a.endsWith("\n");
            boolean bNl = b.endsWith("\n");
            List<io.github.abdurazaaqmohammed.util.text.TextDiffEngine.DiffLine> lines =
                    io.github.abdurazaaqmohammed.util.text.TextDiffEngine.buildDiff(
                            al, aNl, bl, bNl,
                            io.github.abdurazaaqmohammed.util.text.TextDiffEngine.IgnoreMode.NONE,
                            true);
            String diff = io.github.abdurazaaqmohammed.util.text.TextDiffEngine.toUnifiedDiff(
                    lines, al, bl, left.getName(), right.getName(), aNl, bNl);
            return diff.isEmpty() ? "identical" : diff;
        } catch (Exception e) {
            return "diff failed: " + e.getMessage();
        }
    }

    private static String safeSha(File f) {
        String s = DialogJson.sha256(f);
        return s == null || s.isEmpty() ? "?" : s;
    }

    public static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    public static List<Backup> empty() {
        return Collections.emptyList();
    }
}
