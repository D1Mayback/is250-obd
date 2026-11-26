package com.d1mayback.is250obd;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView output;
    private TextView status;
    private String report = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(24, 16, 24, 16);

        TextView title = new TextView(this);
        title.setText("IS250 OBD  ·  head unit probe  v" + versionName());
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(button("Run probe", v -> runProbe()));
        buttons.addView(button("Save report", v -> save()));
        buttons.addView(button("Share", v -> share()));
        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9FB3C8"));
        status.setTextSize(16);
        status.setPadding(24, 0, 0, 0);
        buttons.addView(status);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextColor(Color.parseColor("#E6EDF3"));
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(15);
        output.setTextIsSelectable(true);
        output.setText("Tap \"Run probe\". It only reads information; it changes nothing on the unit.");
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        runProbe();
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 16, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private void runProbe() {
        status.setText("Probing…");
        new Thread(() -> {
            String r;
            try {
                r = new Probe(this).run();
            } catch (Throwable t) {
                r = "Probe crashed: " + t;
            }
            final String result = r;
            ui.post(() -> {
                report = result;
                output.setText(result);
                status.setText("Done. Photograph the summary at the top, or tap Save report.");
            });
        }).start();
    }

    private void save() {
        if (report.isEmpty()) return;
        String name = "is250-obd-report-" + System.currentTimeMillis() + ".txt";
        List<String> saved = new ArrayList<>();

        // Public Download folder (Android 8.1 allows this with storage permission)
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (write(new File(dl, name))) saved.add(dl.getAbsolutePath());
        } catch (Exception ignored) { }

        // App folders on every storage volume, including a USB stick if one is plugged in
        File[] dirs = getExternalFilesDirs(null);
        if (dirs != null) for (File d : dirs) {
            if (d != null && write(new File(d, name))) saved.add(d.getAbsolutePath());
        }

        String msg = saved.isEmpty() ? "Could not save the report anywhere"
                : "Saved " + name + " to:\n" + String.join("\n", saved);
        status.setText(saved.isEmpty() ? "Save failed" : "Saved (" + saved.size() + " places)");
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        output.setText(msg + "\n\n" + report);
    }

    private boolean write(File f) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void share() {
        if (report.isEmpty()) return;
        String text = report.length() > 90_000 ? report.substring(0, 90_000) + "\n…(truncated)" : report;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "IS250 OBD probe report");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "Share report"));
        } catch (Exception e) {
            Toast.makeText(this, "No app on this unit can share text", Toast.LENGTH_LONG).show();
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
