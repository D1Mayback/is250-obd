package com.d1mayback.is250obd;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shows every value the head unit's CAN service reports, and a log of changes, so we can work out
 * which channel is which by doing things in the car (open a door, press a wheel button, etc).
 */
public class LiveCanActivity extends Activity implements FytCan.Listener {

    private static final int MAX_LOG = 400;
    private static final long HIGHLIGHT_MS = 4000;

    private static final class Entry {
        String value;
        long changedAt;
        int changes;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Long, Entry> values = new TreeMap<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final StringBuilder fullLog = new StringBuilder();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView status, valuesView, logView;
    private FytCan can;
    private boolean paused;
    private boolean redrawQueued;
    private long connectedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(24, 12, 24, 12);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Live CAN  ");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title);
        bar.addView(button("Pause", b -> {
            paused = !paused;
            ((Button) b).setText(paused ? "Resume" : "Pause");
        }));
        bar.addView(button("Mark", b -> addLog("—————— MARK ——————")));
        bar.addView(button("Clear log", b -> { log.clear(); queueRedraw(); }));
        bar.addView(button("Save log", b -> save()));
        bar.addView(button("Reconnect", b -> reconnect()));
        bar.addView(button("Probe", b -> startActivity(new Intent(this, MainActivity.class))));
        root.addView(bar);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9FB3C8"));
        status.setTextSize(15);
        root.addView(status);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        logView = panel();
        valuesView = panel();
        cols.addView(wrap("Changes (newest first)", logView), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        cols.addView(wrap("All current values", valuesView), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        reconnect();
    }

    @Override
    protected void onDestroy() {
        if (can != null) can.close();
        super.onDestroy();
    }

    private void reconnect() {
        if (can != null) can.close();
        values.clear();
        connectedAt = SystemClock.elapsedRealtime();
        addLog("Connecting… (values arriving in the first seconds are the current snapshot)");
        can = new FytCan(this, this);
        can.connect();
    }

    // ------------------------------------------------------------ FytCan.Listener (binder threads)

    @Override
    public void onStatus(String text) {
        ui.post(() -> status.setText(text));
    }

    @Override
    public void onUpdate(int module, int field, int[] ints, float[] flts, String[] strs) {
        final String v = format(field, ints, flts, strs);
        final long now = SystemClock.elapsedRealtime();
        ui.post(() -> {
            long key = ((long) module << 32) | (field & 0xffffffffL);
            Entry e = values.get(key);
            boolean snapshot = now - connectedAt < 3000;
            if (e == null) {
                e = new Entry();
                e.value = v;
                e.changedAt = snapshot ? 0 : now;
                values.put(key, e);
                if (!snapshot) addLog(label(module, field) + "  new  " + v);
            } else if (!e.value.equals(v)) {
                String old = e.value;
                e.value = v;
                e.changedAt = now;
                e.changes++;
                if (!paused) addLog(label(module, field) + "  " + old + "  →  " + v);
            }
            queueRedraw();
        });
    }

    // ------------------------------------------------------------ UI helpers

    private void addLog(String line) {
        String stamped = clock.format(new Date()) + "  " + line;
        log.addFirst(stamped);
        while (log.size() > MAX_LOG) log.removeLast();
        fullLog.append(stamped).append('\n');
        queueRedraw();
    }

    private void queueRedraw() {
        if (redrawQueued) return;
        redrawQueued = true;
        ui.postDelayed(this::redraw, 250);
    }

    private void redraw() {
        redrawQueued = false;
        if (paused) return;
        StringBuilder sb = new StringBuilder();
        for (String l : log) sb.append(l).append('\n');
        logView.setText(sb);

        long now = SystemClock.elapsedRealtime();
        SpannableStringBuilder vs = new SpannableStringBuilder();
        for (Map.Entry<Long, Entry> me : values.entrySet()) {
            int module = (int) (me.getKey() >> 32);
            int field = (int) (me.getKey() & 0xffffffffL);
            Entry e = me.getValue();
            int start = vs.length();
            vs.append(label(module, field)).append("  ").append(e.value);
            if (e.changes > 0) vs.append("   (").append(String.valueOf(e.changes)).append("×)");
            if (e.changedAt > 0 && now - e.changedAt < HIGHLIGHT_MS) {
                vs.setSpan(new BackgroundColorSpan(Color.parseColor("#7A5C00")), start, vs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                vs.setSpan(new ForegroundColorSpan(Color.WHITE), start, vs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            vs.append('\n');
        }
        if (values.isEmpty()) vs.append("(no values yet)");
        valuesView.setText(vs);
        // Keep the highlight fading even if nothing new arrives
        ui.removeCallbacks(fade);
        ui.postDelayed(fade, 1000);
    }

    private final Runnable fade = this::queueRedraw;

    private static String label(int module, int field) {
        if (module == 7 && field == FytCan.PROFILE_FIELD) return "M7 #profile";
        return String.format(Locale.US, "M%d #%03d", module, field);
    }

    private static String format(int field, int[] ints, float[] flts, String[] strs) {
        StringBuilder sb = new StringBuilder();
        if (ints != null && ints.length > 0) {
            if (field == FytCan.PROFILE_FIELD && ints.length == 1) {
                sb.append(String.format(Locale.US, "0x%X", ints[0]));
            } else {
                sb.append(Arrays.toString(ints));
            }
        }
        if (flts != null && flts.length > 0) sb.append(" f").append(Arrays.toString(flts));
        if (strs != null && strs.length > 0) sb.append(" s").append(Arrays.toString(strs));
        return sb.length() == 0 ? "[]" : sb.toString().trim();
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 12, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView panel() {
        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor("#E6EDF3"));
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(15);
        t.setTextIsSelectable(false);
        return t;
    }

    private LinearLayout wrap(String heading, TextView content) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 8, 16, 0);
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextColor(Color.parseColor("#7CC4FF"));
        h.setTextSize(16);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(h);
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void save() {
        StringBuilder out = new StringBuilder("IS250 OBD live CAN log\n\n== Current values ==\n");
        for (Map.Entry<Long, Entry> me : values.entrySet()) {
            int module = (int) (me.getKey() >> 32);
            int field = (int) (me.getKey() & 0xffffffffL);
            out.append(label(module, field)).append("  ").append(me.getValue().value)
                    .append("  changes=").append(me.getValue().changes).append('\n');
        }
        out.append("\n== Change log (oldest first) ==\n").append(fullLog);
        String name = "is250-obd-can-" + System.currentTimeMillis() + ".txt";
        int saved = 0;
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (write(new File(dl, name), out.toString())) saved++;
        } catch (Exception ignored) { }
        File[] dirs = getExternalFilesDirs(null);
        if (dirs != null) for (File d : dirs) if (d != null && write(new File(d, name), out.toString())) saved++;
        Toast.makeText(this, saved > 0 ? "Saved " + name + " (Download folder)" : "Save failed", Toast.LENGTH_LONG).show();
    }

    private static boolean write(File f, String text) {
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
