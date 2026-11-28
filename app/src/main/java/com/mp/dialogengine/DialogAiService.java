package com.mp.dialogengine;

import android.content.Context;
import android.text.TextUtils;

import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Project;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import modder.hub.dexeditor.fragment.SettingsFragment;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin Gemini client for the dialog engine. Every result is returned to the
 * caller for a before/after diff; nothing is applied automatically.
 */
public final class DialogAiService {

    public interface Callback {
        void onResult(String text, Model model, String error);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private DialogAiService() {
    }

    public static boolean isAvailable(Context context) {
        return !TextUtils.isEmpty(SettingsFragment.getChatGptApiKey(context));
    }

    public static void explain(Context context, Project project, Model model, Callback cb) {
        String prompt = "اشرح بالعربية بنية هذا الدايلوك ووظيفة كل زر وحدث. كن مختصرًا ودقيقًا، "
                + "ولا تقترح تعديلات في هذه المهمة.\n\n"
                + "Dialog JSON:\n" + DialogJson.toJson(model);
        call(context, prompt, false, cb);
    }

    public static void generate(Context context, String description, Callback cb) {
        String prompt = "أنشئ دايلوك Android كاملًا من الوصف التالي. أعد JSON فقط بين "
                + "<<<JSON>>> و<<<END>>>، بنفس مخطط النموذج (id, name, title, message, "
                + "backgroundColor, cornerRadiusDp, nodes, events, variables). "
                + "استخدم kinds المسموحة: text, title, subtitle, message, image, button, "
                + "input, toggle, list, progress, divider, spacer, card, collapsible, toolbar, pages. "
                + "كل زر يجب أن يملك حدث click وإجراء dismiss على الأقل. "
                + "لا تضف أي شرح خارج الوسمين.\n\nالوصف:\n" + description;
        call(context, prompt, true, cb);
    }

    public static void redesign(Context context, Model model, String style, Callback cb) {
        String prompt = "أعد تصميم هذا الدايلوك بأسلوب " + style + " مع الحفاظ حرفيًا على "
                + "المعرفات والوظائف والأحداث والمتغيرات. غيّر فقط الحقول البصرية "
                + "(الألوان، الزوايا، الحدود، الخط، blurRadiusDp، animation، bottomSheet، glass). "
                + "أعد JSON كاملًا بين <<<JSON>>> و<<<END>>>.\n\n"
                + DialogJson.toJson(model);
        call(context, prompt, true, cb);
    }

    public static void suggestEvents(Context context, Project project, Model model, Callback cb) {
        String prompt = "اقترح أحداثًا وإجراءات مناسبة لعناصر هذا الدايلوك. أعد JSON فقط "
                + "بين <<<JSON>>> و<<<END>>> يحتوي على قائمة events جديدة بنفس المخطط. "
                + "لا تغيّر العناصر الحالية.\n\n"
                + "Functions:\n" + DialogJson.toJson(project == null ? null : project.functions)
                + "\n\nDialog:\n" + DialogJson.toJson(model);
        call(context, prompt, true, cb);
    }

    public static void analyzeErrors(Context context, Project project, Model model,
                                     String findings, Callback cb) {
        String prompt = "حلّل أخطاء هذا الدايلوك واقترح إصلاحات آمنة. أعد شرحًا عربيًا مختصرًا، "
                + "ولا تعدّل JSON في هذه المهمة.\n\nFindings:\n" + findings
                + "\n\nDialog:\n" + DialogJson.toJson(model);
        call(context, prompt, false, cb);
    }

    private static void call(Context context, String prompt, boolean expectJson, Callback cb) {
        String key;
        String modelName;
        try {
            key = SettingsFragment.getChatGptApiKey(context);
            modelName = SettingsFragment.getGeminiModel(context);
        } catch (Throwable t) {
            cb.onResult(null, null, "تعذر قراءة إعدادات الذكاء الاصطناعي");
            return;
        }
        if (TextUtils.isEmpty(key)) {
            cb.onResult(null, null, "أضف مفتاح Gemini من إعدادات المحرر أولًا");
            return;
        }
        if (TextUtils.isEmpty(modelName)) modelName = "gemini-3.5-flash-lite";
        try {
            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject content = new JSONObject();
            JSONArray parts = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("text", prompt);
            parts.put(part);
            content.put("parts", parts);
            contents.put(content);
            body.put("contents", contents);
            JSONObject gen = new JSONObject();
            gen.put("temperature", 0.2);
            body.put("generationConfig", gen);
            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/"
                            + modelName + ":generateContent?key=" + key)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    cb.onResult(null, null, "فشل الاتصال: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response r = response) {
                        String raw = r.body() == null ? "" : r.body().string();
                        if (!r.isSuccessful()) {
                            cb.onResult(null, null, "HTTP " + r.code() + ": " + trim(raw));
                            return;
                        }
                        String text = extractText(raw);
                        if (TextUtils.isEmpty(text)) {
                            cb.onResult(null, null, "رد فارغ من الخدمة");
                            return;
                        }
                        Model parsed = expectJson ? parseModel(text) : null;
                        cb.onResult(text, parsed, null);
                    } catch (Throwable t) {
                        cb.onResult(null, null, "تعذر قراءة الرد: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            cb.onResult(null, null, "تعذر بناء الطلب: " + t.getMessage());
        }
    }

    private static String extractText(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return "";
            JSONObject first = candidates.optJSONObject(0);
            if (first == null) return "";
            JSONObject content = first.optJSONObject("content");
            if (content == null) return "";
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null) sb.append(p.optString("text", ""));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static Model parseModel(String text) {
        if (text == null) return null;
        String json = text;
        int a = text.indexOf("<<<JSON>>>");
        int b = text.indexOf("<<<END>>>");
        if (a >= 0 && b > a) {
            json = text.substring(a + "<<<JSON>>>".length(), b).trim();
        } else {
            int first = text.indexOf('{');
            int last = text.lastIndexOf('}');
            if (first >= 0 && last > first) json = text.substring(first, last + 1);
        }
        json = json.replace("```json", "").replace("```", "").trim();
        try {
            return DialogJson.fromJson(json, Model.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
