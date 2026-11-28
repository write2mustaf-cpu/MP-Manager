package com.mp.dialogruntime;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.ViewFlipper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Framework-only runtime embedded into a target APK. It reads a JSON spec from
 * assets and builds the dialog with real Android views, events and actions.
 * No AndroidX or third-party dependency is referenced here on purpose: the
 * class is extracted from MP Manager's own dex and merged into the target.
 */
public final class DialogRuntime {

    private static final String TAG = "MPDialogRuntime";
    private static final String PREFS = "mp_dialog_runtime";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, DialogRuntime> ACTIVE = new HashMap<>();
    private static int activePriority = Integer.MIN_VALUE;

    private final Context ctx;
    private final JSONObject spec;
    private final String modelId;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, JSONObject> nodes = new LinkedHashMap<>();
    private final JSONObject vars = new JSONObject();
    private final Map<String, JSONObject> functions = new LinkedHashMap<>();
    private Dialog dialog;
    private LinearLayout card;
    private TextView countdownView;
    private android.os.CountDownTimer countdown;
    private boolean dismissed;
    private boolean dragged;
    private float dragLastX;
    private float dragLastY;
    private int dragStartX;
    private int dragStartY;
    private int actionDepth;
    private final boolean dark;
    private boolean nested;

    public static void showFromAsset(Context context, String assetName) {
        showFromAsset(context, assetName, false);
    }

    static void showFromAsset(Context context, String assetName, boolean nested) {
        if (context == null || assetName == null || assetName.isEmpty()) return;
        try {
            java.io.InputStream in = context.getAssets().open(assetName);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            showFromJson(context, new String(bos.toByteArray(), "UTF-8"), nested);
        } catch (Throwable t) {
            Log.e(TAG, "Cannot read dialog asset " + assetName, t);
        }
    }

    public static void showFromJson(Context context, String json) {
        showFromJson(context, json, false);
    }

    static void showFromJson(Context context, String json, boolean nested) {
        if (context == null || json == null || json.isEmpty()) return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        MAIN.post(() -> {
            try {
                DialogRuntime runtime = new DialogRuntime(context, new JSONObject(json));
                runtime.nested = nested;
                runtime.show();
            } catch (Throwable t) {
                Log.e(TAG, "Cannot show dialog", t);
            }
        });
    }

    private DialogRuntime(Context context, JSONObject spec) {
        this.ctx = context;
        this.spec = spec;
        this.modelId = spec.optString("id", "mp_dialog");
        this.dark = spec.optBoolean("dark", false);
        readVariables();
        readFunctions();
    }

    private void readVariables() {
        JSONArray arr = spec.optJSONArray("variables");
        SharedPreferences prefs = prefs();
        boolean save = spec.optBoolean("saveState", false);
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.optJSONObject(i);
            if (v == null) continue;
            String name = v.optString("name", "");
            if (name.isEmpty()) continue;
            String value = v.optString("currentValue", "");
            if (value.isEmpty()) value = v.optString("defaultValue", "");
            String source = v.optString("source", "static");
            String sourceKey = v.optString("sourceKey", "");
            try {
                if ("intent".equals(source)) {
                    if (ctx instanceof Activity) {
                        android.content.Intent intent = ((Activity) ctx).getIntent();
                        if (intent != null && sourceKey != null) {
                            String extra = intent.getStringExtra(sourceKey);
                            if (extra != null) value = extra;
                        }
                    }
                } else if ("prefs".equals(source)) {
                    String p = prefs.getString(sourceKey, value);
                    if (p != null) value = p;
                } else if ("clipboard".equals(source)) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                        if (cs != null) value = cs.toString();
                    }
                } else if ("date".equals(source)) {
                    value = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(new java.util.Date());
                } else if ("package".equals(source)) {
                    value = ctx.getPackageName();
                }
                if (save) {
                    String saved = prefs.getString("var_" + modelId + "_" + name, null);
                    if (saved != null) value = saved;
                }
            } catch (Throwable ignored) {
            }
            try {
                vars.put(name, value);
            } catch (Throwable ignored) {
            }
        }
    }

    private void readFunctions() {
        JSONArray arr = spec.optJSONArray("functions");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            functions.put(f.optString("id", ""), f);
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void show() {
        String showMode = spec.optString("showMode", "always");
        SharedPreferences p = prefs();
        if ("dont_show_again".equals(showMode) && p.getBoolean("dont_" + modelId, false)) return;
        if ("once".equals(showMode) && p.getBoolean("once_" + modelId, false)) return;
        if (!nested && spec.optBoolean("preventDuplicate", true)) {
            DialogRuntime existing = ACTIVE.get(modelId);
            if (existing != null && existing.dialog != null && existing.dialog.isShowing()) return;
        }
        if (!nested && spec.optInt("priority", 0) < activePriority) {
            // A higher-priority dialog is already visible; do not stack over it.
            return;
        }
        if ("dont_show_again".equals(showMode)) p.edit().putBoolean("dont_" + modelId, true).apply();
        if ("once".equals(showMode)) p.edit().putBoolean("once_" + modelId, true).apply();

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(spec.optBoolean("cancelable", true));
        dialog.setCanceledOnTouchOutside(spec.optBoolean("cancelable", true));
        dialog.setOnKeyListener((d, keyCode, event) -> keyCode == android.view.KeyEvent.KEYCODE_BACK
                && spec.optBoolean("preventCloseDuringAction", false) && actionDepth > 0);

        FrameLayout root = new FrameLayout(ctx);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        applyDialogBackground(card);
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (spec.optBoolean("draggable", false)) {
            TextView handle = new TextView(ctx);
            handle.setText(str("dragHandle", "⋮⋮"));
            handle.setGravity(Gravity.CENTER);
            handle.setTextSize(18);
            handle.setAlpha(0.6f);
            handle.setPadding(0, dp(2), 0, dp(4));
            handle.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            handle.setOnTouchListener(this::onDragTouch);
            card.addView(handle);
        }

        String title = resolve(spec.optString("title", ""));
        String message = resolve(spec.optString("message", ""));
        if (!title.isEmpty()) card.addView(buildTitle(title));
        if (!message.isEmpty()) card.addView(buildMessage(message));

        JSONArray nodeArr = spec.optJSONArray("nodes");
        if (nodeArr != null) {
            for (int i = 0; i < nodeArr.length(); i++) {
                JSONObject n = nodeArr.optJSONObject(i);
                if (n == null) continue;
                View v = buildNode(n);
                if (v == null) continue;
                nodes.put(n.optString("id", ""), n);
                views.put(n.optString("id", ""), v);
                card.addView(v);
            }
        }

        if (spec.optInt("countdownSeconds", 0) > 0) {
            countdownView = new TextView(ctx);
            countdownView.setTextSize(12);
            countdownView.setAlpha(0.7f);
            countdownView.setGravity(Gravity.END);
            countdownView.setPadding(0, dp(8), 0, 0);
            card.addView(countdownView);
        }

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = (float) spec.optDouble("dimAmount", 0.4);
            window.setAttributes(lp);
            if (spec.optBoolean("bottomSheet", false)) {
                window.setGravity(Gravity.BOTTOM);
            } else {
                window.setGravity(Gravity.CENTER);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                float blurDp = (float) spec.optDouble("blurRadiusDp", 0);
                if (blurDp > 0) {
                    try {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                        window.setBackgroundBlurRadius(dp(blurDp));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        dialog.setOnDismissListener(d -> {
            dismissed = true;
            if (countdown != null) countdown.cancel();
            ACTIVE.remove(modelId);
            activePriority = Integer.MIN_VALUE;
            persistState();
            runEvents("close", "");
        });
        if (countdownView != null) {
            countdownView.setText(String.valueOf(spec.optInt("countdownSeconds", 0)));
        }
        try {
            dialog.show();
        } catch (Throwable t) {
            Log.e(TAG, "dialog.show failed", t);
            return;
        }
        ACTIVE.put(modelId, this);
        activePriority = spec.optInt("priority", 0);
        applyWindowSize(window);
        animateIn(card);
        runEvents("open", "");
        startCountdown();
        DialogRuntimeActions.attachAll(this);
    }

    private void applyWindowSize(Window window) {
        if (window == null) return;
        try {
            int screen = ctx.getResources().getDisplayMetrics().widthPixels;
            int widthDp = spec.optInt("widthDp", 320);
            float maxPercent = (float) spec.optDouble("maxWidthPercent", 0.92);
            int w = widthDp > 0 ? dp(widthDp) : (int) (screen * maxPercent);
            w = Math.min(w, (int) (screen * maxPercent));
            if (spec.optBoolean("fullWidth", false) || spec.optBoolean("bottomSheet", false)) {
                w = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            int h = spec.optInt("heightDp", 0);
            window.setLayout(w, h > 0 ? dp(h) : ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private void applyDialogBackground(View view) {
        GradientDrawable d = new GradientDrawable();
        String bg = spec.optString("backgroundColor", "");
        String bg2 = spec.optString("backgroundEndColor", "");
        if (bg2 != null && !bg2.isEmpty()) {
            d.setOrientation(orientation(spec.optInt("gradientOrientation", 0)));
            d.setColors(new int[]{color(bg, (dark ? 0xF21C1C1E : 0xF2FFFFFF)), color(bg2, 0xFF000000)});
        } else {
            d.setColor(color(bg, (dark ? 0xF21C1C1E : 0xF2FFFFFF)));
        }
        float radius = (float) spec.optDouble("cornerRadiusDp", 24);
        d.setCornerRadius(dp(radius));
        float border = (float) spec.optDouble("borderWidthDp", 0);
        if (border > 0) d.setStroke(dp(border), color(spec.optString("borderColor", "#00000000"), 0));
        view.setBackground(d);
        int pad = dp((float) spec.optDouble("paddingDp", 20));
        view.setPadding(pad, pad, pad, pad);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                view.setElevation(dp((float) spec.optDouble("elevationDp", 8)));
            } catch (Throwable ignored) {
            }
        }
    }

    private TextView buildTitle(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize((float) spec.optDouble("titleSizeSp", 19));
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(color(spec.optString("titleColor", ""), (dark ? 0xFFE6E1E5 : 0xFF1B1B1F)));
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView buildMessage(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize((float) spec.optDouble("messageSizeSp", 14));
        tv.setTextColor(color(spec.optString("messageColor", ""), (dark ? 0xFFCAC4D0 : 0xFF44474E)));
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private View buildNode(JSONObject n) {
        if (n == null) return null;
        String nodeId = n.optString("id", "");
        if (!nodeId.isEmpty()) {
            nodes.put(nodeId, n);
        }
        String kind = n.optString("kind", "text");
        View v;
        switch (kind) {
            case "image":
                v = buildImage(n);
                break;
            case "button":
                v = buildButton(n);
                break;
            case "input":
                v = buildInput(n);
                break;
            case "toggle":
                v = buildToggle(n);
                break;
            case "list":
                v = buildList(n);
                break;
            case "progress":
                v = buildProgress(n);
                break;
            case "divider":
                v = buildDivider(n);
                break;
            case "spacer":
                v = buildSpacer(n);
                break;
            case "card":
            case "collapsible":
                v = buildContainer(n, "collapsible".equals(kind));
                break;
            case "toolbar":
                v = buildToolbar(n);
                break;
            case "pages":
                v = buildPages(n);
                break;
            case "title":
            case "subtitle":
            case "message":
            case "text":
            default:
                v = buildText(n, kind);
                break;
        }
        if (v == null) return null;
        if (!nodeId.isEmpty()) views.put(nodeId, v);
        v.setTag(nodeId);
        v.setVisibility(n.optBoolean("visible", true) ? View.VISIBLE : View.GONE);
        v.setAlpha((float) n.optDouble("alpha", 1));
        v.setEnabled(n.optBoolean("enabled", true));
        return v;
    }

    void refreshNodes(String id) {
        for (Map.Entry<String, View> entry : views.entrySet()) {
            if (id != null && !id.isEmpty() && !id.equals(entry.getKey())) continue;
            JSONObject n = nodes.get(entry.getKey());
            if (n == null) continue;
            View v = entry.getValue();
            v.setVisibility(n.optBoolean("visible", true) ? View.VISIBLE : View.GONE);
            if (v instanceof ProgressBar) {
                try {
                    ((ProgressBar) v).setProgress(Integer.parseInt(
                            resolve(n.optString("text", "0")).trim()));
                } catch (Throwable ignored) {
                }
            } else if (v instanceof EditText) {
                ((EditText) v).setHint(resolve(n.optString("hint", "")));
            } else if (v instanceof TextView) {
                ((TextView) v).setText(resolve(n.optString("text", "")));
            }
        }
    }

    private TextView buildText(JSONObject n, String kind) {
        TextView tv = new TextView(ctx);
        String text = resolve(n.optString("text", ""));
        if (text.isEmpty() && "text".equals(kind)) text = "";
        tv.setText(text);
        float size = (float) n.optDouble("textSizeSp",
                "title".equals(kind) ? 18 : "subtitle".equals(kind) ? 13 : 15);
        tv.setTextSize(size);
        int style = n.optInt("fontStyle", 0);
        tv.setTypeface(typeface(n.optString("font", ""), style));
        tv.setTextColor(color(n.optString("textColor", ""),
                "title".equals(kind) ? (dark ? 0xFFE6E1E5 : 0xFF1B1B1F) : (dark ? 0xFFCAC4D0 : 0xFF44474E)));
        tv.setGravity(gravity(n.optString("gravity", "start")));
        applyNodeBox(tv, n, 4);
        if ("subtitle".equals(kind)) tv.setAlpha(0.8f);
        return tv;
    }

    private View buildImage(JSONObject n) {
        ImageView iv = new ImageView(ctx);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        String b64 = n.optString("imageBase64", "");
        if (b64 != null && !b64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) iv.setImageBitmap(bmp);
            } catch (Throwable ignored) {
            }
        }
        applyNodeBox(iv, n, 0);
        int h = n.optInt("heightDp", 0);
        if (h > 0) iv.setMinimumHeight(dp(h));
        return iv;
    }

    private View buildButton(JSONObject n) {
        TextView btn = new TextView(ctx);
        btn.setText(resolve(n.optString("text", "Button")));
        btn.setTextSize((float) n.optDouble("textSizeSp", 14));
        btn.setTypeface(typeface(n.optString("font", ""), n.optInt("fontStyle", 0)));
        int textColor = color(n.optString("textColor", ""),
                color(spec.optString("buttonTextColor", ""), (dark ? 0xFF0B0B0C : 0xFFFFFFFF)));
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setMinHeight(dp(44));
        GradientDrawable d = new GradientDrawable();
        String bg = n.optString("backgroundColor", "");
        String bg2 = n.optString("backgroundColor2", "");
        if (bg.isEmpty() && bg2.isEmpty()) {
            d.setColor(color(spec.optString("buttonColor", ""), (dark ? 0xFFA8C7FA : 0xFF3F5AA6)));
        } else if (!bg2.isEmpty()) {
            d.setOrientation(orientation(n.optInt("gradientOrientation", 0)));
            d.setColors(new int[]{color(bg, (dark ? 0xFFA8C7FA : 0xFF3F5AA6)), color(bg2, (dark ? 0xFF7FA6F0 : 0xFF1E3A8A))});
        } else {
            d.setColor(color(bg, (dark ? 0xFFA8C7FA : 0xFF3F5AA6)));
        }
        float radius = (float) n.optDouble("cornerRadiusDp", 12);
        d.setCornerRadius(dp(radius));
        float border = (float) n.optDouble("borderWidthDp", 0);
        if (border > 0) d.setStroke(dp(border), color(n.optString("borderColor", "#00000000"), 0));
        btn.setBackground(d);
        int padH = dp(14), padV = dp(10);
        btn.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                n.optInt("widthDp", -1) == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                n.optInt("heightDp", -2) == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : dp(n.optInt("heightDp", 44)));
        lp.topMargin = dp(n.optInt("yDp", 6));
        btn.setLayoutParams(lp);
        if (n.optBoolean("enabled", true)) {
            btn.setOnClickListener(v -> runEvents("click", n.optString("id", "")));
            btn.setOnLongClickListener(v -> {
                runEvents("long_click", n.optString("id", ""));
                return true;
            });
        }
        return btn;
    }

    private View buildInput(JSONObject n) {
        EditText et = new EditText(ctx);
        et.setText(resolve(n.optString("text", "")));
        et.setHint(resolve(n.optString("hint", "")));
        et.setTextSize((float) n.optDouble("textSizeSp", 15));
        et.setSingleLine(false);
        et.setMaxLines(n.optInt("maxLines", 4));
        int inputType = n.optInt("inputType", 0);
        if (inputType == 0) {
            String declared = n.optString("inputKind", "text");
            if ("number".equals(declared)) inputType = InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            else if ("password".equals(declared)) inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            else if ("email".equals(declared)) inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            else inputType = InputType.TYPE_CLASS_TEXT;
        }
        et.setInputType(inputType);
        et.setTextColor(dark ? 0xFFE6E1E5 : 0xFF1B1B1F);
        et.setHintTextColor(dark ? 0xFF938F99 : 0xFF6B6B72);
        applyNodeBox(et, n, 10);
        if (n.optString("backgroundColor", "").isEmpty()) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(dark ? 0x33FFFFFF : 0x0F000000);
            g.setCornerRadius(dp((float) n.optDouble("cornerRadiusDp", 10)));
            et.setBackground(g);
        }
        String binding = n.optString("dataBinding", "");
        final String nodeId = n.optString("id", "");
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!binding.isEmpty()) setVar(binding, s == null ? "" : s.toString());
                runEvents("input_change", nodeId);
            }
        });
        return et;
    }

    private View buildToggle(JSONObject n) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(resolve(n.optString("text", "")));
        cb.setTextSize((float) n.optDouble("textSizeSp", 15));
        cb.setTextColor(dark ? 0xFFE6E1E5 : 0xFF1B1B1F);
        cb.setChecked(n.optBoolean("checked", false));
        applyNodeBox(cb, n, 4);
        String binding = n.optString("dataBinding", "");
        final String nodeId = n.optString("id", "");
        if (!binding.isEmpty()) setVar(binding, String.valueOf(cb.isChecked()));
        cb.setOnCheckedChangeListener((b, checked) -> {
            if (!binding.isEmpty()) setVar(binding, String.valueOf(checked));
            runEvents("click", nodeId);
        });
        return cb;
    }

    private View buildList(JSONObject n) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        applyNodeBox(wrap, n, 4);
        JSONArray options = n.optJSONArray("options");
        JSONArray selected = n.optJSONArray("selectedIndices");
        boolean multi = n.optBoolean("multiSelect", false);
        String binding = n.optString("dataBinding", "");
        String nodeId = n.optString("id", "");
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        List<View> rowViews = new ArrayList<>();
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                String label = resolve(options.optString(i, ""));
                View row;
                if (multi) {
                    CheckBox cb = new CheckBox(ctx);
                    cb.setText(label);
                    cb.setTextSize(15);
                    cb.setTextColor(dark ? 0xFFE6E1E5 : 0xFF1B1B1F);
                    cb.setChecked(contains(selected, i));
                    int index = i;
                    cb.setOnCheckedChangeListener((b, checked) -> {
                        updateSelectionVar(binding, rows, options.length());
                        runEvents("selection", nodeId);
                        runEvents("click", nodeId);
                    });
                    row = cb;
                } else {
                    TextView tv = new TextView(ctx);
                    tv.setText(label);
                    tv.setTextSize(15);
                    tv.setTextColor(dark ? 0xFFE6E1E5 : 0xFF1B1B1F);
                    tv.setPadding(dp(12), dp(12), dp(12), dp(12));
                    tv.setBackgroundColor(contains(selected, i) ? 0x223F5AA6 : Color.TRANSPARENT);
                    int index = i;
                    tv.setOnClickListener(v -> {
                        for (int k = 0; k < rows.getChildCount(); k++) {
                            rows.getChildAt(k).setBackgroundColor(Color.TRANSPARENT);
                        }
                        v.setBackgroundColor(0x223F5AA6);
                        setVar(binding.isEmpty() ? "selection" : binding, String.valueOf(index));
                        runEvents("selection", nodeId);
                        runEvents("click", nodeId);
                    });
                    row = tv;
                }
                rows.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                rowViews.add(row);
            }
        }
        if (n.optBoolean("searchable", false)) {
            EditText search = new EditText(ctx);
            search.setHint(str("search", "Search"));
            search.setSingleLine(true);
            search.setTextSize(14);
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String q = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                    for (int k = 0; k < rowViews.size(); k++) {
                        View row = rowViews.get(k);
                        String label = "";
                        if (row instanceof CheckBox) label = ((CheckBox) row).getText().toString();
                        else if (row instanceof TextView) label = ((TextView) row).getText().toString();
                        row.setVisibility(label.toLowerCase(Locale.ROOT).contains(q)
                                ? View.VISIBLE : View.GONE);
                    }
                }
            });
            wrap.addView(search, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (rows.getChildCount() > 6) {
            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(rows);
            wrap.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        } else {
            wrap.addView(rows, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (!binding.isEmpty()) updateSelectionVar(binding, rows, options == null ? 0 : options.length());
        return wrap;
    }

    private View buildProgress(JSONObject n) {
        ProgressBar pb = new ProgressBar(ctx, null,
                n.optBoolean("indeterminate", true) ? android.R.attr.progressBarStyle
                        : android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(n.optBoolean("indeterminate", true));
        pb.setMax(100);
        pb.setProgress(n.optInt("progress", 0));
        applyNodeBox(pb, n, 4);
        return pb;
    }

    private View buildDivider(JSONObject n) {
        View v = new View(ctx);
        v.setBackgroundColor(color(n.optString("textColor", ""), dark ? 0x33FFFFFF : 0x22000000));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(n.optInt("heightDp", 1)))));
        return v;
    }

    private View buildSpacer(JSONObject n) {
        Space s = new Space(ctx);
        s.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(n.optInt("heightDp", 12))));
        return s;
    }

    private View buildContainer(JSONObject n, boolean collapsible) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        applyNodeBox(box, n, 10);
        if (collapsible) {
            TextView header = new TextView(ctx);
            header.setText("▸ " + resolve(n.optString("text", "")));
            header.setTextSize(15);
            header.setPadding(dp(8), dp(8), dp(8), dp(8));
            box.addView(header);
        }
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        JSONArray children = n.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                if (c == null) continue;
                View cv = buildNode(c);
                if (cv == null) continue;
                nodes.put(c.optString("id", ""), c);
                views.put(c.optString("id", ""), cv);
                content.addView(cv);
            }
        }
        box.addView(content);
        if (collapsible) {
            content.setVisibility(View.GONE);
            View header = box.getChildAt(0);
            header.setOnClickListener(v -> {
                boolean show = content.getVisibility() != View.VISIBLE;
                content.setVisibility(show ? View.VISIBLE : View.GONE);
                if (header instanceof TextView) {
                    ((TextView) header).setText((show ? "▾ " : "▸ ") + resolve(n.optString("text", "")));
                }
            });
        }
        return box;
    }

    private View buildToolbar(JSONObject n) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        applyNodeBox(bar, n, 6);
        JSONArray children = n.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                if (c == null) continue;
                View cv = buildNode(c);
                if (cv == null) continue;
                nodes.put(c.optString("id", ""), c);
                views.put(c.optString("id", ""), cv);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMarginStart(dp(2));
                lp.setMarginEnd(dp(2));
                bar.addView(cv, lp);
            }
        }
        return bar;
    }

    private View buildPages(JSONObject n) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        applyNodeBox(wrap, n, 4);
        ViewFlipper flipper = new ViewFlipper(ctx);
        JSONArray children = n.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject p = children.optJSONObject(i);
                if (p == null) continue;
                LinearLayout page = new LinearLayout(ctx);
                page.setOrientation(LinearLayout.VERTICAL);
                JSONArray kids = p.optJSONArray("children");
                if (kids != null) {
                    for (int k = 0; k < kids.length(); k++) {
                        JSONObject c = kids.optJSONObject(k);
                        if (c == null) continue;
                        View cv = buildNode(c);
                        if (cv == null) continue;
                        nodes.put(c.optString("id", ""), c);
                        views.put(c.optString("id", ""), cv);
                        page.addView(cv);
                    }
                }
                flipper.addView(page);
            }
        }
        wrap.addView(flipper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (flipper.getChildCount() > 1) {
            LinearLayout nav = new LinearLayout(ctx);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setGravity(Gravity.CENTER);
            TextView prev = navButton("‹");
            TextView indicator = new TextView(ctx);
            indicator.setText("1 / " + flipper.getChildCount());
            indicator.setGravity(Gravity.CENTER);
            indicator.setPadding(dp(12), 0, dp(12), 0);
            TextView next = navButton("›");
            prev.setOnClickListener(v -> {
                flipper.showPrevious();
                indicator.setText((flipper.getDisplayedChild() + 1) + " / " + flipper.getChildCount());
            });
            next.setOnClickListener(v -> {
                flipper.showNext();
                indicator.setText((flipper.getDisplayedChild() + 1) + " / " + flipper.getChildCount());
            });
            nav.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(44)));
            nav.addView(indicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            nav.addView(next, new LinearLayout.LayoutParams(dp(44), dp(44)));
            wrap.addView(nav);
        }
        return wrap;
    }

    private TextView navButton(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(22);
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        return tv;
    }

    private void applyNodeBox(View v, JSONObject n, int defaultPad) {
        String bg = n.optString("backgroundColor", "");
        float radius = (float) n.optDouble("cornerRadiusDp", 0);
        float border = (float) n.optDouble("borderWidthDp", 0);
        if (!bg.isEmpty() || border > 0) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(color(bg, Color.TRANSPARENT));
            if (radius > 0) d.setCornerRadius(dp(radius));
            if (border > 0) d.setStroke(dp(border), color(n.optString("borderColor", "#00000000"), 0));
            v.setBackground(d);
        }
        int pad = dp((float) n.optDouble("paddingDp", defaultPad));
        v.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                n.optInt("widthDp", -1) == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                n.optInt("heightDp", -2) == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : Math.max(0, dp(n.optInt("heightDp", 0))));
        lp.topMargin = dp(n.optInt("yDp", 6));
        v.setLayoutParams(lp);
    }

    private void animateIn(View v) {
        if (v == null) return;
        String anim = spec.optString("animation", "fade_scale");
        v.setAlpha(0f);
        if ("fade".equals(anim)) {
            v.animate().alpha(1f).setDuration(180).start();
        } else if ("scale_in".equals(anim)) {
            v.setScaleX(0.86f);
            v.setScaleY(0.86f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        } else if ("slide_up".equals(anim)) {
            v.setTranslationY(dp(48));
            v.animate().alpha(1f).translationY(0).setDuration(240).start();
        } else if ("slide_down".equals(anim)) {
            v.setTranslationY(-dp(48));
            v.animate().alpha(1f).translationY(0).setDuration(240).start();
        } else if ("pop".equals(anim)) {
            v.setScaleX(0.7f);
            v.setScaleY(0.7f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
        } else {
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        }
    }

    void beginAction() {
        actionDepth++;
        if (dialog != null && spec.optBoolean("preventCloseDuringAction", false)) {
            dialog.setCanceledOnTouchOutside(false);
        }
    }

    void endAction() {
        if (actionDepth > 0) actionDepth--;
        if (actionDepth == 0 && dialog != null
                && spec.optBoolean("preventCloseDuringAction", false)) {
            dialog.setCanceledOnTouchOutside(spec.optBoolean("cancelable", true));
        }
    }

    private void startCountdown() {
        int sec = spec.optInt("countdownSeconds", 0);
        if (sec <= 0 || countdownView == null) return;
        countdown = new android.os.CountDownTimer(sec * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (countdownView != null) {
                    countdownView.setText(String.valueOf(Math.max(0, millisUntilFinished / 1000)));
                }
            }

            @Override
            public void onFinish() {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            }
        };
        countdown.start();
    }

    private boolean onDragTouch(View v, MotionEvent event) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return false;
        WindowManager.LayoutParams lp = window.getAttributes();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragLastX = event.getRawX();
                dragLastY = event.getRawY();
                dragStartX = lp.x;
                dragStartY = lp.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                lp.x = dragStartX + (int) (event.getRawX() - dragLastX);
                lp.y = dragStartY + (int) (event.getRawY() - dragLastY);
                window.setAttributes(lp);
                dragged = true;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return false;
    }

    // ---- package-private API used by DialogRuntimeActions ----

    Context ctx() {
        return ctx;
    }

    JSONObject spec() {
        return spec;
    }

    Dialog dialog() {
        return dialog;
    }

    Map<String, View> views() {
        return views;
    }

    Map<String, JSONObject> nodes() {
        return nodes;
    }

    JSONObject vars() {
        return vars;
    }

    Map<String, JSONObject> functions() {
        return functions;
    }

    void setVar(String name, String value) {
        if (name == null || name.isEmpty()) return;
        try {
            vars.put(name, value == null ? "" : value);
        } catch (Throwable ignored) {
        }
    }

    String getVar(String name) {
        return vars == null ? "" : vars.optString(name, "");
    }

    String str(String key, String fallback) {
        JSONObject strings = spec.optJSONObject("strings");
        if (strings == null) return fallback;
        String value = strings.optString(key, "");
        return value == null || value.isEmpty() ? fallback : value;
    }

    String resolve(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text;
        int guard = 0;
        while (guard++ < 6) {
            int start = out.indexOf("${");
            if (start < 0) break;
            int end = out.indexOf('}', start + 2);
            if (end < 0) break;
            String name = out.substring(start + 2, end);
            String value = vars.optString(name, "");
            out = out.substring(0, start) + value + out.substring(end + 1);
        }
        return out;
    }

    boolean hasDialog(String id) {
        if (id == null) return false;
        android.content.res.Resources res = ctx.getResources();
        try {
            int rid = res.getIdentifier("mp_dialog_" + id.replaceAll("[^A-Za-z0-9_.-]", "_"),
                    "raw", ctx.getPackageName());
            if (rid != 0) return true;
        } catch (Throwable ignored) {
        }
        return spec.optJSONObject("dialogs") != null
                && spec.optJSONObject("dialogs").has(id);
    }

    String dialogSpec(String id) {
        JSONObject d = spec.optJSONObject("dialogs") == null ? null
                : spec.optJSONObject("dialogs").optJSONObject(id);
        if (d == null) return null;
        try {
            JSONObject copy = new JSONObject(d.toString());
            copy.put("functions", spec.optJSONArray("functions"));
            copy.put("strings", spec.optJSONObject("strings"));
            if (!copy.has("dialogs")) copy.put("dialogs", spec.optJSONObject("dialogs"));
            return copy.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    void runEvents(String trigger, String nodeId) {
        DialogRuntimeActions.runEvents(this, trigger, nodeId);
    }

    void persistState() {
        if (!spec.optBoolean("saveState", false)) return;
        SharedPreferences.Editor e = prefs().edit();
        Iterator<String> it = vars.keys();
        while (it.hasNext()) {
            String k = it.next();
            e.putString("var_" + modelId + "_" + k, vars.optString(k, ""));
        }
        e.apply();
    }

    int dp(float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static int color(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Color.parseColor(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static GradientDrawable.Orientation orientation(int value) {
        switch (value) {
            case 1:
                return GradientDrawable.Orientation.LEFT_RIGHT;
            case 2:
                return GradientDrawable.Orientation.TL_BR;
            case 3:
                return GradientDrawable.Orientation.BL_TR;
            default:
                return GradientDrawable.Orientation.TOP_BOTTOM;
        }
    }

    private static int gravity(String value) {
        if ("center".equals(value)) return Gravity.CENTER;
        if ("end".equals(value)) return Gravity.END;
        return Gravity.START;
    }

    private static Typeface typeface(String family, int style) {
        try {
            if (family != null && !family.isEmpty()) return Typeface.create(family, style);
        } catch (Throwable ignored) {
        }
        return Typeface.create(Typeface.DEFAULT, style);
    }

    private static boolean contains(JSONArray arr, int value) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) if (arr.optInt(i, -1) == value) return true;
        return false;
    }

    private void updateSelectionVar(String binding, LinearLayout rows, int count) {
        if (binding == null || binding.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            boolean checked = row instanceof CheckBox && ((CheckBox) row).isChecked();
            if (checked) {
                if (sb.length() > 0) sb.append(',');
                sb.append(i);
            }
        }
        setVar(binding, sb.toString());
    }
}
