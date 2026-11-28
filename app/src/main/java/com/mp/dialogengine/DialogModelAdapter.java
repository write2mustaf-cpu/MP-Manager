package com.mp.dialogengine;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;

import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;

import java.util.ArrayList;
import java.util.List;

import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.feature.apk.OverlayInjectorUtil;

/** Converts the new model into the legacy advanced editor payload. */
public final class DialogModelAdapter {

    private DialogModelAdapter() {
    }

    public static OverlayInjectorUtil.DialogOptions toDialogOptions(Context context,
                                                                    Model model,
                                                                    int[] unsupported) {
        OverlayInjectorUtil.DialogOptions dlg = new OverlayInjectorUtil.DialogOptions();
        dlg.advanced = true;
        dlg.widgets = new ArrayList<>();
        dlg.cancelable = model.cancelable;
        dlg.draggable = model.draggable;
        dlg.countdown = model.countdownSeconds > 0;
        dlg.countdownSeconds = Math.max(1, model.countdownSeconds);
        dlg.showOnce = "once".equals(model.showMode);
        dlg.dontShowAgain = "dont_show_again".equals(model.showMode);
        dlg.hookMethod = "onResume".equals(model.hookMethod) ? "onResume" : "onCreate";
        dlg.bgEnabled = true;
        dlg.bgColor1 = color(model.backgroundColor, 0xF2FFFFFF);
        dlg.bgColor2 = color(model.backgroundEndColor, 0);
        dlg.bgGradient = dlg.bgColor2 != 0;
        dlg.bgOrientation = Math.max(0, Math.min(3, model.gradientOrientation));
        dlg.cornerRadiusDp = model.cornerRadiusDp;
        dlg.borderWidthDp = model.borderWidthDp;
        dlg.borderColor = color(model.borderColor, 0);
        dlg.titleColor = color(model.titleColor, 0);
        dlg.dlgFont = model.font == null ? "" : model.font;
        dlg.dlgFontPath = model.fontPath == null ? "" : model.fontPath;
        int top = 16;
        if (model.title != null && !model.title.isEmpty()) {
            OverlayInjectorUtil.AdvWidget w = text(context, model.title, 19f, model.titleColor,
                    Typeface.BOLD, 16, top);
            dlg.widgets.add(w);
            top += 32;
        }
        if (model.message != null && !model.message.isEmpty()) {
            OverlayInjectorUtil.AdvWidget w = text(context, model.message, 14f, model.messageColor,
                    Typeface.NORMAL, 16, top);
            dlg.widgets.add(w);
            top += 28;
        }
        List<Node> flat = new ArrayList<>();
        flatten(model.nodes, flat);
        int i = 0;
        for (Node n : flat) {
            if (n == null) continue;
            OverlayInjectorUtil.AdvWidget w = null;
            switch (n.kind) {
                case "text":
                case "title":
                case "subtitle":
                case "message":
                    w = text(context, n.text, n.textSizeSp, n.textColor, n.fontStyle,
                            16, n.yDp > 0 ? n.yDp : top);
                    if (n.backgroundColor != null && !n.backgroundColor.isEmpty()) {
                        w.btnBg = color(n.backgroundColor, 0);
                        w.btnCornerRadiusDp = n.cornerRadiusDp;
                    }
                    break;
                case "image":
                    w = new OverlayInjectorUtil.AdvWidget();
                    w.kind = "image";
                    w.imageB64 = n.imageBase64 == null ? "" : n.imageBase64;
                    w.leftDp = n.xDp > 0 ? n.xDp : 16;
                    w.topDp = n.yDp > 0 ? n.yDp : top;
                    break;
                case "button":
                    w = new OverlayInjectorUtil.AdvWidget();
                    w.kind = "button";
                    w.text = n.text == null ? "" : n.text;
                    w.textSizeSp = n.textSizeSp > 0 ? n.textSizeSp : 14f;
                    w.textColor = color(n.textColor, 0);
                    w.fontStyle = n.fontStyle;
                    w.font = n.font == null ? "" : n.font;
                    w.fontPath = n.fontPath == null ? "" : n.fontPath;
                    w.btnBg = color(n.backgroundColor, 0);
                    w.btnBg2 = color(n.backgroundColor2, 0);
                    w.btnCornerRadiusDp = n.cornerRadiusDp;
                    w.btnBorderWidthDp = n.borderWidthDp;
                    w.btnBorderColor = color(n.borderColor, -1);
                    w.btnPaddingDp = (int) n.paddingDp;
                    w.leftDp = n.xDp > 0 ? n.xDp : 16;
                    w.topDp = n.yDp > 0 ? n.yDp : top;
                    String url = urlFor(model, n.id);
                    if (url != null && !url.isEmpty()) {
                        w.btnAction = context.getString(R.string.open_url);
                        w.url = url;
                    } else {
                        w.btnAction = context.getString(R.string.dismiss);
                    }
                    break;
                default:
                    if (unsupported != null && unsupported.length > 0) unsupported[0]++;
                    break;
            }
            if (w != null) {
                if (w.kind != null && !"image".equals(w.kind) && (w.topDp <= 0)) w.topDp = top;
                dlg.widgets.add(w);
                top += 40;
            }
            i++;
        }
        return dlg;
    }

    private static OverlayInjectorUtil.AdvWidget text(Context context, String text, float size,
                                                      String color, int style, int x, int y) {
        OverlayInjectorUtil.AdvWidget w = new OverlayInjectorUtil.AdvWidget();
        w.kind = "text";
        w.text = text == null ? "" : text;
        w.textSizeSp = size > 0 ? size : 15f;
        w.textColor = color(color, 0);
        w.fontStyle = style;
        w.leftDp = x;
        w.topDp = y;
        return w;
    }

    private static String urlFor(Model model, String nodeId) {
        if (model.events == null) return "";
        for (Event e : model.events) {
            if (e == null || !nodeId.equals(e.nodeId) || e.actions == null) continue;
            for (Action a : e.actions) {
                if (a != null && "open_url".equals(a.type)) {
                    return a.value != null ? a.value : a.target;
                }
            }
        }
        return "";
    }

    private static void flatten(List<Node> nodes, List<Node> out) {
        if (nodes == null) return;
        for (Node n : nodes) {
            if (n == null) continue;
            out.add(n);
            flatten(n.children, out);
        }
    }

    public static int color(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Color.parseColor(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
