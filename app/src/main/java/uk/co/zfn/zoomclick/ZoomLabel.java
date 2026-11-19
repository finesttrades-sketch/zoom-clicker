package uk.co.zfn.zoomclick;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Big zoom label shown over the camera's round shutter button, e.g. "3×".
 * It can't be touched (taps go straight through to the camera), it never appears in photos,
 * and it turns with the phone so it stays upright in landscape. Main thread only.
 */
final class ZoomLabel {
    private final Context context;
    private final WindowManager wm;
    private TextView view;
    private boolean shown = false;
    private OrientationEventListener tilt;
    private WindowManager.LayoutParams lp;

    ZoomLabel(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShown() {
        return shown;
    }

    /** Show the label centred on the shutter button (or top right if we don't know where it is). */
    void show(String text, Rect shutter) {
        if (view == null) {
            create();
        }
        view.setText(text);
        float d = context.getResources().getDisplayMetrics().density;
        int size = (int) (104 * d);
        WindowManager.LayoutParams want = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (shutter != null && shutter.width() > 0) {
            want.gravity = Gravity.TOP | Gravity.LEFT;
            want.x = shutter.centerX() - size / 2;
            want.y = shutter.centerY() - size / 2;
        } else {
            want.gravity = Gravity.TOP | Gravity.END;
            want.x = (int) (12 * d);
            want.y = (int) (110 * d);
        }
        try {
            if (!shown) {
                wm.addView(view, want);
                lp = want;
                shown = true;
                if (tilt != null && tilt.canDetectOrientation()) {
                    tilt.enable();
                }
            } else if (lp == null || lp.x != want.x || lp.y != want.y || lp.gravity != want.gravity) {
                wm.updateViewLayout(view, want);
                lp = want;
            }
        } catch (Exception e) {
            ZfnLog.add("zoom label couldn't be shown: " + e.getClass().getSimpleName());
        }
    }

    void hide() {
        if (shown && view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
                // already gone
            }
        }
        shown = false;
        if (tilt != null) {
            tilt.disable();
        }
    }

    private void create() {
        float d = context.getResources().getDisplayMetrics().density;
        view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(34);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(170, 0, 0, 0));
        bg.setShape(GradientDrawable.OVAL);   // round, like the shutter button
        view.setBackground(bg);
        tilt = new OrientationEventListener(context) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN || view == null) {
                    return;
                }
                int snap = ((degrees + 45) / 90 % 4) * 90;   // 0, 90, 180 or 270
                view.setRotation(-snap);
            }
        };
    }

    /** "0.6×", "3×", "10×", "2.4×". */
    static String format(double value) {
        if (value <= 0) {
            return "?";
        }
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return ((int) Math.rint(value)) + "×";
        }
        return String.format(java.util.Locale.UK, "%.1f", value) + "×";
    }
}
