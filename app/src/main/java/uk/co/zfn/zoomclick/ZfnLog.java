package uk.co.zfn.zoomclick;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Short in-memory activity log, shown on the app's screen so problems on site can be copied. */
final class ZfnLog {
    private static final int MAX = 80;
    private static final List<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.UK);

    private ZfnLog() {
    }

    static synchronized void add(String line) {
        LINES.add(0, TIME.format(new Date()) + "  " + line);
        while (LINES.size() > MAX) {
            LINES.remove(LINES.size() - 1);
        }
    }

    static synchronized String all() {
        if (LINES.isEmpty()) {
            return "(nothing yet: use the button in the camera, then come back here)";
        }
        return String.join("\n", LINES);
    }
}
