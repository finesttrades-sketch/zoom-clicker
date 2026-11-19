package uk.co.zfn.zoomclick;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZFN Zoom Clicker
 *
 * 1 press = photo, 2 presses = next zoom (0.6x -> 3x -> 10x -> 0.6x), 3 presses = 0.6x.
 * Outside the Samsung camera the button works as normal.
 *
 * Speed rules (v1.15):
 * - The button handler never waits for the camera app. Deciding "is the camera in front?"
 *   uses only the window list, which the system answers instantly.
 * - The photo is a tap on the shutter's known position, sent straight to the screen.
 * - Anything that has to read the camera's screen (zoom buttons, finding the shutter)
 *   runs on a separate worker thread, and is dropped if it would happen too late.
 */
public class ZoomClickService extends AccessibilityService {

    // ------------------------------------------------------------------
    // Settings you can change
    // ------------------------------------------------------------------

    /**
     * Zoom steps, in order. Each step lists the names the camera might use for that
     * button; the first one found on screen is tapped.
     */
    private static final String[][] ZOOM_STEPS = {
            {"3", "Telephoto lens", "Zoom 3", "3x"},                              // 3x
            {"10", "Zoom 10", "10x", "Super telephoto lens"},                     // 10x
            {".6", "0.6", "Ultra wide lens", "Zoom 0.6", "0.6x"}                  // 0.6x (default)
    };

    /** What to show on screen after each step. */
    private static final String[] STEP_NAMES = {"3x", "10x", "0.6x"};

    /** Only react to buttons from a device whose name contains this. Empty = any device. */
    private static final String REMOTE_NAME_CONTAINS = "Spigen";

    /** Samsung Camera app. */
    private static final String CAMERA_PACKAGE = "com.sec.android.app.camera";

    /** A photo or zoom that can't start within this long after the press is dropped. */
    private static final long TOO_LATE_MS = 1500;

    // ------------------------------------------------------------------

    private boolean swallowUntilUp = false;
    private boolean waitingForSecondPress = false;
    private int pressCount = 0;               // presses in the current burst (1, 2 or 3)
    private long burstStartedAt = 0;
    /** When the last double press (zoom step) happened: a press soon after it = triple. */
    private long lastDoubleAt = 0;
    private volatile AccessibilityNodeInfo cachedShutter = null;
    private volatile int nextStep = 0;
    private volatile String lastForegroundPackage = "";
    private volatile Rect lastShutterBounds = null;
    private volatile double lastFromZoom = -1;
    private volatile double lastSetZoom = -1;
    private volatile long lastSetAt = 0;
    /** Window ids that belong to the camera, learnt from its own events. */
    private final List<Integer> cameraWindowIds = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;

    /** Runs a short gap after the last release: decides single (photo) or double (zoom). */
    private final Runnable decide = new Runnable() {
        @Override
        public void run() {
            waitingForSecondPress = false;
            int n = pressCount;
            pressCount = 0;
            if (n == 1) {
                takePhoto(burstStartedAt);
            } else if (n == 2) {
                final long pressedAt = burstStartedAt;
                runLater("zoom", pressedAt, () -> stepZoom());
            }
        }
    };

    /** Re-reads where the shutter button is (worker thread). */
    private final Runnable refreshShutter = new Runnable() {
        @Override
        public void run() {
            long t = SystemClock.uptimeMillis();
            for (AccessibilityNodeInfo root : roots()) {
                AccessibilityNodeInfo shutter = findShutter(root);
                if (shutter != null) {
                    Rect r = new Rect();
                    shutter.getBoundsInScreen(r);
                    if (r.width() > 0 && r.height() > 0) {
                        lastShutterBounds = r;
                        cachedShutter = shutter;
                    }
                    break;
                }
            }
            long took = SystemClock.uptimeMillis() - t;
            if (took > 800) {
                ZfnLog.add("camera was slow to answer (" + took + " ms) while checking the shutter");
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        workerThread = new HandlerThread("zfn-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        ZfnLog.add("service started");
        Toast.makeText(this, "ZFN Zoom Clicker is on", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    /** Run a job on the worker thread, unless it would start too long after the press. */
    private void runLater(final String what, final long pressedAt, final Runnable job) {
        if (worker == null) {
            return;
        }
        worker.post(() -> {
            long late = SystemClock.uptimeMillis() - pressedAt;
            if (late > TOO_LATE_MS) {
                ZfnLog.add(what + " dropped: it would have happened " + late + " ms after the press");
                return;
            }
            long t = SystemClock.uptimeMillis();
            try {
                job.run();
            } catch (Exception e) {
                ZfnLog.add(what + " failed: " + e.getClass().getSimpleName());
            }
            long took = SystemClock.uptimeMillis() - t;
            ZfnLog.add(what + " done in " + took + " ms" + (took > 800 ? "  <- camera slow" : ""));
        });
    }

    private void scheduleShutterRefresh(long delayMs) {
        if (worker != null) {
            worker.removeCallbacks(refreshShutter);
            worker.postDelayed(refreshShutter, delayMs);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (!fromRemote(event)) {
            return false;
        }
        int action = event.getAction();

        if (action == KeyEvent.ACTION_UP) {
            if (waitingForSecondPress) {
                // released: wait a short gap to see if another press follows
                handler.removeCallbacks(decide);
                handler.postDelayed(decide, pref(MainActivity.KEY_GAP, MainActivity.DEFAULT_GAP));
            }
            if (swallowUntilUp) {
                swallowUntilUp = false;
                return true;
            }
            return false;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return swallowUntilUp;
        }
        if (event.getRepeatCount() > 0) {
            return swallowUntilUp;      // button held down: ignore the repeats
        }
        if (!cameraInFront()) {
            ZfnLog.add("press ignored: camera not in front (on screen: " + topAppWindowTitle() + ")");
            return false;               // outside the camera the button works as normal
        }

        swallowUntilUp = true;          // the camera never sees the button itself
        long now = SystemClock.uptimeMillis();
        handler.removeCallbacks(decide);

        // A press shortly after a double press = triple press: straight to 0.6x.
        if (lastDoubleAt > 0
                && now - lastDoubleAt <= pref(MainActivity.KEY_TRIPLE, MainActivity.DEFAULT_TRIPLE)) {
            ZfnLog.add("triple press (" + (now - lastDoubleAt) + " ms after the double)");
            lastDoubleAt = 0;
            waitingForSecondPress = false;
            pressCount = 0;
            runLater("0.6x", now, () -> resetToWidest());
            return true;
        }
        lastDoubleAt = 0;

        if (waitingForSecondPress) {
            // second press: change zoom straight away (no more waiting)
            waitingForSecondPress = false;
            pressCount = 0;
            lastDoubleAt = now;
            ZfnLog.add("double press (" + (now - burstStartedAt) + " ms after the first)");
            runLater("zoom", now, () -> stepZoom());
        } else {
            // first press: photo, unless a second press follows within the gap (on release)
            waitingForSecondPress = true;
            pressCount = 1;
            burstStartedAt = now;
        }
        return true;
    }

    /** A timing setting from the app's own screen (milliseconds). */
    private long pref(String key, int fallback) {
        try {
            return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Take a photo. Fast way: tap the shutter's known position straight on the screen
     * (doesn't wait for the camera app at all). If we don't know where it is yet, find it
     * on the worker thread.
     */
    private void takePhoto(final long pressedAt) {
        Rect r = lastShutterBounds;
        if (r != null) {
            if (!cameraInFront()) {
                ZfnLog.add("photo cancelled: camera no longer in front");
                return;
            }
            tapAt(r.centerX(), r.centerY());
            ZfnLog.add("photo: shutter tapped " + (SystemClock.uptimeMillis() - pressedAt) + " ms after press");
            return;
        }
        runLater("photo (first, finding shutter)", pressedAt, () -> {
            refreshShutter.run();
            AccessibilityNodeInfo s = cachedShutter;
            if (s != null && s.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return;
            }
            Rect b = lastShutterBounds;
            if (b != null) {
                tapAt(b.centerX(), b.centerY());
            } else {
                ZfnLog.add("photo: couldn't find the shutter button");
            }
        });
    }

    private void tapAt(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription tap = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        dispatchGesture(tap, null, null);
    }

    private AccessibilityNodeInfo findShutter(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isVisibleToUser() && node.isClickable()) {
            CharSequence d = node.getContentDescription();
            String id = node.getViewIdResourceName();
            boolean byName = d != null && d.toString().trim().toLowerCase().startsWith("take picture");
            boolean byId = id != null && id.toLowerCase().contains("shutter");
            if (byName || byId) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findShutter(node.getChild(i));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private boolean fromRemote(KeyEvent event) {
        if (REMOTE_NAME_CONTAINS.isEmpty()) {
            return true;
        }
        InputDevice device = event.getDevice();
        if (device == null || device.getName() == null) {
            return true;
        }
        return device.getName().toLowerCase().contains(REMOTE_NAME_CONTAINS.toLowerCase());
    }

    /** The app window currently on top (from the system's window list: instant). */
    private AccessibilityWindowInfo topAppWindow() {
        try {
            AccessibilityWindowInfo first = null;
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                if (w.isActive() || w.isFocused()) {
                    return w;
                }
                if (first == null) {
                    first = w;
                }
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private String topAppWindowTitle() {
        AccessibilityWindowInfo w = topAppWindow();
        if (w == null) {
            return "?";
        }
        CharSequence t = w.getTitle();
        return t == null ? "untitled window" : t.toString();
    }

    private boolean isCameraWindow(AccessibilityWindowInfo w) {
        if (w == null) {
            return false;
        }
        CharSequence t = w.getTitle();
        if (t != null && t.toString().toLowerCase().contains("camera")) {
            return true;
        }
        synchronized (cameraWindowIds) {
            return cameraWindowIds.contains(w.getId()) && CAMERA_PACKAGE.equals(lastForegroundPackage);
        }
    }

    /**
     * Is the Samsung camera on screen? Uses only the window list (no questions to the camera
     * app), so it answers instantly even while the camera is busy saving a photo.
     */
    private boolean cameraInFront() {
        AccessibilityWindowInfo top = topAppWindow();
        if (top != null) {
            return isCameraWindow(top);
        }
        return CAMERA_PACKAGE.equals(lastForegroundPackage);
    }

    /** The zoom levels we cycle through, in order. */
    private static final double[] TARGETS = {3.0, 10.0, 0.6};

    /** One button in the camera's zoom bar. */
    private static final class ZoomButton {
        AccessibilityNodeInfo tap;
        double value;
        boolean selected;
        int left;
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d*\\.?\\d+)");

    /** Tap the widest zoom in the bar (0.6x). Returns true if it was tapped. */
    private boolean resetToWidest() {
        ZoomButton widest = null;
        for (ZoomButton b : zoomBar()) {
            if (widest == null || b.value < widest.value) {
                widest = b;
            }
        }
        if (widest == null) {
            for (String label : ZOOM_STEPS[2]) {
                if (clickByLabel(label)) {
                    nextStep = 0;
                    return true;
                }
            }
            return false;
        }
        if (widest.selected) {
            return true;                // already there
        }
        if (Math.abs(lastSetZoom - widest.value) < 0.05
                && SystemClock.uptimeMillis() - lastSetAt < 1500) {
            return true;                // we only just tapped it (screen not caught up yet)
        }
        if (widest.tap.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastFromZoom = -1;
            lastSetZoom = widest.value;
            lastSetAt = SystemClock.uptimeMillis();
            nextStep = 0;
            ZfnLog.add("zoom -> " + widest.value + "x");
            return true;
        }
        return false;
    }

    /**
     * Work out where we are from the zoom bar (the selected zoom shows an "x", e.g. "3x",
     * the others are plain numbers), then tap the next one in the cycle 3x -> 10x -> 0.6x.
     */
    private void stepZoom() {
        List<ZoomButton> bar = zoomBar();
        double now = -1;
        for (ZoomButton b : bar) {
            if (b.selected) {
                now = b.value;
            }
        }
        if (now <= 0) {
            now = currentZoomOnScreen();
        }
        // If the screen still shows the zoom we just switched away from, it hasn't caught up
        // yet: trust the zoom we set ourselves.
        long t = SystemClock.uptimeMillis();
        if (lastSetZoom > 0 && t - lastSetAt < 8000
                && (now <= 0 || Math.abs(now - lastFromZoom) < 0.05)) {
            now = lastSetZoom;
        }
        int target;
        if (now > 0) {
            if (now < 2.95) {
                target = 0;          // 0.6x, 1x or 2x  -> 3x
            } else if (now < 9.95) {
                target = 1;          // 3x or 5x        -> 10x
            } else {
                target = 2;          // 10x and above   -> 0.6x
            }
        } else {
            target = nextStep;       // couldn't read the bar: use our own count
        }

        // 1st choice: the button in the zoom bar showing that number
        ZoomButton pick = null;
        for (ZoomButton b : bar) {
            if (!b.selected && Math.abs(b.value - TARGETS[target]) < 0.05) {
                pick = b;
            }
        }
        // for 0.6x, the leftmost (smallest) button in the bar will do
        if (pick == null && target == 2) {
            for (ZoomButton b : bar) {
                if (!b.selected && (pick == null || b.value < pick.value)) {
                    pick = b;
                }
            }
        }
        if (pick != null && pick.tap.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            nextStep = (target + 1) % TARGETS.length;
            lastFromZoom = now;
            lastSetZoom = pick.value;
            lastSetAt = SystemClock.uptimeMillis();
            ZfnLog.add("zoom " + now + "x -> " + pick.value + "x");
            return;
        }

        // 2nd choice: the old way, by button name (only for the zoom we want)
        for (String label : ZOOM_STEPS[target]) {
            if (clickByLabel(label)) {
                nextStep = (target + 1) % ZOOM_STEPS.length;
                return;
            }
        }
    }

    /** All the buttons in the camera's zoom bar, read from the numbers shown on them. */
    private List<ZoomButton> zoomBar() {
        List<ZoomButton> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        // Samsung camera: each zoom button is "zoom_lens_text_button_main",
        // described as "Zoom 3, Button", and the current one is marked selected.
        for (AccessibilityNodeInfo root : roots()) {
            gatherLensButtons(root, out, seen);
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (AccessibilityNodeInfo root : roots()) {
            gatherZoomButtons(root, out, seen);
        }
        return out;
    }

    private void gatherLensButtons(AccessibilityNodeInfo node, List<ZoomButton> out, List<String> seen) {
        if (node == null) {
            return;
        }
        String id = node.getViewIdResourceName();
        if (id != null && id.endsWith("zoom_lens_text_button_main")) {
            node.refresh();   // make sure we see the current state, not a cached one
        }
        CharSequence d = node.getContentDescription();
        if (node.isVisibleToUser() && id != null && id.endsWith("zoom_lens_text_button_main") && d != null) {
            Matcher m = NUMBER.matcher(d.toString());
            if (m.find()) {
                try {
                    double value = Double.parseDouble(m.group(1));
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    String key = r.left + "," + r.top;
                    if (!seen.contains(key)) {
                        boolean selected = node.isSelected();
                        for (int i = 0; i < node.getChildCount(); i++) {
                            AccessibilityNodeInfo c = node.getChild(i);
                            if (c != null) {
                                c.refresh();
                            }
                            if (c != null && c.isSelected()) {
                                selected = true;
                            }
                        }
                        ZoomButton b = new ZoomButton();
                        b.tap = node;
                        b.value = value;
                        b.selected = selected;
                        b.left = r.left;
                        out.add(b);
                        seen.add(key);
                    }
                } catch (NumberFormatException ignored) {
                    // not a zoom button
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            gatherLensButtons(node.getChild(i), out, seen);
        }
    }

    private void gatherZoomButtons(AccessibilityNodeInfo node, List<ZoomButton> out, List<String> seen) {
        if (node == null) {
            return;
        }
        if (node.isVisibleToUser()) {
            String id = node.getViewIdResourceName();
            boolean zoomId = id != null && id.toLowerCase().contains("zoom");
            for (CharSequence cs : new CharSequence[]{node.getText(), node.getContentDescription()}) {
                if (cs == null) {
                    continue;
                }
                String raw = cs.toString().trim();
                String v = raw.replace("\u00D7", "x").replace("X", "x").replace(" ", "");
                boolean plainNumber = v.matches("[0-9]*\\.?[0-9]+x?");
                boolean zoomLabel = raw.toLowerCase().startsWith("zoom") || zoomId;
                if (!plainNumber && !zoomLabel) {
                    continue;
                }
                Matcher m = NUMBER.matcher(v);
                if (!m.find()) {
                    continue;
                }
                double value;
                try {
                    value = Double.parseDouble(m.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (value <= 0 || value > 200) {
                    continue;
                }
                AccessibilityNodeInfo tap = node;
                while (tap != null && !tap.isClickable()) {
                    tap = tap.getParent();
                }
                if (tap == null || !isSmall(tap)) {
                    continue;
                }
                Rect r = new Rect();
                tap.getBoundsInScreen(r);
                String key = r.left + "," + r.top;
                ZoomButton existing = null;
                int idx = seen.indexOf(key);
                if (idx >= 0) {
                    existing = out.get(idx);
                }
                boolean selected = plainNumber && v.endsWith("x");
                if (existing != null) {
                    existing.selected |= selected || node.isSelected();
                    continue;
                }
                ZoomButton b = new ZoomButton();
                b.tap = tap;
                b.value = value;
                b.selected = selected || node.isSelected() || tap.isSelected();
                b.left = r.left;
                out.add(b);
                seen.add(key);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            gatherZoomButtons(node.getChild(i), out, seen);
        }
    }

    /** The zoom currently selected in the camera's zoom bar (e.g. 3.0 for "3x"), or -1. */
    private double currentZoomOnScreen() {
        for (AccessibilityNodeInfo root : roots()) {
            double v = findSelectedZoom(root);
            if (v > 0) {
                return v;
            }
        }
        return -1;
    }

    private double findSelectedZoom(AccessibilityNodeInfo node) {
        if (node == null) {
            return -1;
        }
        CharSequence t = node.getText();
        if (t != null && node.isVisibleToUser()) {
            String v = t.toString().trim().replace("\u00D7", "x").replace("X", "x").replace(" ", "");
            if (v.matches("[0-9]*\\.?[0-9]+x")) {
                try {
                    return Double.parseDouble(v.substring(0, v.length() - 1));
                } catch (NumberFormatException ignored) {
                    // not a zoom label
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            double r = findSelectedZoom(node.getChild(i));
            if (r > 0) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Troubleshooting: writes what the camera screen reports (every visible item with its
     * text, description, id and whether it can be tapped) to Downloads/zfn-zoom-report.txt
     * and copies it to the clipboard.
     */
    private void saveScreenReport() {
        StringBuilder sb = new StringBuilder("ZFN Zoom Clicker report\n");
        for (AccessibilityNodeInfo root : roots()) {
            sb.append("--- window: ").append(root.getPackageName()).append('\n');
            reportNode(root, 0, sb);
            if (sb.length() > 30000) {
                break;
            }
        }
        String text = sb.toString();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ZFN zoom report", text));
        } catch (Exception ignored) {
            // clipboard not available
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "zfn-zoom-report.txt");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.close();
                }
            }
        } catch (Exception ignored) {
            // could not save the file
        }
    }

    private void reportNode(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null || sb.length() > 30000) {
            return;
        }
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String id = node.getViewIdResourceName();
        if (node.isVisibleToUser() && (t != null || d != null || node.isClickable())) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            for (int i = 0; i < depth; i++) {
                sb.append(' ');
            }
            CharSequence cls = node.getClassName();
            String c = cls == null ? "" : cls.toString();
            sb.append(c.substring(c.lastIndexOf('.') + 1))
                    .append(" | text=").append(t)
                    .append(" | desc=").append(d)
                    .append(" | id=").append(id == null ? "" : id.substring(id.indexOf('/') + 1))
                    .append(node.isClickable() ? " | CLICK" : "")
                    .append(node.isSelected() ? " | SELECTED" : "")
                    .append(" | ").append(r.left).append(',').append(r.top)
                    .append(' ').append(r.width()).append('x').append(r.height())
                    .append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            reportNode(node.getChild(i), depth + 1, sb);
        }
    }

    /** Names of the zoom/lens buttons currently on screen, for troubleshooting. */
    private String describeZoomButtons() {
        List<String> names = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) {
            gatherNames(root, names);
        }
        String joined = names.isEmpty() ? "none" : String.join(" | ", names);
        return joined.length() > 400 ? joined.substring(0, 400) + "..." : joined;
    }

    private void gatherNames(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) {
            return;
        }
        for (CharSequence cs : new CharSequence[]{node.getContentDescription(), node.getText()}) {
            if (cs == null || !node.isVisibleToUser()) {
                continue;
            }
            String v = cs.toString().trim();
            String l = v.toLowerCase();
            boolean looksLikeZoom = l.contains("zoom") || l.contains("lens")
                    || v.matches("[0-9]+(\\.[0-9]+)?x?");
            if (looksLikeZoom && !out.contains(v)) {
                out.add(v + (node.isSelected() ? " [selected]" : ""));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            gatherNames(node.getChild(i), out);
        }
    }

    private boolean clickByLabel(String label) {
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> hits = new ArrayList<>();
            collect(root, label, hits);
            // 1st choice: a visible match that is itself a button
            for (AccessibilityNodeInfo h : hits) {
                if (h.isVisibleToUser() && h.isClickable() && !h.isSelected()
                        && h.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            // 2nd choice: the nearest SMALL clickable box around a visible match
            for (AccessibilityNodeInfo h : hits) {
                if (!h.isVisibleToUser()) {
                    continue;
                }
                AccessibilityNodeInfo t = h.getParent();
                while (t != null && !t.isClickable()) {
                    t = t.getParent();
                }
                if (t != null && isSmall(t) && !t.isSelected()
                        && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the box is button-sized, not a whole panel or the camera preview. */
    private boolean isSmall(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return r.width() < dm.widthPixels / 2 && r.height() < dm.heightPixels / 6;
    }

    /** Root of every camera window (worker thread only: these ask the camera app). */
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !isCameraWindow(w)) {
                    continue;
                }
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) {
                    list.add(r);
                }
            }
        } catch (Exception ignored) {
            // fall back to the active window below
        }
        if (list.isEmpty()) {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && active.getPackageName() != null
                    && CAMERA_PACKAGE.contentEquals(active.getPackageName())) {
                list.add(active);
            }
        }
        return list;
    }

    private void collect(AccessibilityNodeInfo node, String label, List<AccessibilityNodeInfo> out) {
        if (node == null) {
            return;
        }
        if (matches(node.getContentDescription(), label) || matches(node.getText(), label)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), label, out);
        }
    }

    private static boolean matches(CharSequence value, String label) {
        if (value == null) {
            return false;
        }
        String v = value.toString().trim();
        return v.equalsIgnoreCase(label) || v.toLowerCase().startsWith(label.toLowerCase() + ",");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        String p = pkg.toString();
        boolean camera = p.equals(CAMERA_PACKAGE);
        if (camera) {
            int id = event.getWindowId();
            if (id >= 0) {
                synchronized (cameraWindowIds) {
                    if (!cameraWindowIds.contains(id)) {
                        cameraWindowIds.add(id);
                        while (cameraWindowIds.size() > 20) {
                            cameraWindowIds.remove(0);
                        }
                    }
                }
            }
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence cls = event.getClassName();
        if (p.equals("com.android.systemui") || p.equals(getPackageName())
                || (cls != null && cls.toString().contains("Toast"))) {
            return;   // ignore pop-ups (volume bar, toasts, etc.)
        }
        if (camera) {
            if (!p.equals(lastForegroundPackage)) {
                nextStep = 0;   // camera just opened: start the zoom cycle from 3x again
                ZfnLog.add("camera opened");
            }
            scheduleShutterRefresh(700);   // re-check where the shutter is
        } else if (CAMERA_PACKAGE.equals(lastForegroundPackage)) {
            ZfnLog.add("left the camera (now: " + p + ")");
        }
        lastForegroundPackage = p;
    }

    @Override
    public void onInterrupt() {
    }
}
