package com.mp.dialogengine;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mp.dialogruntime.DialogRuntime;
import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Descriptor;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.Finding;
import com.mp.dialogengine.DialogModels.FunctionRef;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;
import com.mp.dialogengine.DialogModels.Project;
import com.mp.dialogengine.DialogModels.Variable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.ui.UiFields;
import io.github.abdurazaaqmohammed.ui.common.util.DialogUtil;
import io.github.abdurazaaqmohammed.ui.editor.FilePickerDialog;
import io.github.abdurazaaqmohammed.util.misc.AppExecutors;

/**
 * Single collapsible card placed inside the existing Advanced section. Every
 * group owns its content and its open state, and every action calls a real
 * engine method; incomplete paths are not rendered as buttons.
 */
public final class DialogEngineUi {

    public interface Host {
        /** Applies the model to the legacy advanced editor; returns omitted widget count. */
        int applyModelToEditor(Model model);

        void finishInjection(File output, boolean sign);

        void refreshPanes();
    }

    private static final String PREF_CARD = "dialog_engine_card_open";
    private static final String PREF_GROUPS = "dialog_engine_open_groups";
    private static final String[] GROUP_IDS = {
            "recovery", "events", "functions", "variables", "components", "lifecycle",
            "ios", "analysis", "editor", "ai", "testing", "library"
    };
    private static final int[] GROUP_TITLES = {
            R.string.de_group_recovery, R.string.de_group_events, R.string.de_group_functions,
            R.string.de_group_variables, R.string.de_group_components, R.string.de_group_lifecycle,
            R.string.de_group_ios, R.string.de_group_analysis, R.string.de_group_editor,
            R.string.de_group_ai, R.string.de_group_testing, R.string.de_group_library
    };

    private final Activity activity;
    private final DialogUtil dialogUtil;
    private final LinearLayout container;
    private final File apk;
    private final String apkPath;
    private final List<String> activities;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Map<String, LinearLayout> groupContents = new LinkedHashMap<>();
    private final Map<String, TextView> groupStatus = new LinkedHashMap<>();
    private final Set<String> openGroups = new LinkedHashSet<>();
    private LinearLayout cardContent;

    private Project project = new Project();
    private Model model;
    private DialogModels.ScanResult scan;
    private List<Finding> findings = new ArrayList<>();
    private String lastReport = "";
    private File sourceBackup;

    public DialogEngineUi(Activity activity, DialogUtil dialogUtil, LinearLayout container,
                          File apk, String apkPath, List<String> activities, Host host) {
        this.activity = activity;
        this.dialogUtil = dialogUtil;
        this.container = container;
        this.apk = apk;
        this.apkPath = apkPath;
        this.activities = activities == null ? Collections.emptyList() : activities;
        this.host = host;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        loadState();
    }

    public void build() {
        if (container == null) return;
        for (int i = 0; i < container.getChildCount(); i++) {
            if ("de_card".equals(container.getChildAt(i).getTag())) return;
        }
        Project saved = DialogLibrary.loadProject(activity);
        if (saved != null && (saved.apkPath == null || saved.apkPath.isEmpty()
                || saved.apkPath.equals(apkPath))) {
            project = saved;
        } else {
            project = new Project();
            project.apkPath = apkPath == null ? "" : apkPath;
        }
        model = project.selected();
        if (model == null) {
            model = new Model();
            model.name = activity.getString(R.string.de_default_dialog_title);
            model.title = activity.getString(R.string.de_default_dialog_title);
            model.message = "";
            Node b = new Node();
            b.kind = "button";
            b.text = activity.getString(R.string.de_default_ok);
            model.nodes.add(b);
            Event e = new Event();
            e.nodeId = b.id;
            e.trigger = "click";
            Action a = new Action();
            a.type = "dismiss";
            e.actions.add(a);
            model.events.add(e);
            b.eventIds.add(e.id);
            project.dialogs.add(model);
            project.selectedDialogId = model.id;
        }
        if (openGroups.isEmpty()) openGroups.add("recovery");

        MaterialCardView card = new MaterialCardView(activity);
        card.setTag("de_card");
        card.setCardElevation(dp(0));
        card.setRadius(dp(20));
        try {
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorSurfaceContainerLow));
            card.setStrokeColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorOutlineVariant));
            card.setStrokeWidth(dp(1));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(12);
        card.setLayoutParams(cardLp);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        TextView title = new TextView(activity);
        title.setText(R.string.de_title);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(MaterialColors.getColor(header, com.google.android.material.R.attr.colorPrimary));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.arrow_drop_down_24px);
        arrow.setColorFilter(MaterialColors.getColor(header,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        header.addView(title);
        header.addView(arrow);
        root.addView(header);

        cardContent = new LinearLayout(activity);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.de_subtitle);
        subtitle.setTextSize(12);
        subtitle.setAlpha(0.7f);
        subtitle.setPadding(0, 0, 0, dp(6));
        cardContent.addView(subtitle);
        for (int i = 0; i < GROUP_IDS.length; i++) {
            addGroup(cardContent, GROUP_IDS[i], GROUP_TITLES[i]);
        }
        root.addView(cardContent);
        card.addView(root);
        container.addView(card);

        boolean open = prefs.getBoolean(PREF_CARD, true);
        cardContent.setVisibility(open ? View.VISIBLE : View.GONE);
        arrow.setRotation(open ? 0f : -90f);
        header.setOnClickListener(v -> {
            boolean now = cardContent.getVisibility() != View.VISIBLE;
            cardContent.setVisibility(now ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(now ? 0f : -90f).setDuration(160).start();
            prefs.edit().putBoolean(PREF_CARD, now).apply();
        });
        updateStatuses();
    }

    private void addGroup(LinearLayout parent, String id, int titleRes) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(4);
        wrapper.setLayoutParams(wlp);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));
        header.setPadding(dp(4), 0, dp(4), 0);
        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = new TextView(activity);
        status.setTextSize(11);
        status.setAlpha(0.65f);
        status.setPadding(dp(4), 0, dp(6), 0);
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.arrow_drop_down_24px);
        arrow.setColorFilter(MaterialColors.getColor(header,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        header.addView(title);
        header.addView(status);
        header.addView(arrow);
        wrapper.addView(header);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), 0, dp(4), dp(6));
        content.setVisibility(View.GONE);
        wrapper.addView(content);
        groupContents.put(id, content);
        groupStatus.put(id, status);

        final boolean[] built = {false};
        final Runnable open = () -> {
            if (!built[0]) {
                built[0] = true;
                buildGroup(id, content);
            }
            content.setVisibility(View.VISIBLE);
            arrow.animate().rotation(0f).setDuration(150).start();
            openGroups.add(id);
            persistGroups();
        };
        header.setOnClickListener(v -> {
            if (content.getVisibility() == View.VISIBLE) {
                content.setVisibility(View.GONE);
                arrow.animate().rotation(-90f).setDuration(150).start();
                openGroups.remove(id);
                persistGroups();
            } else {
                open.run();
            }
        });
        boolean isOpen = openGroups.contains(id);
        arrow.setRotation(isOpen ? 0f : -90f);
        if (isOpen) {
            built[0] = true;
            buildGroup(id, content);
            content.setVisibility(View.VISIBLE);
        }
        parent.addView(wrapper);
    }

    private void buildGroup(String id, LinearLayout c) {
        switch (id) {
            case "recovery":
                buildRecovery(c);
                break;
            case "events":
                buildEvents(c);
                break;
            case "functions":
                buildFunctions(c);
                break;
            case "variables":
                buildVariables(c);
                break;
            case "components":
                buildComponents(c);
                break;
            case "lifecycle":
                buildLifecycle(c);
                break;
            case "ios":
                buildIos(c);
                break;
            case "analysis":
                buildAnalysis(c);
                break;
            case "editor":
                buildEditor(c);
                break;
            case "ai":
                buildAi(c);
                break;
            case "testing":
                buildTesting(c);
                break;
            case "library":
                buildLibrary(c);
                break;
        }
    }

    private void persistGroups() {
        prefs.edit().putStringSet(PREF_GROUPS, new HashSet<>(openGroups)).apply();
    }

    private void loadState() {
        try {
            Set<String> saved = prefs.getStringSet(PREF_GROUPS, null);
            if (saved != null) openGroups.addAll(saved);
        } catch (Throwable ignored) {
        }
    }

    private void updateStatuses() {
        TextView recovery = groupStatus.get("recovery");
        if (recovery != null) {
            if (scan == null) recovery.setText(activity.getString(R.string.de_need_scan));
            else recovery.setText(activity.getString(R.string.de_scan_done,
                    scan.dialogs.size(), scan.functions.size()));
        }
        TextView events = groupStatus.get("events");
        if (events != null) events.setText(activity.getString(R.string.de_count,
                model == null || model.events == null ? 0 : model.events.size()));
        TextView functions = groupStatus.get("functions");
        if (functions != null) functions.setText(activity.getString(R.string.de_count,
                project.functions == null ? 0 : project.functions.size()));
        TextView variables = groupStatus.get("variables");
        if (variables != null) variables.setText(activity.getString(R.string.de_count,
                model == null || model.variables == null ? 0 : model.variables.size()));
        TextView components = groupStatus.get("components");
        if (components != null) components.setText(activity.getString(R.string.de_count,
                model == null ? 0 : flatten(model.nodes).size()));
        TextView lifecycle = groupStatus.get("lifecycle");
        if (lifecycle != null) lifecycle.setText(model == null ? "" : model.showMode);
        TextView ios = groupStatus.get("ios");
        if (ios != null) ios.setText(model == null ? "" : model.designName);
        TextView analysis = groupStatus.get("analysis");
        if (analysis != null) analysis.setText(findings.isEmpty()
                ? activity.getString(R.string.de_no_findings)
                : DialogAnalyzer.summarize(findings));
        TextView ai = groupStatus.get("ai");
        if (ai != null) ai.setText(DialogAiService.isAvailable(activity)
                ? activity.getString(R.string.de_ok)
                : activity.getString(R.string.de_not_available));
        TextView testing = groupStatus.get("testing");
        if (testing != null) testing.setText(TextUtils.isEmpty(lastReport)
                ? activity.getString(R.string.de_test_run) : activity.getString(R.string.de_done, ""));
        TextView library = groupStatus.get("library");
        if (library != null) library.setText(activity.getString(R.string.de_count,
                DialogLibrary.listTemplates(activity).size()));
    }

    // ---------- small view builders ----------

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void sectionLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setAlpha(0.75f);
        tv.setPadding(0, dp(8), 0, dp(2));
        parent.addView(tv);
    }

    private MaterialButton action(LinearLayout parent, int textRes, Runnable action) {
        return action(parent, activity.getString(textRes), action);
    }

    private MaterialButton action(LinearLayout parent, String text, Runnable action) {
        MaterialButton b = new MaterialButton(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(42));
        b.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        parent.addView(b);
        return b;
    }

    private TextInputEditText field(LinearLayout parent, int hintRes, String value, int inputType) {
        TextInputLayout box = UiFields.box(activity, activity.getString(hintRes));
        TextInputEditText edit = UiFields.field(box, inputType);
        if (value != null) edit.setText(value);
        parent.addView(box);
        return edit;
    }

    private TextInputEditText field(LinearLayout parent, String hint, String value, int inputType) {
        TextInputLayout box = UiFields.box(activity, hint);
        TextInputEditText edit = UiFields.field(box, inputType);
        if (value != null) edit.setText(value);
        parent.addView(box);
        return edit;
    }

    private MaterialAutoCompleteTextView dropdown(LinearLayout parent, int hintRes,
                                                  String[] items, String selected,
                                                  Runnable onPick) {
        TextInputLayout box = UiFields.box(activity, activity.getString(hintRes));
        box.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        MaterialAutoCompleteTextView tv = new MaterialAutoCompleteTextView(activity);
        tv.setInputType(InputType.TYPE_NULL);
        tv.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_dropdown_item_1line, items));
        if (selected != null) tv.setText(selected, false);
        tv.setOnItemClickListener((p, v, pos, id) -> {
            if (onPick != null) onPick.run();
        });
        box.addView(tv);
        parent.addView(box);
        return tv;
    }

    private MaterialSwitch switchRow(LinearLayout parent, int textRes, boolean checked,
                                     java.util.function.Consumer<Boolean> onChange) {
        MaterialSwitch sw = new MaterialSwitch(activity);
        sw.setText(textRes);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, v) -> onChange.accept(v));
        parent.addView(sw);
        return sw;
    }

    private void showMessage(String title, String message) {
        dialogUtil.getDialogBuilder()
                .setTitle(title)
                .setMessage(message == null ? "" : message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showText(String title, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(13);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sv = new ScrollView(activity);
        sv.addView(tv);
        dialogUtil.getDialogBuilder()
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showChoice(String title, List<String> items,
                            java.util.function.Consumer<Integer> onPick) {
        if (items == null || items.isEmpty()) {
            showMessage(title, activity.getString(R.string.de_none));
            return;
        }
        ScrollView sv = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            MaterialButton b = new MaterialButton(activity);
            b.setText(items.get(i));
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                onPick.accept(index);
            });
            box.addView(b);
        }
        sv.addView(box);
        holder[0] = dialogUtil.getDialogBuilder()
                .setTitle(title)
                .setView(sv)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSearchList(String title, List<String> items,
                                java.util.function.Consumer<Integer> onPick) {
        if (items == null || items.isEmpty()) {
            showMessage(title, activity.getString(R.string.de_none));
            return;
        }
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText search = new EditText(activity);
        search.setHint(R.string.de_search);
        search.setSingleLine(true);
        root.addView(search);
        ScrollView sv = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv);
        AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            String q = search.getText() == null ? "" : search.getText().toString()
                    .trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < items.size(); i++) {
                String label = items.get(i);
                if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
                final int index = i;
                MaterialButton b = new MaterialButton(activity);
                b.setText(label);
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    onPick.accept(index);
                });
                list.addView(b);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refresh[0].run();
            }
        });
        refresh[0].run();
        holder[0] = dialogUtil.getDialogBuilder()
                .setTitle(title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class Busy {
        private final AlertDialog dialog;
        private final TextView text;

        Busy(int titleRes) {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(12), dp(20), dp(12));
            ProgressBar bar = new ProgressBar(activity);
            bar.setIndeterminate(true);
            box.addView(bar, new LinearLayout.LayoutParams(dp(40), dp(40)));
            text = new TextView(activity);
            text.setText(titleRes);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            box.addView(text);
            dialog = dialogUtil.getDialogBuilder()
                    .setView(box)
                    .setCancelable(false)
                    .create();
            dialog.show();
        }

        void update(String message) {
            main.post(() -> text.setText(message == null ? "" : message));
        }

        void dismiss() {
            main.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            });
        }
    }

    private void save() {
        try {
            DialogLibrary.saveProject(activity, project);
        } catch (Throwable ignored) {
        }
    }

    private void setModel(Model m) {
        model = m;
        if (m != null) {
            project.selectedDialogId = m.id;
            boolean found = false;
            for (int i = 0; i < project.dialogs.size(); i++) {
                if (project.dialogs.get(i) != null && m.id.equals(project.dialogs.get(i).id)) {
                    project.dialogs.set(i, m);
                    found = true;
                    break;
                }
            }
            if (!found) project.dialogs.add(m);
        }
        save();
        updateStatuses();
    }

    private List<Node> flatten(List<Node> nodes) {
        List<Node> out = new ArrayList<>();
        if (nodes == null) return out;
        for (Node n : nodes) {
            if (n == null) continue;
            out.add(n);
            out.addAll(flatten(n.children));
        }
        return out;
    }

    private String nodeLabel(Node n) {
        if (n == null) return "?";
        String text = n.text == null ? "" : n.text;
        if (text.length() > 32) text = text.substring(0, 32) + "…";
        return n.kind + "  " + (text.isEmpty() ? n.id : text);
    }

    private void chooseNode(String title, java.util.function.Consumer<Node> onPick) {
        if (model == null) {
            showMessage(title, activity.getString(R.string.de_no_model));
            return;
        }
        List<Node> flat = flatten(model.nodes);
        List<String> labels = new ArrayList<>();
        for (Node n : flat) labels.add(nodeLabel(n));
        showChoice(title, labels, i -> onPick.accept(flat.get(i)));
    }

    private void toast(int res, Object... args) {
        try {
            android.widget.Toast.makeText(activity, activity.getString(res, args),
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- Recovery and conversion ----------------

    private void buildRecovery(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_recovery));
        action(c, R.string.de_scan, this::scan);
        action(c, R.string.de_tree, this::showTree);
        action(c, R.string.de_preview, this::preview);
        action(c, R.string.de_apply_editor, this::applyToEditor);
        action(c, R.string.de_inject, this::confirmInject);
        action(c, R.string.de_backup, this::backupApk);
        action(c, R.string.de_restore, this::showRestore);
        action(c, R.string.de_compare, this::showCompare);
        action(c, R.string.de_save_project, this::saveProjectNow);
        action(c, R.string.de_load_project, this::loadProjectNow);
    }

    private void scan() {
        if (apk == null || !apk.isFile()) {
            showMessage(activity.getString(R.string.de_error), activity.getString(R.string.de_apk_missing));
            return;
        }
        final Busy busy = new Busy(R.string.de_scanning);
        AppExecutors.io().execute(() -> {
            try {
                DialogModels.ScanResult result = DialogSourceScanner.scan(activity, apk,
                        (message, current, total) -> busy.update(message));
                main.post(() -> {
                    busy.dismiss();
                    scan = result;
                    project.functions = result.functions;
                    project.apkPath = apkPath == null ? "" : apkPath;
                    save();
                    updateStatuses();
                    toast(R.string.de_scan_done, result.dialogs.size(), result.functions.size());
                    if (!result.warnings.isEmpty()) {
                        showText(activity.getString(R.string.de_info),
                                TextUtils.join("\n", result.warnings));
                    }
                });
            } catch (Throwable t) {
                busy.dismiss();
                main.post(() -> showMessage(activity.getString(R.string.de_error),
                        String.valueOf(t.getMessage())));
            }
        });
    }

    private void showTree() {
        if (scan == null || scan.dialogs == null || scan.dialogs.isEmpty()) {
            toast(R.string.de_need_scan);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Descriptor d : scan.dialogs) {
            String screen = d.screen == null ? "" : d.screen;
            if (screen.length() > 48) screen = "…" + screen.substring(screen.length() - 48);
            labels.add(d.type + " · " + d.name + "\n" + screen);
        }
        showSearchList(activity.getString(R.string.de_tree), labels, i -> {
            Descriptor d = scan.dialogs.get(i);
            if (d.model == null) {
                toast(R.string.de_scan_empty);
                return;
            }
            Model m = DialogJson.cloneModel(d.model);
            setModel(m);
            int omitted = host.applyModelToEditor(m);
            toast(R.string.de_selected, m.name);
            if (omitted > 0) showMessage(activity.getString(R.string.de_warning),
                    activity.getString(R.string.de_apply_warn, omitted));
        });
    }

    private void preview() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        try {
            DialogRuntime.showFromJson(activity, DialogRuntimeInjector.buildSpec(activity, project, model));
            if (Build.VERSION.SDK_INT < 31 && model.blurRadiusDp > 0) {
                toast(R.string.de_ios_blur_note);
            }
        } catch (Throwable t) {
            showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
        }
    }

    private void applyToEditor() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        int omitted = host.applyModelToEditor(model);
        if (omitted > 0) {
            showMessage(activity.getString(R.string.de_warning),
                    activity.getString(R.string.de_apply_warn, omitted));
        } else {
            toast(R.string.de_success);
        }
    }

    private void confirmInject() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        if (activities.isEmpty()) {
            showMessage(activity.getString(R.string.de_error), activity.getString(R.string.de_no_activity));
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView summary = new TextView(activity);
        summary.setText(model.name + "\n" + model.title + "\n"
                + activity.getString(R.string.de_count, flatten(model.nodes).size())
                + " · " + activity.getString(R.string.de_count,
                model.events == null ? 0 : model.events.size())
                + "\n" + model.designName);
        summary.setTextSize(13);
        box.addView(summary);
        MaterialSwitch sign = new MaterialSwitch(activity);
        sign.setText(R.string.de_autosign);
        sign.setChecked(true);
        box.addView(sign);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_inject)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_inject, (d, w) -> inject(sign.isChecked()))
                .show();
    }

    private void inject(boolean sign) {
        final Busy busy = new Busy(R.string.de_injecting);
        AppExecutors.io().execute(() -> {
            try {
                DialogBackupStore.backup(activity, apk, "before dialog engine inject");
                File out = DialogRuntimeInjector.inject(activity, apk, activities, project, model, null);
                main.post(() -> {
                    busy.dismiss();
                    host.finishInjection(out, sign);
                    toast(R.string.de_inject_done, out.getName());
                });
            } catch (Throwable t) {
                busy.dismiss();
                main.post(() -> showMessage(activity.getString(R.string.de_error),
                        String.valueOf(t.getMessage())));
            }
        });
    }

    private void backupApk() {
        AppExecutors.io().execute(() -> {
            try {
                DialogModels.Backup b = DialogBackupStore.backup(activity, apk, "manual");
                main.post(() -> {
                    toast(R.string.de_backup_done);
                    showText(activity.getString(R.string.de_backup), b.backupPath);
                });
            } catch (Throwable t) {
                main.post(() -> showMessage(activity.getString(R.string.de_error),
                        String.valueOf(t.getMessage())));
            }
        });
    }

    private void showRestore() {
        List<DialogModels.Backup> list = DialogBackupStore.list(activity);
        if (list.isEmpty()) {
            toast(R.string.de_backup_empty);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (DialogModels.Backup b : list) {
            labels.add(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new java.util.Date(b.timestamp)) + "\n"
                    + (b.note == null ? "" : b.note) + " · " + b.size + " bytes");
        }
        showChoice(activity.getString(R.string.de_restore), labels, i -> {
            DialogModels.Backup b = list.get(i);
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(8), dp(16), dp(8));
            String base = apk == null ? "restored" : apk.getName();
            if (base.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                base = base.substring(0, base.length() - 4);
            }
            final String baseFinal = base;
            TextInputEditText name = field(box, R.string.de_destination,
                    baseFinal + "_restored.apk", InputType.TYPE_CLASS_TEXT);
            dialogUtil.getDialogBuilder()
                    .setTitle(R.string.de_restore)
                    .setView(box)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.de_restore, (d, w) -> {
                        String file = name.getText() == null ? "" : name.getText().toString().trim();
                        if (file.isEmpty()) return;
                        File dest = new File(apk == null ? activity.getFilesDir() : apk.getParentFile(), file);
                        if (apk != null && dest.getAbsolutePath().equals(apk.getAbsolutePath())) {
                            dest = new File(dest.getParentFile(), baseFinal + "_restored.apk");
                        }
                        final File target = unusedFile(dest);
                        AppExecutors.io().execute(() -> {
                            try {
                                DialogBackupStore.restore(b, target);
                                main.post(() -> {
                                    toast(R.string.de_restored, target.getName());
                                    host.refreshPanes();
                                });
                            } catch (Throwable t) {
                                main.post(() -> showMessage(activity.getString(R.string.de_error),
                                        String.valueOf(t.getMessage())));
                            }
                        });
                    }).show();
        });
    }

    private void showCompare() {
        List<DialogModels.Backup> list = DialogBackupStore.list(activity);
        if (list.isEmpty()) {
            toast(R.string.de_backup_empty);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (DialogModels.Backup b : list) {
            labels.add(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new java.util.Date(b.timestamp)) + " · " + b.backupPath);
        }
        showChoice(activity.getString(R.string.de_compare), labels, i -> {
            DialogModels.Backup b = list.get(i);
            if (apk == null) return;
            String diff = DialogBackupStore.diff(new File(b.backupPath), apk);
            showText(activity.getString(R.string.de_compare), diff);
        });
    }

    private void saveProjectNow() {
        try {
            DialogLibrary.saveProject(activity, project);
            toast(R.string.de_project_saved);
        } catch (Throwable t) {
            showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
        }
    }

    private void loadProjectNow() {
        Project p = DialogLibrary.loadProject(activity);
        if (p == null) {
            showMessage(activity.getString(R.string.de_load_project),
                    activity.getString(R.string.de_none));
            return;
        }
        project = p;
        model = project.selected();
        updateStatuses();
        if (model != null) {
            host.applyModelToEditor(model);
            toast(R.string.de_selected, model.name);
        }
    }

    // ---------------- Events and actions ----------------

    private static final String[] TRIGGERS = {
            "click", "long_click", "double_click", "open", "close", "confirm", "cancel",
            "success", "failure", "input_change", "timeout", "focus", "selection"
    };
    private static final String[] ACTION_TYPES = {
            "condition", "read_field", "call_function", "pass_data", "show_result",
            "open_dialog", "update_screen", "notify_success", "notify_failure",
            "set_variable", "dismiss", "open_url", "copy_text", "toast",
            "hide_node", "show_node", "focus_node", "refresh", "delay"
    };

    private void buildEvents(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_events));
        action(c, R.string.de_add_event, this::addEvent);
        action(c, R.string.de_manage_events, this::manageEvents);
        action(c, R.string.de_validate, this::analyze);
        action(c, R.string.de_run_preview, this::preview);
    }

    private void addEvent() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        showChoice(activity.getString(R.string.de_event_trigger), Arrays.asList(TRIGGERS), ti -> {
            String trigger = TRIGGERS[ti];
            chooseNode(activity.getString(R.string.de_event_node), node -> {
                if (node == null) return;
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(16), dp(8), dp(16), dp(8));
                TextInputEditText priority = field(box, R.string.de_event_priority, "0",
                        InputType.TYPE_CLASS_NUMBER);
                MaterialSwitch stop = new MaterialSwitch(activity);
                stop.setText(R.string.de_event_stop);
                stop.setChecked(true);
                box.addView(stop);
                dialogUtil.getDialogBuilder()
                        .setTitle(R.string.de_add_event)
                        .setView(box)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.de_add, (d, w) -> {
                            Event e = new Event();
                            e.trigger = trigger;
                            e.nodeId = node.id;
                            e.priority = parseInt(text(priority), 0);
                            e.stopOnFailure = stop.isChecked();
                            if (model.events == null) model.events = new ArrayList<>();
                            model.events.add(e);
                            if (node.eventIds == null) node.eventIds = new ArrayList<>();
                            node.eventIds.add(e.id);
                            save();
                            updateStatuses();
                            editEvent(e);
                        }).show();
            });
        });
    }

    private void manageEvents() {
        if (model == null || model.events == null || model.events.isEmpty()) {
            toast(R.string.de_none);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Event e : model.events) {
            Node n = model.node(e.nodeId);
            labels.add(e.trigger + " → " + (n == null ? e.nodeId : nodeLabel(n))
                    + "  (" + (e.actions == null ? 0 : e.actions.size()) + ")");
        }
        showChoice(activity.getString(R.string.de_manage_events), labels,
                i -> editEvent(model.events.get(i)));
    }

    private void editEvent(Event e) {
        if (e == null) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView header = new TextView(activity);
        Node node = model == null ? null : model.node(e.nodeId);
        header.setText(e.trigger + " → " + (node == null ? e.nodeId : nodeLabel(node)));
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(header);
        TextInputEditText evPriority = field(box, R.string.de_event_priority,
                String.valueOf(e.priority), InputType.TYPE_CLASS_NUMBER);
        MaterialSwitch evStop = new MaterialSwitch(activity);
        evStop.setText(R.string.de_event_stop);
        evStop.setChecked(e.stopOnFailure);
        box.addView(evStop);
        TextInputEditText evSkip = field(box, R.string.de_event_skip,
                e.skipIf == null ? "" : e.skipIf, InputType.TYPE_CLASS_TEXT);
        TextInputEditText evDelay = field(box, R.string.de_event_delay,
                String.valueOf(e.delayMs), InputType.TYPE_CLASS_NUMBER);
        ScrollView sv = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        final AlertDialog[] holder = new AlertDialog[1];
        if (e.actions != null) {
            for (Action a : e.actions) {
                if (a == null) continue;
                MaterialButton b = new MaterialButton(activity);
                String label = a.type + (a.functionRef == null || a.functionRef.isEmpty()
                        ? "" : " · " + a.functionRef)
                        + (a.value == null || a.value.isEmpty() ? "" : " · " + a.value);
                b.setText(label);
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    editAction(e, a);
                });
                list.addView(b);
            }
        }
        action(list, R.string.de_add_action, () -> {
            if (holder[0] != null) holder[0].dismiss();
            editAction(e, null);
        });
        action(list, R.string.de_run_preview, this::preview);
        action(list, R.string.de_delete, () -> {
            if (holder[0] != null) holder[0].dismiss();
            if (model.events != null) model.events.remove(e);
            Node n = model.node(e.nodeId);
            if (n != null && n.eventIds != null) n.eventIds.remove(e.id);
            save();
            updateStatuses();
        });
        holder[0] = dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_manage_events)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    e.priority = parseInt(text(evPriority), e.priority);
                    e.stopOnFailure = evStop.isChecked();
                    e.skipIf = text(evSkip);
                    e.delayMs = parseInt(text(evDelay), e.delayMs);
                    save();
                    updateStatuses();
                })
                .setNegativeButton(R.string.de_close, null)
                .show();
    }

    private void editAction(Event event, Action existing) {
        if (event == null) return;
        Action a = existing == null ? new Action() : existing;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        MaterialAutoCompleteTextView type = dropdown(box, R.string.de_action_type,
                ACTION_TYPES, a.type, null);
        TextInputEditText target = field(box, R.string.de_action_target, a.target,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText key = field(box, R.string.de_action_key, a.key,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText value = field(box, R.string.de_action_value, a.value,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText condition = field(box, R.string.de_action_condition, a.condition,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText args = field(box, R.string.de_action_args,
                a.args == null ? "" : TextUtils.join(", ", a.args), InputType.TYPE_CLASS_TEXT);
        TextInputEditText priority = field(box, R.string.de_event_priority,
                String.valueOf(a.priority), InputType.TYPE_CLASS_NUMBER);
        MaterialSwitch stop = new MaterialSwitch(activity);
        stop.setText(R.string.de_event_stop);
        stop.setChecked(a.stopOnFailure);
        box.addView(stop);
        final String[] functionId = {a.functionRef == null ? "" : a.functionRef};
        if (project.functions != null && !project.functions.isEmpty()) {
            List<FunctionRef> safe = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (FunctionRef f : project.functions) {
                if (f != null && f.safe) {
                    safe.add(f);
                    names.add(f.displayName);
                }
            }
            if (!names.isEmpty()) {
                MaterialAutoCompleteTextView fn = dropdown(box, R.string.de_action_function,
                        names.toArray(new String[0]), null, null);
                fn.setOnItemClickListener((p, v, pos, id) -> functionId[0] = safe.get(pos).id);
                if (!functionId[0].isEmpty()) {
                    FunctionRef cur = findFunction(functionId[0]);
                    if (cur != null) fn.setText(cur.displayName, false);
                }
            }
        } else {
            sectionLabel(box, activity.getString(R.string.de_no_functions));
        }
        MaterialButton chooseNode = new MaterialButton(activity);
        chooseNode.setText(R.string.de_event_node);
        chooseNode.setAllCaps(false);
        chooseNode.setOnClickListener(v -> chooseNode(activity.getString(R.string.de_event_node),
                n -> {
                    if (n != null) target.setText(n.id);
                }));
        box.addView(chooseNode);
        MaterialButton chooseDialog = new MaterialButton(activity);
        chooseDialog.setText(R.string.de_choose);
        chooseDialog.setAllCaps(false);
        chooseDialog.setOnClickListener(v -> {
            List<String> names = new ArrayList<>();
            List<Model> list = new ArrayList<>();
            if (project.dialogs != null) {
                for (Model m : project.dialogs) {
                    if (m != null) {
                        list.add(m);
                        names.add(m.name);
                    }
                }
            }
            showChoice(activity.getString(R.string.de_group_recovery), names,
                    i -> target.setText(list.get(i).id));
        });
        box.addView(chooseDialog);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        dialogUtil.getDialogBuilder()
                .setTitle(existing == null ? R.string.de_add_action : R.string.de_edit)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_save, (d, w) -> {
                    a.type = text(type);
                    if (a.type == null || a.type.isEmpty()) a.type = "dismiss";
                    a.target = text(target);
                    a.key = text(key);
                    a.value = text(value);
                    a.condition = text(condition);
                    a.args = new ArrayList<>();
                    String rawArgs = text(args).trim();
                    if (!rawArgs.isEmpty()) {
                        for (String arg : rawArgs.split("\\s*,\\s*")) {
                            if (!arg.isEmpty()) a.args.add(arg);
                        }
                    }
                    a.priority = parseInt(text(priority), 0);
                    a.stopOnFailure = stop.isChecked();
                    a.functionRef = functionId[0];
                    if ("call_function".equals(a.type) && (a.functionRef == null || a.functionRef.isEmpty())) {
                        toast(R.string.de_no_functions);
                        return;
                    }
                    if (existing == null) {
                        if (event.actions == null) event.actions = new ArrayList<>();
                        event.actions.add(a);
                    }
                    save();
                    updateStatuses();
                    editEvent(event);
                }).show();
    }

    private FunctionRef findFunction(String id) {
        if (project.functions == null || id == null) return null;
        for (FunctionRef f : project.functions) if (f != null && id.equals(f.id)) return f;
        return null;
    }

    // ---------------- Native function bridge ----------------

    private void buildFunctions(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_functions));
        action(c, R.string.de_bind_function, this::bindFunction);
        action(c, R.string.de_functions_scan, this::showFunctions);
        action(c, R.string.de_unbind_function, this::unbindFunction);
    }

    private void bindFunction() {
        if (project.functions == null || project.functions.isEmpty()) {
            toast(R.string.de_no_functions);
            return;
        }
        chooseNode(activity.getString(R.string.de_bind_function), this::bindFunctionTo);
    }

    private void bindFunctionTo(Node node) {
        if (node == null) return;
        List<FunctionRef> safe = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (FunctionRef f : project.functions) {
            if (f != null && f.safe) {
                safe.add(f);
                names.add(f.displayName);
            }
        }
        if (names.isEmpty()) {
            toast(R.string.de_no_functions);
            return;
        }
        showSearchList(activity.getString(R.string.de_functions_search), names, i -> {
            FunctionRef fn = safe.get(i);
            node.functionRef = fn.id;
            Event e = clickEventFor(node);
            Action a = new Action();
            a.type = "call_function";
            a.functionRef = fn.id;
            a.stopOnFailure = true;
            if (e.actions == null) e.actions = new ArrayList<>();
            e.actions.add(a);
            save();
            updateStatuses();
            toast(R.string.de_success);
        });
    }

    private Event clickEventFor(Node node) {
        if (model.events != null) {
            for (Event e : model.events) {
                if (e != null && "click".equals(e.trigger) && node.id.equals(e.nodeId)) return e;
            }
        }
        Event e = new Event();
        e.trigger = "click";
        e.nodeId = node.id;
        e.actions = new ArrayList<>();
        if (model.events == null) model.events = new ArrayList<>();
        model.events.add(e);
        if (node.eventIds == null) node.eventIds = new ArrayList<>();
        node.eventIds.add(e.id);
        return e;
    }

    private void showFunctions() {
        if (project.functions == null || project.functions.isEmpty()) {
            toast(R.string.de_no_functions);
            return;
        }
        List<String> names = new ArrayList<>();
        for (FunctionRef f : project.functions) {
            if (f == null) continue;
            names.add((f.safe ? "" : "⛔ ") + f.displayName + "\n" + f.descriptor);
        }
        showSearchList(activity.getString(R.string.de_functions_scan), names, i -> {
            FunctionRef fn = project.functions.get(i);
            String details = fn.displayName + "\n\n"
                    + "class: " + fn.className + "\n"
                    + "method: " + fn.methodName + "\n"
                    + "descriptor: " + fn.descriptor + "\n"
                    + "return: " + fn.returnType + "\n"
                    + "source: " + fn.sourceEntry + "\n"
                    + (fn.safe ? activity.getString(R.string.de_function_safe)
                    : activity.getString(R.string.de_function_unsafe) + "\n" + fn.reason);
            dialogUtil.getDialogBuilder()
                    .setTitle(R.string.de_functions_scan)
                    .setMessage(details)
                    .setNegativeButton(R.string.de_close, null)
                    .setPositiveButton(R.string.de_bind_function, (d, w) -> {
                        chooseNode(activity.getString(R.string.de_bind_function), node -> {
                            if (node == null) return;
                            node.functionRef = fn.id;
                            Event e = clickEventFor(node);
                            Action a = new Action();
                            a.type = "call_function";
                            a.functionRef = fn.id;
                            if (e.actions == null) e.actions = new ArrayList<>();
                            e.actions.add(a);
                            save();
                            updateStatuses();
                            toast(R.string.de_success);
                        });
                    }).show();
        });
    }

    private void unbindFunction() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        List<Node> flat = flatten(model.nodes);
        List<Node> bound = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Node n : flat) {
            if (n != null && n.functionRef != null && !n.functionRef.isEmpty()) {
                bound.add(n);
                FunctionRef f = findFunction(n.functionRef);
                labels.add(nodeLabel(n) + " → " + (f == null ? n.functionRef : f.displayName));
            }
        }
        if (labels.isEmpty()) {
            toast(R.string.de_none);
            return;
        }
        showChoice(activity.getString(R.string.de_unbind_function), labels, i -> {
            Node n = bound.get(i);
            String ref = n.functionRef;
            n.functionRef = "";
            if (model.events != null) {
                for (Event e : model.events) {
                    if (e == null || e.actions == null) continue;
                    e.actions.removeIf(a -> a != null && "call_function".equals(a.type)
                            && ref.equals(a.functionRef));
                }
            }
            save();
            updateStatuses();
            toast(R.string.de_deleted);
        });
    }

    // ---------------- Variables and data ----------------

    private static final String[] VAR_TYPES = {"string", "number", "boolean", "list"};
    private static final String[] VAR_SOURCES = {
            "static", "intent", "prefs", "clipboard", "date", "package", "input"
    };

    private void buildVariables(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_variables));
        action(c, R.string.de_add_variable, this::addVariable);
        action(c, R.string.de_manage_variables, this::manageVariables);
        action(c, R.string.de_insert_placeholder, this::insertPlaceholder);
    }

    private void addVariable() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextInputEditText name = field(box, R.string.de_variable_name, "var",
                InputType.TYPE_CLASS_TEXT);
        MaterialAutoCompleteTextView type = dropdown(box, R.string.de_variable_type,
                VAR_TYPES, "string", null);
        TextInputEditText def = field(box, R.string.de_variable_default, "",
                InputType.TYPE_CLASS_TEXT);
        MaterialAutoCompleteTextView source = dropdown(box, R.string.de_variable_source,
                VAR_SOURCES, "static", null);
        TextInputEditText sourceKey = field(box, R.string.de_variable_source_key, "",
                InputType.TYPE_CLASS_TEXT);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_add_variable)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_add, (d, w) -> {
                    String n = text(name).trim();
                    if (n.isEmpty()) return;
                    Variable v = new Variable();
                    v.name = n;
                    v.type = text(type);
                    v.defaultValue = text(def);
                    v.currentValue = v.defaultValue;
                    v.source = text(source);
                    v.sourceKey = text(sourceKey);
                    if (model.variables == null) model.variables = new ArrayList<>();
                    model.variables.add(v);
                    save();
                    updateStatuses();
                    toast(R.string.de_added);
                }).show();
    }

    private void manageVariables() {
        if (model == null || model.variables == null || model.variables.isEmpty()) {
            toast(R.string.de_none);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Variable v : model.variables) {
            labels.add(v.name + " : " + v.type + " = "
                    + (v.currentValue == null || v.currentValue.isEmpty()
                    ? v.defaultValue : v.currentValue));
        }
        showChoice(activity.getString(R.string.de_manage_variables), labels,
                i -> editVariable(model.variables.get(i)));
    }

    private void editVariable(Variable v) {
        if (v == null) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextInputEditText name = field(box, R.string.de_variable_name, v.name,
                InputType.TYPE_CLASS_TEXT);
        MaterialAutoCompleteTextView type = dropdown(box, R.string.de_variable_type,
                VAR_TYPES, v.type, null);
        TextInputEditText def = field(box, R.string.de_variable_default,
                v.defaultValue, InputType.TYPE_CLASS_TEXT);
        TextInputEditText cur = field(box, R.string.de_value,
                v.currentValue, InputType.TYPE_CLASS_TEXT);
        MaterialAutoCompleteTextView source = dropdown(box, R.string.de_variable_source,
                VAR_SOURCES, v.source, null);
        TextInputEditText sourceKey = field(box, R.string.de_variable_source_key,
                v.sourceKey, InputType.TYPE_CLASS_TEXT);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_manage_variables)
                .setView(scroll)
                .setNegativeButton(R.string.de_delete, (d, w) -> {
                    model.variables.remove(v);
                    save();
                    updateStatuses();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_save, (d, w) -> {
                    String n = text(name).trim();
                    if (!n.isEmpty()) v.name = n;
                    v.type = text(type);
                    v.defaultValue = text(def);
                    v.currentValue = text(cur);
                    v.source = text(source);
                    v.sourceKey = text(sourceKey);
                    save();
                    updateStatuses();
                    toast(R.string.de_updated);
                }).show();
    }

    private void insertPlaceholder() {
        if (model == null || model.variables == null || model.variables.isEmpty()) {
            toast(R.string.de_none);
            return;
        }
        chooseNode(activity.getString(R.string.de_insert_placeholder), node -> {
            if (node == null) return;
            List<String> names = new ArrayList<>();
            for (Variable v : model.variables) names.add(v.name);
            showChoice(activity.getString(R.string.de_insert_placeholder), names, i -> {
                Variable v = model.variables.get(i);
                node.text = (node.text == null ? "" : node.text) + "${" + v.name + "}";
                save();
                updateStatuses();
                toast(R.string.de_added);
            });
        });
    }

    // ---------------- Interactive components ----------------

    private static final String[] COMPONENT_KINDS = {
            "text", "title", "subtitle", "message", "image", "button", "input",
            "toggle", "list", "progress", "divider", "spacer", "card",
            "collapsible", "toolbar", "pages"
    };

    private void buildComponents(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_components));
        action(c, R.string.de_add_component, this::addComponent);
        action(c, R.string.de_edit_component, this::editComponent);
        action(c, R.string.de_order_components, this::orderComponents);
    }

    private void addComponent() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        showChoice(activity.getString(R.string.de_component_kind),
                Arrays.asList(COMPONENT_KINDS), i -> {
                    Node n = defaultNode(COMPONENT_KINDS[i]);
                    if (model.nodes == null) model.nodes = new ArrayList<>();
                    model.nodes.add(n);
                    save();
                    updateStatuses();
                    if ("button".equals(n.kind)) {
                        Event e = clickEventFor(n);
                        Action a = new Action();
                        a.type = "dismiss";
                        e.actions.add(a);
                    }
                    editNode(n);
                });
    }

    private Node defaultNode(String kind) {
        Node n = new Node();
        n.kind = kind;
        switch (kind) {
            case "title":
                n.text = activity.getString(R.string.de_default_title);
                n.textSizeSp = 19f;
                n.fontStyle = Typeface.BOLD;
                break;
            case "subtitle":
                n.text = activity.getString(R.string.de_default_subtitle);
                n.textSizeSp = 13f;
                break;
            case "message":
                n.text = activity.getString(R.string.de_default_message);
                n.textSizeSp = 14f;
                break;
            case "image":
                n.heightDp = 160;
                break;
            case "button":
                n.text = activity.getString(R.string.de_default_button);
                n.textSizeSp = 14f;
                n.backgroundColor = "#FF3F5AA6";
                n.textColor = "#FFFFFFFF";
                n.cornerRadiusDp = 12f;
                n.paddingDp = 10f;
                break;
            case "input":
                n.hint = activity.getString(R.string.de_default_input_hint);
                n.dataBinding = "field";
                break;
            case "toggle":
                n.text = activity.getString(R.string.de_default_toggle);
                n.dataBinding = "checked";
                break;
            case "list":
                n.options = new ArrayList<>(Arrays.asList(
                        activity.getString(R.string.de_default_option_1),
                        activity.getString(R.string.de_default_option_2),
                        activity.getString(R.string.de_default_option_3)));
                n.searchable = true;
                n.dataBinding = "selected";
                break;
            case "progress":
                n.indeterminate = true;
                n.progress = 50;
                break;
            case "divider":
                n.heightDp = 1;
                break;
            case "spacer":
                n.heightDp = 12;
                break;
            case "card":
                n.backgroundColor = "#11FFFFFF";
                n.cornerRadiusDp = 16f;
                n.paddingDp = 12f;
                break;
            case "collapsible":
                n.text = activity.getString(R.string.de_default_group);
                break;
            case "toolbar":
                break;
            case "pages":
                break;
            case "text":
            default:
                n.text = activity.getString(R.string.de_default_text);
                n.textSizeSp = 15f;
                break;
        }
        return n;
    }

    private void editComponent() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        chooseNode(activity.getString(R.string.de_edit_component), this::editNode);
    }

    private void editNode(Node n) {
        if (n == null) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextInputEditText text = field(box, R.string.de_component_text, n.text,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        TextInputEditText hint = field(box, R.string.de_component_hint, n.hint,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText size = field(box, R.string.de_component_size,
                String.valueOf(n.textSizeSp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextInputEditText color = field(box, R.string.de_component_color, n.textColor,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText bg = field(box, R.string.de_component_bg, n.backgroundColor,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText radius = field(box, R.string.de_component_radius,
                String.valueOf(n.cornerRadiusDp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextInputEditText width = field(box, R.string.de_value,
                String.valueOf(n.widthDp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        TextInputEditText height = field(box, R.string.de_value,
                String.valueOf(n.heightDp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        MaterialAutoCompleteTextView gravity = dropdown(box, R.string.de_gravity,
                new String[]{"start", "center", "end"}, n.gravity, null);
        MaterialSwitch visible = new MaterialSwitch(activity);
        visible.setText(R.string.de_component_visible);
        visible.setChecked(n.visible);
        box.addView(visible);
        MaterialSwitch enabled = new MaterialSwitch(activity);
        enabled.setText(R.string.de_component_enabled);
        enabled.setChecked(n.enabled);
        box.addView(enabled);
        TextInputEditText alpha = field(box, R.string.de_value,
                String.valueOf(n.alpha), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextInputEditText options = null;
        TextInputEditText progress = null;
        if ("list".equals(n.kind)) {
            StringBuilder sb = new StringBuilder();
            if (n.options != null) for (String o : n.options) sb.append(o).append('\n');
            options = field(box, R.string.de_component_options, sb.toString().trim(),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        if ("progress".equals(n.kind)) {
            progress = field(box, R.string.de_component_progress,
                    String.valueOf(n.progress), InputType.TYPE_CLASS_NUMBER);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        final TextInputEditText fOptions = options;
        final TextInputEditText fProgress = progress;
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_edit_component)
                .setView(scroll)
                .setNegativeButton(R.string.de_delete, (d, w) -> deleteNode(n))
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_save, (d, w) -> {
                    n.text = text(text);
                    n.hint = text(hint);
                    n.textSizeSp = parseFloat(text(size), n.textSizeSp);
                    n.textColor = text(color);
                    n.backgroundColor = text(bg);
                    n.cornerRadiusDp = parseFloat(text(radius), n.cornerRadiusDp);
                    n.widthDp = parseInt(text(width), n.widthDp);
                    n.heightDp = parseInt(text(height), n.heightDp);
                    n.gravity = text(gravity);
                    n.visible = visible.isChecked();
                    n.enabled = enabled.isChecked();
                    n.alpha = parseFloat(text(alpha), 1f);
                    if (fOptions != null) {
                        n.options = new ArrayList<>();
                        for (String line : text(fOptions).split("\\r?\\n")) {
                            if (!line.trim().isEmpty()) n.options.add(line.trim());
                        }
                    }
                    if (fProgress != null) n.progress = parseInt(text(fProgress), 0);
                    save();
                    updateStatuses();
                    toast(R.string.de_updated);
                }).show();
    }

    private void orderComponents() {
        if (model == null || model.nodes == null || model.nodes.isEmpty()) {
            toast(R.string.de_none);
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        ScrollView sv = new ScrollView(activity);
        sv.addView(box);
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < model.nodes.size(); i++) {
            final int index = i;
            Node n = model.nodes.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(activity);
            label.setText(nodeLabel(n));
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);
            MaterialButton up = new MaterialButton(activity);
            up.setText("↑");
            up.setMinWidth(0);
            up.setOnClickListener(v -> {
                if (index > 0) {
                    Collections.swap(model.nodes, index, index - 1);
                    save();
                    if (holder[0] != null) holder[0].dismiss();
                    orderComponents();
                }
            });
            MaterialButton down = new MaterialButton(activity);
            down.setText("↓");
            down.setMinWidth(0);
            down.setOnClickListener(v -> {
                if (index < model.nodes.size() - 1) {
                    Collections.swap(model.nodes, index, index + 1);
                    save();
                    if (holder[0] != null) holder[0].dismiss();
                    orderComponents();
                }
            });
            MaterialButton del = new MaterialButton(activity);
            del.setText(R.string.de_delete);
            del.setMinWidth(0);
            del.setOnClickListener(v -> {
                deleteNode(n);
                if (holder[0] != null) holder[0].dismiss();
                orderComponents();
            });
            row.addView(up);
            row.addView(down);
            row.addView(del);
            box.addView(row);
        }
        holder[0] = dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_order_components)
                .setView(sv)
                .setNegativeButton(R.string.de_close, null)
                .show();
    }

    private void deleteNode(Node n) {
        if (n == null || model == null) return;
        removeNode(model.nodes, n);
        if (model.events != null) {
            model.events.removeIf(e -> e != null && n.id.equals(e.nodeId));
        }
        save();
        updateStatuses();
        toast(R.string.de_deleted);
    }

    private boolean removeNode(List<Node> list, Node target) {
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            Node n = list.get(i);
            if (n == target) {
                list.remove(i);
                return true;
            }
            if (n != null && removeNode(n.children, target)) return true;
        }
        return false;
    }

    // ---------------- Lifecycle and automation ----------------

    private static final String[] SHOW_MODES = {"always", "once", "dont_show_again"};

    private void buildLifecycle(LinearLayout c) {
        if (model == null) {
            sectionLabel(c, activity.getString(R.string.de_no_model));
            return;
        }
        sectionLabel(c, activity.getString(R.string.de_group_lifecycle));
        switchRow(c, R.string.de_cancelable, model.cancelable, v -> {
            model.cancelable = v;
            save();
        });
        switchRow(c, R.string.de_draggable, model.draggable, v -> {
            model.draggable = v;
            save();
        });
        switchRow(c, R.string.de_prevent_duplicate, model.preventDuplicate, v -> {
            model.preventDuplicate = v;
            save();
        });
        switchRow(c, R.string.de_save_state, model.saveState, v -> {
            model.saveState = v;
            save();
        });
        switchRow(c, R.string.de_prevent_close, model.preventCloseDuringAction, v -> {
            model.preventCloseDuringAction = v;
            save();
        });
        TextInputEditText countdown = field(c, R.string.de_countdown,
                String.valueOf(model.countdownSeconds), InputType.TYPE_CLASS_NUMBER);
        countdown.addTextChangedListener(simpleWatcher(() -> {
            model.countdownSeconds = parseInt(text(countdown), 0);
            save();
        }));
        TextInputEditText priority = field(c, R.string.de_priority,
                String.valueOf(model.priority), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        priority.addTextChangedListener(simpleWatcher(() -> {
            model.priority = parseInt(text(priority), 0);
            save();
        }));
        String[] showLabels = {
                activity.getString(R.string.de_show_always),
                activity.getString(R.string.de_show_once),
                activity.getString(R.string.de_show_dont)
        };
        String current = activity.getString("once".equals(model.showMode)
                ? R.string.de_show_once : "dont_show_again".equals(model.showMode)
                ? R.string.de_show_dont : R.string.de_show_always);
        MaterialAutoCompleteTextView show = dropdown(c, R.string.de_show_mode,
                showLabels, current, null);
        show.setOnItemClickListener((p, v, pos, id) -> {
            model.showMode = SHOW_MODES[pos];
            save();
            updateStatuses();
        });
        MaterialAutoCompleteTextView hook = dropdown(c, R.string.de_hook_method,
                new String[]{"onCreate", "onResume"},
                "onResume".equals(model.hookMethod) ? "onResume" : "onCreate", null);
        hook.setOnItemClickListener((p, v, pos, id) -> {
            model.hookMethod = pos == 1 ? "onResume" : "onCreate";
            save();
        });
        action(c, R.string.de_preview, this::preview);
    }

    private android.text.TextWatcher simpleWatcher(Runnable run) {
        return new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                run.run();
            }
        };
    }

    // ---------------- iOS designs ----------------

    private void buildIos(LinearLayout c) {
        if (model == null) {
            sectionLabel(c, activity.getString(R.string.de_no_model));
            return;
        }
        TextView current = new TextView(activity);
        current.setText(activity.getString(R.string.de_ios_current,
                model.designName == null || model.designName.isEmpty() ? model.design : model.designName));
        current.setTextSize(12);
        current.setAlpha(0.8f);
        c.addView(current);
        action(c, R.string.de_ios_choose, this::chooseDesign);
        action(c, R.string.de_ios_custom, this::customDesign);
        action(c, R.string.de_preview, this::preview);
        TextView note = new TextView(activity);
        note.setText(R.string.de_ios_blur_note);
        note.setTextSize(11);
        note.setAlpha(0.6f);
        note.setPadding(0, dp(6), 0, 0);
        c.addView(note);
    }

    private void chooseDesign() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        List<DialogDesigns.Design> designs = DialogDesigns.all();
        List<String> labels = new ArrayList<>();
        for (DialogDesigns.Design d : designs) {
            labels.add(d.name + "  ·  " + d.category);
        }
        showSearchList(activity.getString(R.string.de_ios_choose), labels, i -> {
            DialogDesigns.Design d = designs.get(i);
            DialogDesigns.apply(model, d);
            save();
            updateStatuses();
            toast(R.string.de_selected, d.name);
            if (Build.VERSION.SDK_INT < 31 && d.blurRadiusDp > 0) {
                toast(R.string.de_ios_blur_note);
            }
        });
    }

    // ---------------- Analysis and repair ----------------

    private void buildAnalysis(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_analysis));
        action(c, R.string.de_analyze, this::analyze);
        action(c, R.string.de_fix_safe, this::fixSafe);
        action(c, R.string.de_analysis_report, this::showReport);
    }

    private void analyze() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        findings = DialogAnalyzer.analyze(project, model);
        updateStatuses();
        if (findings.isEmpty()) {
            toast(R.string.de_no_findings);
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView summary = new TextView(activity);
        summary.setText(DialogAnalyzer.summarize(findings));
        summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(summary);
        ScrollView sv = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Finding f : findings) {
            TextView tv = new TextView(activity);
            tv.setText(("error".equals(f.severity) ? "✗ " : "! ") + f.message
                    + (f.fixable ? "\n   ↳ " + f.fixDescription : ""));
            tv.setTextSize(13);
            tv.setPadding(0, dp(6), 0, dp(6));
            list.addView(tv);
        }
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_analysis_report)
                .setView(box)
                .setNegativeButton(R.string.de_close, null)
                .setPositiveButton(R.string.de_fix_safe, (d, w) -> fixSafe())
                .show();
    }

    private void fixSafe() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        if (findings.isEmpty()) findings = DialogAnalyzer.analyze(project, model);
        int changes = DialogAnalyzer.applySafeFixes(project, model, findings);
        findings = DialogAnalyzer.analyze(project, model);
        save();
        updateStatuses();
        toast(R.string.de_fix_done, changes);
    }

    private void showReport() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        if (findings.isEmpty()) findings = DialogAnalyzer.analyze(project, model);
        StringBuilder sb = new StringBuilder();
        sb.append(DialogAnalyzer.summarize(findings)).append("\n\n");
        if (findings.isEmpty()) sb.append(activity.getString(R.string.de_no_findings));
        for (Finding f : findings) {
            sb.append("[").append(f.severity).append("] ").append(f.message).append('\n');
            if (f.fixable) sb.append("  → ").append(f.fixDescription).append('\n');
        }
        showText(activity.getString(R.string.de_analysis_report), sb.toString());
    }

    // ---------------- Code editor ----------------

    private void buildEditor(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_editor));
        action(c, R.string.de_open_source, this::openSource);
        action(c, R.string.de_compare_changes, this::compareSource);
        action(c, R.string.de_source_info, this::showSourceInfo);
    }

    private void openSource() {
        if (model == null || model.sourceRef == null) {
            toast(R.string.de_no_source);
            return;
        }
        DialogModels.SourceRef ref = model.sourceRef;
        try {
            if (ref.path != null && !ref.path.isEmpty()) {
                File src = new File(ref.path);
                if (src.isFile()) {
                    try {
                        DialogModels.Backup b = DialogBackupStore.backup(activity, src, "before editor");
                        sourceBackup = new File(b.backupPath);
                    } catch (Throwable ignored) {
                    }
                    Intent intent = new Intent();
                    intent.setClassName(activity,
                            "io.github.abdurazaaqmohammed.ui.editor.TextEditorActivity");
                    intent.putExtra("path", src.getAbsolutePath());
                    activity.startActivity(intent);
                    return;
                }
            }
            if (ref.container != null && ref.container.matches("classes[0-9]*\\.dex")
                    && apk != null) {
                File dex = extractDex(ref.container);
                if (dex != null && dex.isFile()) {
                    Intent intent = new Intent();
                    intent.setClassName(activity,
                            "modder.hub.dexeditor.activity.DexEditorActivity");
                    ArrayList<String> dexes = new ArrayList<>();
                    dexes.add(dex.getAbsolutePath());
                    intent.putStringArrayListExtra("SelectedDexFiles", dexes);
                    intent.putExtra("zipFilePath", apk.getAbsolutePath());
                    intent.putExtra("zipEntryPath", ref.container);
                    activity.startActivity(intent);
                    return;
                }
            }
            showMessage(activity.getString(R.string.de_no_source),
                    "class: " + ref.className + "\nmethod: " + ref.methodName
                            + ref.methodDescriptor + "\ncontainer: " + ref.container);
        } catch (Throwable t) {
            showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
        }
    }

    private File extractDex(String entry) {
        if (apk == null || scan == null || scan.workDir == null) return null;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry e = zip.getEntry(entry);
            if (e == null) return null;
            File dir = new File(scan.workDir, "dex");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, DialogJson.safeName(entry));
            try (java.io.InputStream in = zip.getInputStream(e);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void compareSource() {
        if (model == null || model.sourceRef == null || model.sourceRef.path == null
                || model.sourceRef.path.isEmpty()) {
            toast(R.string.de_no_source);
            return;
        }
        File current = new File(model.sourceRef.path);
        if (!current.isFile()) {
            toast(R.string.de_no_source);
            return;
        }
        if (sourceBackup == null || !sourceBackup.isFile()) {
            showMessage(activity.getString(R.string.de_compare_changes),
                    activity.getString(R.string.de_none));
            return;
        }
        showText(activity.getString(R.string.de_compare_changes),
                DialogBackupStore.diff(sourceBackup, current));
    }

    private void showSourceInfo() {
        if (model == null || model.sourceRef == null) {
            toast(R.string.de_no_source);
            return;
        }
        DialogModels.SourceRef r = model.sourceRef;
        showText(activity.getString(R.string.de_source_info),
                "kind: " + r.kind + "\ncontainer: " + r.container
                        + "\npath: " + r.path + "\nclass: " + r.className
                        + "\nmethod: " + r.methodName + r.methodDescriptor
                        + "\nconfidence: " + r.confidence + "\n\n" + r.snippet);
    }

    private void customDesign() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextInputEditText bg = field(box, R.string.de_component_bg, model.backgroundColor,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText bg2 = field(box, R.string.de_value, model.backgroundEndColor,
                InputType.TYPE_CLASS_TEXT);
        TextInputEditText orient = field(box, R.string.de_value,
                String.valueOf(model.gradientOrientation), InputType.TYPE_CLASS_NUMBER);
        TextInputEditText radius = field(box, R.string.de_component_radius,
                String.valueOf(model.cornerRadiusDp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextInputEditText border = field(box, R.string.de_value,
                String.valueOf(model.borderWidthDp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextInputEditText borderColor = field(box, R.string.de_component_color,
                model.borderColor, InputType.TYPE_CLASS_TEXT);
        TextInputEditText blur = field(box, R.string.de_ios_blur,
                String.valueOf(model.blurRadiusDp), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextInputEditText dim = field(box, R.string.de_ios_dim,
                String.valueOf(model.dimAmount), InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        MaterialAutoCompleteTextView anim = dropdown(box, R.string.de_ios_animation,
                new String[]{"fade", "fade_scale", "scale_in", "slide_up", "slide_down", "pop"},
                model.animation, null);
        TextInputEditText titleColor = field(box, R.string.de_ios_title_color,
                model.titleColor, InputType.TYPE_CLASS_TEXT);
        TextInputEditText msgColor = field(box, R.string.de_ios_message_color,
                model.messageColor, InputType.TYPE_CLASS_TEXT);
        TextInputEditText btnColor = field(box, R.string.de_ios_button_color,
                model.buttonColor, InputType.TYPE_CLASS_TEXT);
        MaterialSwitch sheet = new MaterialSwitch(activity);
        sheet.setText(R.string.de_ios_bottom_sheet);
        sheet.setChecked(model.bottomSheet);
        box.addView(sheet);
        MaterialSwitch full = new MaterialSwitch(activity);
        full.setText(R.string.de_ios_full_width);
        full.setChecked(model.fullWidth);
        box.addView(full);
        MaterialSwitch glass = new MaterialSwitch(activity);
        glass.setText(R.string.de_ios_glass);
        glass.setChecked(model.glass);
        box.addView(glass);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_ios_custom)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_save, (d, w) -> {
                    model.design = "custom";
                    model.designName = activity.getString(R.string.de_ios_custom);
                    model.backgroundColor = text(bg);
                    model.backgroundEndColor = text(bg2);
                    model.gradientOrientation = parseInt(text(orient), 0);
                    model.cornerRadiusDp = parseFloat(text(radius), model.cornerRadiusDp);
                    model.borderWidthDp = parseFloat(text(border), 0f);
                    model.borderColor = text(borderColor);
                    model.blurRadiusDp = parseFloat(text(blur), 0f);
                    model.dimAmount = parseFloat(text(dim), 0.4f);
                    model.animation = text(anim);
                    model.titleColor = text(titleColor);
                    model.messageColor = text(msgColor);
                    model.buttonColor = text(btnColor);
                    model.bottomSheet = sheet.isChecked();
                    model.fullWidth = full.isChecked();
                    model.glass = glass.isChecked();
                    save();
                    updateStatuses();
                    toast(R.string.de_updated);
                }).show();
    }
    // ---------------- AI assistant ----------------

    private void buildAi(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_ai));
        if (!DialogAiService.isAvailable(activity)) {
            TextView tv = new TextView(activity);
            tv.setText(R.string.de_ai_unavailable);
            tv.setTextSize(12);
            tv.setPadding(0, dp(4), 0, dp(4));
            c.addView(tv);
            action(c, R.string.de_ai_unavailable, this::openAiSettings);
            return;
        }
        action(c, R.string.de_ai_explain, this::aiExplain);
        action(c, R.string.de_ai_generate, this::aiGenerate);
        action(c, R.string.de_ai_redesign, this::aiRedesign);
        action(c, R.string.de_ai_events, this::aiSuggestEvents);
        action(c, R.string.de_ai_analyze, this::aiAnalyze);
    }

    private void openAiSettings() {
        try {
            Intent intent = new Intent();
            intent.setClassName(activity,
                    "io.github.abdurazaaqmohammed.ui.editor.EditorSettingsActivity");
            intent.putExtra("from_dex", true);
            activity.startActivity(intent);
        } catch (Throwable t) {
            showMessage(activity.getString(R.string.de_ai_unavailable),
                    String.valueOf(t.getMessage()));
        }
    }

    private void aiExplain() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        final Busy busy = new Busy(R.string.de_ai_working);
        DialogAiService.explain(activity, project, model, (text, parsed, error) -> main.post(() -> {
            busy.dismiss();
            if (error != null) showMessage(activity.getString(R.string.de_error), error);
            else showText(activity.getString(R.string.de_ai_result), text);
        }));
    }

    private void aiGenerate() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextInputEditText prompt = field(box, R.string.de_ai_prompt, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_ai_generate)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_ai_generate, (d, w) -> {
                    String desc = text(prompt).trim();
                    if (desc.isEmpty()) return;
                    final Busy busy = new Busy(R.string.de_ai_working);
                    DialogAiService.generate(activity, desc, (text, parsed, error) -> main.post(() -> {
                        busy.dismiss();
                        if (error != null) {
                            showMessage(activity.getString(R.string.de_error), error);
                            return;
                        }
                        if (parsed == null) {
                            showText(activity.getString(R.string.de_ai_result), text
                                    + "\n\n" + activity.getString(R.string.de_ai_no_json));
                            return;
                        }
                        showAiResult(text, parsed, () -> {
                            setModel(parsed);
                            host.applyModelToEditor(parsed);
                        });
                    }));
                }).show();
    }

    private void aiRedesign() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextInputEditText style = field(box, R.string.de_ai_style,
                model.designName == null || model.designName.isEmpty() ? "Liquid Glass" : model.designName,
                InputType.TYPE_CLASS_TEXT);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_ai_redesign)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_ai_redesign, (d, w) -> {
                    final Busy busy = new Busy(R.string.de_ai_working);
                    DialogAiService.redesign(activity, model, text(style),
                            (text, parsed, error) -> main.post(() -> {
                                busy.dismiss();
                                if (error != null) {
                                    showMessage(activity.getString(R.string.de_error), error);
                                    return;
                                }
                                if (parsed == null) {
                                    showText(activity.getString(R.string.de_ai_result), text
                                            + "\n\n" + activity.getString(R.string.de_ai_no_json));
                                    return;
                                }
                                showAiResult(text, parsed, () -> {
                                    Model replaced = DialogLibrary.replace(model, parsed);
                                    setModel(replaced);
                                    host.applyModelToEditor(replaced);
                                });
                            }));
                }).show();
    }

    private void aiSuggestEvents() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        final Busy busy = new Busy(R.string.de_ai_working);
        DialogAiService.suggestEvents(activity, project, model,
                (text, parsed, error) -> main.post(() -> {
                    busy.dismiss();
                    if (error != null) {
                        showMessage(activity.getString(R.string.de_error), error);
                        return;
                    }
                    if (parsed == null) {
                        showText(activity.getString(R.string.de_ai_result), text
                                + "\n\n" + activity.getString(R.string.de_ai_no_json));
                        return;
                    }
                    showAiResult(text, parsed, () -> {
                        if (parsed.events != null) {
                            if (model.events == null) model.events = new ArrayList<>();
                            model.events.addAll(parsed.events);
                        }
                        save();
                        updateStatuses();
                        host.applyModelToEditor(model);
                    });
                }));
    }

    private void aiAnalyze() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        if (findings.isEmpty()) findings = DialogAnalyzer.analyze(project, model);
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) sb.append(f.severity).append(": ").append(f.message).append('\n');
        final Busy busy = new Busy(R.string.de_ai_working);
        DialogAiService.analyzeErrors(activity, project, model, sb.toString(),
                (text, parsed, error) -> main.post(() -> {
                    busy.dismiss();
                    if (error != null) showMessage(activity.getString(R.string.de_error), error);
                    else showText(activity.getString(R.string.de_ai_result), text);
                }));
    }

    private void showAiResult(String text, Model newModel, Runnable apply) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView result = new TextView(activity);
        result.setText(text == null ? "" : trunc(text, 1200));
        result.setTextSize(12);
        result.setTextIsSelectable(true);
        box.addView(result);
        if (newModel != null) {
            TextView before = new TextView(activity);
            before.setText(activity.getString(R.string.de_ai_diff) + "\n\n"
                    + activity.getString(R.string.de_before) + ":\n"
                    + trunc(DialogJson.toJson(model), 1800) + "\n\n"
                    + activity.getString(R.string.de_after) + ":\n"
                    + trunc(DialogJson.toJson(newModel), 1800));
            before.setTextSize(10);
            before.setTextIsSelectable(true);
            before.setPadding(0, dp(8), 0, dp(8));
            box.addView(before);
        }
        ScrollView sv = new ScrollView(activity);
        sv.addView(box);
        dialogUtil.getDialogBuilder()
                .setTitle(R.string.de_ai_result)
                .setView(sv)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.de_ai_apply, (d, w) -> apply.run())
                .show();
    }

    // ---------------- Testing ----------------

    private void buildTesting(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_testing));
        action(c, R.string.de_test_run, this::runTests);
        action(c, R.string.de_preview, this::preview);
        TextView note = new TextView(activity);
        note.setText(R.string.de_compat_note);
        note.setTextSize(11);
        note.setAlpha(0.6f);
        note.setPadding(0, dp(6), 0, 0);
        c.addView(note);
    }

    private void runTests() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        DialogSimulator.Report report = DialogSimulator.simulate(project, model);
        lastReport = report.text();
        updateStatuses();
        showText(activity.getString(R.string.de_test_results), lastReport);
    }

    // ---------------- Library and templates ----------------

    private void buildLibrary(LinearLayout c) {
        sectionLabel(c, activity.getString(R.string.de_group_library));
        action(c, R.string.de_lib_save, this::saveTemplate);
        action(c, R.string.de_lib_load, this::loadTemplate);
        action(c, R.string.de_lib_delete, this::deleteTemplate);
        action(c, R.string.de_lib_export, this::exportJson);
        action(c, R.string.de_lib_import, this::importJson);
        action(c, R.string.de_lib_merge, this::mergeDialogs);
        action(c, R.string.de_lib_replace, this::replaceDialog);
        action(c, R.string.de_lib_relations, this::showRelations);
    }

    private void saveTemplate() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        try {
            File f = DialogLibrary.saveTemplate(activity, model);
            toast(R.string.de_success);
            showText(activity.getString(R.string.de_lib_save), f.getAbsolutePath());
        } catch (Throwable t) {
            showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
        }
    }

    private void loadTemplate() {
        List<Model> templates = DialogLibrary.listTemplates(activity);
        if (templates.isEmpty()) {
            toast(R.string.de_no_templates);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Model m : templates) labels.add(m.name + " · " + m.id);
        showChoice(activity.getString(R.string.de_lib_load), labels, i -> {
            Model m = DialogJson.cloneModel(templates.get(i));
            setModel(m);
            host.applyModelToEditor(m);
            toast(R.string.de_selected, m.name);
        });
    }

    private void deleteTemplate() {
        List<Model> templates = DialogLibrary.listTemplates(activity);
        if (templates.isEmpty()) {
            toast(R.string.de_no_templates);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Model m : templates) labels.add(m.name + " · " + m.id);
        showChoice(activity.getString(R.string.de_lib_delete), labels, i -> {
            DialogLibrary.deleteTemplate(templates.get(i));
            updateStatuses();
            toast(R.string.de_deleted);
        });
    }

    private void exportJson() {
        if (model == null) {
            toast(R.string.de_no_model);
            return;
        }
        try {
            File f = DialogLibrary.export(activity, model, model.name);
            toast(R.string.de_exported, f.getName());
            showText(activity.getString(R.string.de_lib_export), f.getAbsolutePath());
        } catch (Throwable t) {
            showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
        }
    }

    private void importJson() {
        FilePickerDialog.Properties props = new FilePickerDialog.Properties();
        props.root = apk == null ? activity.getFilesDir() : apk.getParentFile();
        props.offset = props.root;
        props.selection_type = FilePickerDialog.FILE_SELECT;
        props.selection_mode = FilePickerDialog.SINGLE_MODE;
        props.extensions = new String[]{"json"};
        props.preferenceKey = "de_import_path";
        FilePickerDialog picker = new FilePickerDialog(activity, props);
        picker.setTitle(activity.getString(R.string.de_lib_import));
        picker.setDialogSelectionListener(files -> {
            if (files == null || files.length == 0) return;
            try {
                Model m = DialogLibrary.importModel(new File(files[0]));
                if (m == null) throw new Exception("Invalid JSON");
                setModel(m);
                host.applyModelToEditor(m);
                toast(R.string.de_selected, m.name);
            } catch (Throwable t) {
                showMessage(activity.getString(R.string.de_error), String.valueOf(t.getMessage()));
            }
        });
        picker.show();
    }

    private void mergeDialogs() {
        List<Model> pool = new ArrayList<>();
        if (project.dialogs != null) pool.addAll(project.dialogs);
        pool.addAll(DialogLibrary.listTemplates(activity));
        if (pool.size() < 2) {
            showMessage(activity.getString(R.string.de_lib_merge),
                    activity.getString(R.string.de_none));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Model m : pool) labels.add(m.name + " · " + m.id);
        showChoice(activity.getString(R.string.de_lib_merge), labels, i -> {
            Model first = pool.get(i);
            showChoice(activity.getString(R.string.de_lib_merge), labels, j -> {
                if (i == j) return;
                DialogLibrary.MergeResult result = DialogLibrary.merge(first, pool.get(j));
                if (result.model == null) return;
                setModel(result.model);
                host.applyModelToEditor(result.model);
                toast(R.string.de_merged);
                if (!result.warnings.isEmpty()) {
                    showText(activity.getString(R.string.de_lib_merge),
                            TextUtils.join("\n", result.warnings));
                }
            });
        });
    }

    private void replaceDialog() {
        List<Model> targets = new ArrayList<>();
        if (project.dialogs != null) targets.addAll(project.dialogs);
        if (targets.isEmpty()) {
            toast(R.string.de_no_model);
            return;
        }
        List<String> targetLabels = new ArrayList<>();
        for (Model m : targets) targetLabels.add(m.name + " · " + m.id);
        List<Model> replacements = new ArrayList<>();
        if (project.dialogs != null) replacements.addAll(project.dialogs);
        replacements.addAll(DialogLibrary.listTemplates(activity));
        List<String> replacementLabels = new ArrayList<>();
        for (Model m : replacements) replacementLabels.add(m.name + " · " + m.id);
        showChoice(activity.getString(R.string.de_lib_replace), targetLabels, i -> {
            Model target = targets.get(i);
            showChoice(activity.getString(R.string.de_lib_replace), replacementLabels, j -> {
                Model replacement = replacements.get(j);
                Model out = DialogLibrary.replace(target, replacement);
                setModel(out);
                host.applyModelToEditor(out);
                toast(R.string.de_updated);
            });
        });
    }

    private void showRelations() {
        List<String> relations = DialogLibrary.relations(project);
        if (relations.isEmpty()) {
            showMessage(activity.getString(R.string.de_lib_relations),
                    activity.getString(R.string.de_none));
            return;
        }
        showText(activity.getString(R.string.de_lib_relations), TextUtils.join("\n", relations));
    }

    // ---------------- helpers ----------------

    private String text(EditText e) {
        return e != null && e.getText() != null ? e.getText().toString() : "";
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s == null ? "" : s.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private File unusedFile(File file) {
        if (file == null || !file.exists()) return file;
        String name = file.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        File dir = file.getParentFile();
        int i = 2;
        File out = new File(dir, base + "_" + i + ext);
        while (out.exists()) {
            i++;
            out = new File(dir, base + "_" + i + ext);
        }
        return out;
    }
}
