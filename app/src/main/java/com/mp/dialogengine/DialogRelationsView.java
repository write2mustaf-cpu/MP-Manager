package com.mp.dialogengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency map for open_dialog links. Tap a box to select that dialog. */
public final class DialogRelationsView extends View {

    public interface OnNodeClick {
        void onClick(String dialogId);
    }

    private static final class Box {
        String id;
        String name;
        final RectF rect = new RectF();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Box> boxes = new ArrayList<>();
    private final List<String[]> edges = new ArrayList<>();
    private final float density;
    private OnNodeClick listener;
    private String selected = "";

    public DialogRelationsView(Context context, Project project) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        text.setColor(0xFF1B1B1F);
        text.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        text.setFakeBoldText(true);
        paint.setStrokeWidth(1.5f * density);
        if (project == null || project.dialogs == null) return;
        Map<String, Model> byId = new LinkedHashMap<>();
        for (Model m : project.dialogs) {
            if (m == null) continue;
            byId.put(m.id, m);
            Box b = new Box();
            b.id = m.id;
            b.name = m.name == null || m.name.isEmpty() ? m.id : m.name;
            boxes.add(b);
        }
        for (Model m : project.dialogs) {
            if (m == null || m.events == null) continue;
            for (DialogModels.Event e : m.events) {
                if (e == null || e.actions == null) continue;
                for (DialogModels.Action a : e.actions) {
                    if (a != null && "open_dialog".equals(a.type) && a.target != null
                            && byId.containsKey(a.target)) {
                        edges.add(new String[]{m.id, a.target});
                    }
                }
            }
        }
    }

    public void setOnNodeClick(OnNodeClick listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = Math.max(1, (boxes.size() + 1) / 2);
        int height = (int) (rows * 64 * density + 32 * density);
        setMeasuredDimension(width, Math.max((int) (300 * density), height));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boxes.isEmpty()) return;
        int width = getWidth();
        int height = getHeight();
        float pad = 14 * density;
        float boxW = Math.max(110 * density, (width - pad * 3) / 2);
        float boxH = 42 * density;
        float rowH = 64 * density;
        for (int i = 0; i < boxes.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            float left = pad + col * (boxW + pad);
            float top = pad + row * rowH;
            boxes.get(i).rect.set(left, top, Math.min(width - pad, left + boxW), top + boxH);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF7A7A7A);
        for (String[] e : edges) {
            RectF a = rect(e[0]);
            RectF b = rect(e[1]);
            if (a == null || b == null) continue;
            float x1 = a.centerX();
            float y1 = a.bottom;
            float x2 = b.centerX();
            float y2 = b.top;
            if (Math.abs(y1 - y2) < 1) {
                x1 = a.right;
                y1 = a.centerY();
                x2 = b.left;
                y2 = b.centerY();
            }
            canvas.drawLine(x1, y1, x2, y2, paint);
            Path head = new Path();
            float angle = (float) Math.atan2(y2 - y1, x2 - x1);
            head.moveTo(x2, y2);
            head.lineTo(x2 - (float) Math.cos(angle - 0.5) * 8 * density,
                    y2 - (float) Math.sin(angle - 0.5) * 8 * density);
            head.lineTo(x2 - (float) Math.cos(angle + 0.5) * 8 * density,
                    y2 - (float) Math.sin(angle + 0.5) * 8 * density);
            head.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(head, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        for (Box b : boxes) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(b.id.equals(selected) ? 0xFFD6E3FF : 0xFFF2F2F2);
            canvas.drawRoundRect(b.rect, 12 * density, 12 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xFF9A9A9A);
            canvas.drawRoundRect(b.rect, 12 * density, 12 * density, paint);
            String label = b.name;
            if (label.length() > 22) label = label.substring(0, 22) + "…";
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF1B1B1F);
            canvas.drawText(label, b.rect.left + 10 * density,
                    b.rect.centerY() + text.getTextSize() / 3f, text);
        }
        if (edges.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF666666);
            text.setFakeBoldText(false);
            canvas.drawText("No open_dialog links", pad, height - pad, text);
            text.setFakeBoldText(true);
        }
    }

    private RectF rect(String id) {
        for (Box b : boxes) if (b.id.equals(id)) return b.rect;
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();
            for (Box b : boxes) {
                if (b.rect.contains(x, y)) {
                    selected = b.id;
                    invalidate();
                    if (listener != null) listener.onClick(b.id);
                    return true;
                }
            }
        }
        return true;
    }

    public int boxCount() {
        return boxes.size();
    }
}
