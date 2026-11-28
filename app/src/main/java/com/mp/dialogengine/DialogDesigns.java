package com.mp.dialogengine;

import java.util.ArrayList;
import java.util.List;

/**
 * Curated iOS-inspired presets. A preset only writes visual fields; logic,
 * nodes, variables and events are never touched when it is applied.
 */
public final class DialogDesigns {

    public static final class Design {
        public final String id;
        public final String name;
        public final String category;
        public final String background;
        public final String backgroundEnd;
        public final int gradientOrientation;
        public final float cornerRadiusDp;
        public final float borderWidthDp;
        public final String borderColor;
        public final float paddingDp;
        public final float blurRadiusDp;
        public final float dimAmount;
        public final String animation;
        public final String titleColor;
        public final String messageColor;
        public final String buttonColor;
        public final String buttonTextColor;
        public final boolean bottomSheet;
        public final boolean fullWidth;
        public final boolean glass;
        public final boolean dark;

        Design(String id, String name, String category, String background, String backgroundEnd,
               int gradientOrientation, float cornerRadiusDp, float borderWidthDp, String borderColor,
               float paddingDp, float blurRadiusDp, float dimAmount, String animation,
               String titleColor, String messageColor, String buttonColor, String buttonTextColor,
               boolean bottomSheet, boolean fullWidth, boolean glass, boolean dark) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.background = background;
            this.backgroundEnd = backgroundEnd;
            this.gradientOrientation = gradientOrientation;
            this.cornerRadiusDp = cornerRadiusDp;
            this.borderWidthDp = borderWidthDp;
            this.borderColor = borderColor;
            this.paddingDp = paddingDp;
            this.blurRadiusDp = blurRadiusDp;
            this.dimAmount = dimAmount;
            this.animation = animation;
            this.titleColor = titleColor;
            this.messageColor = messageColor;
            this.buttonColor = buttonColor;
            this.buttonTextColor = buttonTextColor;
            this.bottomSheet = bottomSheet;
            this.fullWidth = fullWidth;
            this.glass = glass;
            this.dark = dark;
        }
    }

    private static final List<Design> ALL = new ArrayList<>();

    static {
        add("material", "Material", "أساسي", "#F7FFFFFF", "", 0, 28, 0, "#00000000", 20, 0, 0.4f, "fade_scale",
                "#FF1B1B1F", "#FF44474E", "#FF3F5AA6", "#FFFFFFFF", false, false, false, false);
        add("ios_alert", "iOS Alert", "iOS", "#F2F2F2F7", "", 0, 14, 0, "#00000000", 20, 0, 0.45f, "fade_scale",
                "#FF000000", "#FF3C3C43", "#FF007AFF", "#FFFFFFFF", false, false, false, false);
        add("ios_alert_dark", "iOS Alert Dark", "iOS", "#F21C1C1E", "", 0, 14, 1, "#33FFFFFF", 20, 0, 0.55f, "fade_scale",
                "#FFFFFFFF", "#FFAEAEB2", "#FF0A84FF", "#FFFFFFFF", false, false, false, true);
        add("ios_sheet", "iOS Bottom Sheet", "iOS", "#FF1C1C1E", "", 0, 26, 0, "#22FFFFFF", 24, 0, 0.5f, "slide_up",
                "#FFFFFFFF", "#FFB0B0B5", "#FF0A84FF", "#FFFFFFFF", true, true, false, true);
        add("ios_sheet_light", "iOS Bottom Sheet Light", "iOS", "#FFF2F2F7", "", 0, 26, 0, "#22000000", 24, 0, 0.35f, "slide_up",
                "#FF000000", "#FF3C3C43", "#FF007AFF", "#FFFFFFFF", true, true, false, false);
        add("ios_action_sheet", "iOS Action Sheet", "iOS", "#F2F2F2F7", "", 0, 18, 0, "#00000000", 12, 0, 0.5f, "slide_up",
                "#FF8E8E93", "#FF3C3C43", "#FF007AFF", "#FFFFFFFF", true, true, false, false);
        add("liquid_glass", "Liquid Glass", "Glass", "#3DFFFFFF", "#1A7FD8FF", 2, 28, 1, "#66FFFFFF", 22, 28, 0.42f, "fade_scale",
                "#FF0B1B2A", "#FF37424E", "#FF0A84FF", "#FFFFFFFF", false, false, true, false);
        add("liquid_glass_dark", "Liquid Glass Dark", "Glass", "#3D000000", "#331A3D6B", 2, 28, 1, "#33FFFFFF", 22, 28, 0.55f, "fade_scale",
                "#FFFFFFFF", "#FFD1D1D6", "#FF64D2FF", "#FF000000", false, false, true, true);
        add("frosted_glass", "Frosted Glass", "Glass", "#66FFFFFF", "#40DDEBFF", 1, 24, 1, "#55FFFFFF", 22, 24, 0.35f, "fade_scale",
                "#FF16202B", "#FF46505A", "#FF3F5AA6", "#FFFFFFFF", false, false, true, false);
        add("frosted_glass_dark", "Frosted Glass Dark", "Glass", "#66000000", "#40202040", 1, 24, 1, "#33FFFFFF", 22, 24, 0.55f, "fade_scale",
                "#FFFFFFFF", "#FFC7C7CC", "#FF8E8E93", "#FFFFFFFF", false, false, true, true);
        add("dynamic_island", "Dynamic Island", "iOS", "#FF000000", "#FF1A1A1A", 3, 28, 0, "#00000000", 18, 0, 0.55f, "scale_in",
                "#FFFFFFFF", "#FFB8B8BE", "#FF30D158", "#FF000000", false, false, false, true);
        add("floating_panel", "Floating Panel", "Panel", "#F7FFFFFF", "", 0, 22, 1, "#22000000", 22, 0, 0.35f, "fade_scale",
                "#FF111827", "#FF4B5563", "#FF2563EB", "#FFFFFFFF", false, false, false, false);
        add("floating_panel_dark", "Floating Panel Dark", "Panel", "#F71F2937", "", 0, 22, 1, "#33FFFFFF", 22, 0, 0.55f, "fade_scale",
                "#FFFFFFFF", "#FFD1D5DB", "#FF60A5FA", "#FF0B1220", false, false, false, true);
        add("interactive_card", "Interactive Card", "Panel", "#FFFFFFFF", "#FFF1F5FF", 1, 20, 1, "#22000000", 22, 0, 0.3f, "slide_up",
                "#FF0F172A", "#FF475569", "#FF0EA5E9", "#FFFFFFFF", false, false, false, false);
        add("premium_notification", "Premium Notification", "Notification", "#FF101014", "#FF232336", 1, 18, 1, "#33FFFFFF", 18, 0, 0.5f, "slide_down",
                "#FFFFFFFF", "#FFC7C7CC", "#FFBF5AF2", "#FFFFFFFF", false, true, false, true);
        add("animated_dialog", "Animated Dialog", "Motion", "#FF111827", "#FF1D4ED8", 2, 26, 1, "#44FFFFFF", 22, 0, 0.5f, "pop",
                "#FFFFFFFF", "#FFDBEAFE", "#FF38BDF8", "#FF06121F", false, false, false, true);
        add("neumorphic", "Neumorphic", "Panel", "#FFE8EDF5", "#FFD7DEE9", 3, 24, 1, "#55FFFFFF", 22, 0, 0.25f, "fade_scale",
                "#FF243447", "#FF52657A", "#FF4F6BED", "#FFFFFFFF", false, false, false, false);
        add("glass_blue", "Glass Blue", "Glass", "#553B82F6", "#3322D3EE", 2, 26, 1, "#66FFFFFF", 22, 26, 0.4f, "fade_scale",
                "#FFFFFFFF", "#FFDBEAFE", "#FFEFF6FF", "#FF0B1B2A", false, false, true, true);
        add("glass_purple", "Glass Purple", "Glass", "#556D28D9", "#334C1D95", 2, 26, 1, "#66FFFFFF", 22, 26, 0.4f, "fade_scale",
                "#FFFFFFFF", "#FFEDE9FE", "#FFF5F3FF", "#FF1E1B4B", false, false, true, true);
        add("glass_green", "Glass Green", "Glass", "#5516A34A", "#33065F46", 2, 26, 1, "#66FFFFFF", 22, 26, 0.4f, "fade_scale",
                "#FFFFFFFF", "#FFD1FAE5", "#FFECFDF5", "#FF052E16", false, false, true, true);
        add("minimal_light", "Minimal Light", "Minimal", "#FFFFFFFF", "", 0, 16, 1, "#14000000", 20, 0, 0.25f, "fade",
                "#FF111111", "#FF555555", "#FF111111", "#FFFFFFFF", false, false, false, false);
        add("minimal_dark", "Minimal Dark", "Minimal", "#FF0B0B0C", "", 0, 16, 1, "#22FFFFFF", 20, 0, 0.55f, "fade",
                "#FFFFFFFF", "#FFB3B3B8", "#FFFFFFFF", "#FF000000", false, false, false, true);
        add("midnight", "Midnight", "Gradient", "#FF050816", "#FF111C44", 2, 24, 1, "#22FFFFFF", 22, 0, 0.55f, "fade_scale",
                "#FFFFFFFF", "#FFB6C2E2", "#FF6366F1", "#FFFFFFFF", false, false, false, true);
        add("sunset", "Sunset", "Gradient", "#FFFF6B6B", "#FFFFB86B", 1, 24, 0, "#00000000", 22, 0, 0.35f, "fade_scale",
                "#FF3B0A0A", "#FF5B1E1E", "#FF7C2D12", "#FFFFFFFF", false, false, false, false);
        add("ocean", "Ocean", "Gradient", "#FF0EA5E9", "#FF0F766E", 2, 24, 0, "#00000000", 22, 0, 0.4f, "fade_scale",
                "#FFFFFFFF", "#FFD1FAE5", "#FF0369A1", "#FFFFFFFF", false, false, false, true);
        add("aurora", "Aurora", "Gradient", "#FF312E81", "#FF0F766E", 3, 26, 1, "#33FFFFFF", 22, 0, 0.5f, "fade_scale",
                "#FFFFFFFF", "#FFE0E7FF", "#FF22D3EE", "#FF0B1020", false, false, true, true);
        add("spotlight", "Spotlight", "Motion", "#FF111111", "#FF2A2A2A", 2, 20, 1, "#33FFFFFF", 20, 0, 0.6f, "scale_in",
                "#FFFFFFFF", "#FFD4D4D8", "#FFFACC15", "#FF1C1917", false, false, false, true);
        add("compact", "Compact", "Layout", "#F7FFFFFF", "", 0, 14, 0, "#00000000", 14, 0, 0.35f, "fade",
                "#FF111827", "#FF4B5563", "#FF2563EB", "#FFFFFFFF", false, false, false, false);
        add("wide", "Wide", "Layout", "#F7FFFFFF", "", 0, 18, 0, "#00000000", 24, 0, 0.35f, "fade_scale",
                "#FF111827", "#FF4B5563", "#FF2563EB", "#FFFFFFFF", false, true, false, false);
        add("confirmation_panel", "Confirmation Panel", "Panel", "#FF1F2937", "", 0, 20, 1, "#33FFFFFF", 22, 0, 0.5f, "fade_scale",
                "#FFFFFFFF", "#FFD1D5DB", "#FFEF4444", "#FFFFFFFF", false, false, false, true);
        add("toast_card", "Toast Card", "Notification", "#F71F1F1F", "", 0, 18, 1, "#22FFFFFF", 16, 0, 0.35f, "slide_up",
                "#FFFFFFFF", "#FFD4D4D8", "#FF3B82F6", "#FFFFFFFF", false, false, false, true);
    }

    private static void add(String id, String name, String category, String bg, String bgEnd,
                            int orientation, float radius, float borderWidth, String borderColor,
                            float padding, float blur, float dim, String animation,
                            String titleColor, String messageColor, String buttonColor,
                            String buttonTextColor, boolean bottomSheet, boolean fullWidth,
                            boolean glass, boolean dark) {
        ALL.add(new Design(id, name, category, bg, bgEnd, orientation, radius, borderWidth,
                borderColor, padding, blur, dim, animation, titleColor, messageColor,
                buttonColor, buttonTextColor, bottomSheet, fullWidth, glass, dark));
    }

    private DialogDesigns() {
    }

    public static List<Design> all() {
        return new ArrayList<>(ALL);
    }

    public static Design find(String id) {
        if (id == null) return null;
        for (Design d : ALL) if (d.id.equals(id)) return d;
        return null;
    }

    public static void apply(DialogModels.Model model, Design design) {
        if (model == null || design == null) return;
        model.design = design.id;
        model.designName = design.name;
        model.backgroundColor = design.background;
        model.backgroundEndColor = design.backgroundEnd;
        model.gradientOrientation = design.gradientOrientation;
        model.cornerRadiusDp = design.cornerRadiusDp;
        model.borderWidthDp = design.borderWidthDp;
        model.borderColor = design.borderColor;
        model.paddingDp = design.paddingDp;
        model.blurRadiusDp = design.blurRadiusDp;
        model.dimAmount = design.dimAmount;
        model.animation = design.animation;
        model.titleColor = design.titleColor;
        model.messageColor = design.messageColor;
        model.buttonColor = design.buttonColor;
        model.buttonTextColor = design.buttonTextColor;
        model.bottomSheet = design.bottomSheet;
        model.fullWidth = design.fullWidth;
        model.glass = design.glass;
        model.dark = design.dark;
    }
}
