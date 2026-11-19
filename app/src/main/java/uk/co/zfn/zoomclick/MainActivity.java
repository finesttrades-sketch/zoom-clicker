package uk.co.zfn.zoomclick;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings and button tester.
 * - Box to type the double-press gap in milliseconds (read live by the service).
 * - A tester that shows exactly what the Spigen remote sends when pressed and held.
 */
public class MainActivity extends Activity {

    static final String PREFS = "zfn";
    static final String KEY_GAP = "gap";
    static final int DEFAULT_GAP = 250;
    static final int MIN_GAP = 50;
    static final int MAX_GAP = 1000;
    static final String KEY_TRIPLE = "triple";
    static final int DEFAULT_TRIPLE = 700;
    static final String KEY_LABEL = "label";

    private TextView log;
    private TextView activity;
    private final List<String> lines = new ArrayList<>();
    private long lastDownAt = 0;
    private long lastEventAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ZFN Zoom Clicker");
        title.setTextSize(22);
        root.addView(title);

        TextView gapLabel = new TextView(this);
        gapLabel.setText("\nDouble-press gap (milliseconds)\n"
                + "Shorter = faster single photos, but double/triple presses must be quicker. "
                + "Allowed: " + MIN_GAP + " to " + MAX_GAP + ".");
        root.addView(gapLabel);

        final EditText gapBox = new EditText(this);
        gapBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        gapBox.setText(String.valueOf(prefs.getInt(KEY_GAP, DEFAULT_GAP)));
        root.addView(gapBox);

        final TextView gapStatus = new TextView(this);
        gapStatus.setText("Current: " + prefs.getInt(KEY_GAP, DEFAULT_GAP) + " ms");
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> {
            int value;
            try {
                value = Integer.parseInt(gapBox.getText().toString().trim());
            } catch (NumberFormatException e) {
                gapStatus.setText("Please type a number, e.g. 180");
                return;
            }
            value = Math.max(MIN_GAP, Math.min(MAX_GAP, value));
            prefs.edit().putInt(KEY_GAP, value).apply();
            gapBox.setText(String.valueOf(value));
            gapStatus.setText("Saved: " + value + " ms (works straight away)");
        });
        root.addView(save);
        root.addView(gapStatus);

        TextView triLabel = new TextView(this);
        triLabel.setText("\nTriple-press window (milliseconds)\n"
                + "After a double press changes the zoom, another press within this time "
                + "goes straight to 0.6x instead of taking a photo. Allowed: 200 to 2000.");
        root.addView(triLabel);

        final EditText triBox = new EditText(this);
        triBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        triBox.setText(String.valueOf(prefs.getInt(KEY_TRIPLE, DEFAULT_TRIPLE)));
        root.addView(triBox);

        final TextView triStatus = new TextView(this);
        triStatus.setText("Current: " + prefs.getInt(KEY_TRIPLE, DEFAULT_TRIPLE) + " ms");
        Button triSave = new Button(this);
        triSave.setText("Save");
        triSave.setOnClickListener(v -> {
            int value;
            try {
                value = Integer.parseInt(triBox.getText().toString().trim());
            } catch (NumberFormatException e) {
                triStatus.setText("Please type a number, e.g. 700");
                return;
            }
            value = Math.max(200, Math.min(2000, value));
            prefs.edit().putInt(KEY_TRIPLE, value).apply();
            triBox.setText(String.valueOf(value));
            triStatus.setText("Saved: " + value + " ms (works straight away)");
        });
        root.addView(triSave);
        root.addView(triStatus);

        final Button labelToggle = new Button(this);
        labelToggle.setText(prefs.getBoolean(KEY_LABEL, true)
                ? "Big zoom label on camera: ON" : "Big zoom label on camera: OFF");
        labelToggle.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean(KEY_LABEL, true);
            prefs.edit().putBoolean(KEY_LABEL, on).apply();
            labelToggle.setText(on ? "Big zoom label on camera: ON" : "Big zoom label on camera: OFF");
        });
        root.addView(labelToggle);

        Button access = new Button(this);
        access.setText("Open Accessibility settings");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        TextView actTitle = new TextView(this);
        actTitle.setText("\nRecent activity (newest first)");
        actTitle.setTextSize(16);
        root.addView(actTitle);

        LinearLayout actButtons = new LinearLayout(this);
        actButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> activity.setText(ZfnLog.all()));
        actButtons.addView(refresh);
        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ZFN log", ZfnLog.all()));
            refresh.setText("Refresh");
            copy.setText("Copied");
        });
        actButtons.addView(copy);
        root.addView(actButtons);

        activity = new TextView(this);
        activity.setTextSize(13);
        activity.setTextIsSelectable(true);
        activity.setText(ZfnLog.all());
        root.addView(activity);

        TextView testTitle = new TextView(this);
        testTitle.setText("\nButton tester: press the Spigen button here to see what it sends.");
        testTitle.setTextSize(16);
        root.addView(testTitle);

        log = new TextView(this);
        log.setTextSize(15);
        log.setText("(nothing yet)");
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activity != null) {
            activity.setText(ZfnLog.all());
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        long now = SystemClock.uptimeMillis();
        String what;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                lastDownAt = now;
                what = "PRESSED";
            } else {
                what = "still held (repeat " + event.getRepeatCount() + ")";
            }
        } else {
            what = "RELEASED after " + (now - lastDownAt) + " ms";
        }
        String since = lastEventAt == 0 ? "" : "   (+" + (now - lastEventAt) + " ms)";
        lastEventAt = now;
        String device = event.getDevice() == null ? "?" : event.getDevice().getName();
        lines.add(0, what + since + "  [" + device + "]");
        while (lines.size() > 14) {
            lines.remove(lines.size() - 1);
        }
        log.setText(String.join("\n", lines));
        return true;    // don't change the volume while testing
    }
}
