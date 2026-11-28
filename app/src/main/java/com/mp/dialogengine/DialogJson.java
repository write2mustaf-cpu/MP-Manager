package com.mp.dialogengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class DialogJson {

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private DialogJson() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static DialogModels.Model cloneModel(DialogModels.Model model) {
        if (model == null) return null;
        return GSON.fromJson(GSON.toJson(model), DialogModels.Model.class);
    }

    public static DialogModels.Project cloneProject(DialogModels.Project project) {
        if (project == null) return null;
        return GSON.fromJson(GSON.toJson(project), DialogModels.Project.class);
    }

    public static void writeFile(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    public static String sha256(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            byte[] out = digest.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) return "dialog";
        String s = value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "dialog" : s;
    }
}
