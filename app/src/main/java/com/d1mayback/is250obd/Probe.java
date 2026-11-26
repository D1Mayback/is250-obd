package com.d1mayback.is250obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Read-only probe of the head unit. It never writes to ports, settings or other apps.
 * Builds a text report: a short summary first, then full details.
 */
public class Probe {

    /** Package/property name fragments that usually belong to CAN box / MCU software on Chinese head units. */
    private static final String[] KEYWORDS = {
            "can", "mcu", "syu", "fyt", "obd", "car", "topway", "ts7", "ts10", "hct", "microntek",
            "mtc", "hiworld", "raise", "luzheng", "lz", "hz", "xy", "uis", "unisoc", "sprd",
            "vehicle", "canbus", "serial", "uart", "climate", "steer", "swc", "radio"
    };

    private final Context ctx;
    private final StringBuilder summary = new StringBuilder();
    private final StringBuilder details = new StringBuilder();

    public Probe(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public String run() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        summary.append("IS250 OBD probe report  ").append(stamp).append('\n');
        summary.append("========================================\n");

        section("Device", this::device);
        section("Root access", this::root);
        section("Serial / tty ports", this::serialPorts);
        section("Kernel tty drivers", () -> details.append(readFile("/proc/tty/drivers")));
        section("USB devices", this::usb);
        section("Bluetooth", this::bluetooth);
        section("CAN / MCU related apps", this::packages);
        section("Interesting system properties", this::props);
        section("System services (service list)", this::services);
        section("All installed packages", this::allPackages);

        return summary + "\n\n====== DETAILS ======\n" + details;
    }

    // ---------------------------------------------------------------- sections

    private void device() {
        line("Manufacturer", Build.MANUFACTURER);
        line("Brand", Build.BRAND);
        line("Model", Build.MODEL);
        line("Device", Build.DEVICE);
        line("Product", Build.PRODUCT);
        line("Board", Build.BOARD);
        line("Hardware", Build.HARDWARE);
        line("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line("Build", Build.DISPLAY);
        line("Fingerprint", Build.FINGERPRINT);
        line("ABIs", Arrays.toString(Build.SUPPORTED_ABIS));
        summary.append("Android ").append(Build.VERSION.RELEASE)
                .append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | hw=").append(Build.HARDWARE).append('\n');
    }

    private void root() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/app/Superuser.apk", "/data/adb/magisk"};
        List<String> found = new ArrayList<>();
        for (String p : paths) {
            if (new File(p).exists()) found.add(p);
        }
        line("su binaries found", found.isEmpty() ? "none" : found.toString());
        line("App runs as", sh("id").trim());
        line("ro.secure", prop("ro.secure"));
        line("ro.debuggable", prop("ro.debuggable"));
        line("ro.build.type", prop("ro.build.type"));
        line("ADB enabled (persist.sys.usb.config)", prop("persist.sys.usb.config"));
        summary.append("Root: ").append(found.isEmpty() ? "not found" : "possible " + found)
                .append(" | build type ").append(prop("ro.build.type")).append('\n');
    }

    private void serialPorts() {
        File dev = new File("/dev");
        String[] names = dev.list();
        List<String> readable = new ArrayList<>();
        List<String> writable = new ArrayList<>();
        int total = 0;
        if (names == null) {
            details.append("Cannot list /dev (permission denied). Using ls fallback below.\n");
        } else {
            Arrays.sort(names);
            for (String n : names) {
                String lower = n.toLowerCase(Locale.US);
                boolean interesting = lower.startsWith("tty") || lower.contains("can")
                        || lower.contains("mcu") || lower.contains("uart") || lower.contains("serial")
                        || lower.contains("obd") || lower.startsWith("rfcomm");
                if (!interesting) continue;
                // Skip the dozens of virtual consoles tty0..tty63 unless accessible
                File f = new File(dev, n);
                boolean r = f.canRead();
                boolean w = f.canWrite();
                total++;
                if (r) readable.add(n);
                if (w) writable.add(n);
                if (n.matches("tty\\d+") && !r && !w) continue;
                details.append(String.format(Locale.US, "  %-22s read=%-5s write=%s%n", "/dev/" + n, r, w));
            }
        }
        details.append("\nls -l output:\n");
        details.append(sh("ls -l /dev/tty* /dev/*can* /dev/*mcu* /dev/*uart* /dev/*serial* 2>&1 | grep -v 'tty[0-9]'"));
        details.append("\nnet interfaces (looking for can0 / slcan):\n");
        details.append(sh("ls /sys/class/net 2>&1"));

        summary.append("Serial-like ports: ").append(total)
                .append(" | readable by apps: ").append(readable.isEmpty() ? "none" : readable)
                .append(" | writable: ").append(writable.isEmpty() ? "none" : writable).append('\n');
    }

    private void usb() {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) {
            details.append("No USB host service\n");
            summary.append("USB devices: service unavailable\n");
            return;
        }
        int count = 0;
        for (UsbDevice d : um.getDeviceList().values()) {
            count++;
            details.append(String.format(Locale.US,
                    "  %s  vid=%04x pid=%04x class=%d  maker=%s  product=%s%n",
                    d.getDeviceName(), d.getVendorId(), d.getProductId(), d.getDeviceClass(),
                    safe(d.getManufacturerName()), safe(d.getProductName())));
        }
        if (count == 0) details.append("  (none attached)\n");
        summary.append("USB devices: ").append(count).append('\n');
    }

    private void bluetooth() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                details.append("No Bluetooth adapter visible to apps\n");
                summary.append("Bluetooth: none visible\n");
                return;
            }
            line("Adapter name", bt.getName());
            line("Enabled", String.valueOf(bt.isEnabled()));
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            int n = bonded == null ? 0 : bonded.size();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    details.append("  paired: ").append(safe(d.getName())).append("  ").append(d.getAddress()).append('\n');
                }
            }
            summary.append("Bluetooth: ").append(bt.isEnabled() ? "on" : "off")
                    .append(", paired devices ").append(n).append('\n');
        } catch (SecurityException e) {
            details.append("Bluetooth permission denied: ").append(e.getMessage()).append('\n');
            summary.append("Bluetooth: permission denied\n");
        }
    }

    private void packages() {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> all = pm.getInstalledPackages(0);
        List<String> hits = new ArrayList<>();
        for (PackageInfo pi : all) {
            if (matches(pi.packageName)) hits.add(pi.packageName);
        }
        java.util.Collections.sort(hits);
        int flags = PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES;
        for (String pkg : hits) {
            details.append("\n# ").append(pkg).append('\n');
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, flags);
                ApplicationInfo ai = pi.applicationInfo;
                details.append("  label=").append(safe(String.valueOf(pm.getApplicationLabel(ai))))
                        .append("  version=").append(safe(pi.versionName))
                        .append("  system=").append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                        .append("  uid=").append(ai.uid)
                        .append('\n');
                details.append("  apk=").append(ai.sourceDir).append('\n');
                if (pi.services != null) for (ServiceInfo s : pi.services) {
                    details.append("  service  ").append(s.name).append("  exported=").append(s.exported)
                            .append(s.permission != null ? "  perm=" + s.permission : "").append('\n');
                }
                if (pi.providers != null) for (ProviderInfo p : pi.providers) {
                    details.append("  provider ").append(p.name).append("  authority=").append(p.authority)
                            .append("  exported=").append(p.exported)
                            .append(p.readPermission != null ? "  readPerm=" + p.readPermission : "").append('\n');
                }
                if (pi.receivers != null) for (ActivityInfo r : pi.receivers) {
                    details.append("  receiver ").append(r.name).append("  exported=").append(r.exported).append('\n');
                }
                if (pi.activities != null) for (ActivityInfo a : pi.activities) {
                    details.append("  activity ").append(a.name).append('\n');
                }
            } catch (Exception e) {
                details.append("  (could not read: ").append(e.getClass().getSimpleName()).append(")\n");
            }
        }
        summary.append("CAN/MCU-looking apps (").append(hits.size()).append("): ");
        // Keep summary short: show the most specific hits first
        List<String> strong = new ArrayList<>();
        for (String h : hits) {
            String l = h.toLowerCase(Locale.US);
            if (l.contains("can") || l.contains("mcu") || l.contains("syu") || l.contains("fyt")
                    || l.contains("obd") || l.contains("microntek") || l.contains("hct")) strong.add(h);
        }
        summary.append(strong.isEmpty() ? hits.toString() : strong.toString()).append('\n');
    }

    private void props() {
        String all = sh("getprop");
        int n = 0;
        for (String l : all.split("\n")) {
            if (matches(l) || l.contains("ro.product") || l.contains("ro.build.display")
                    || l.contains("ro.board") || l.contains("ro.hardware")) {
                details.append(l).append('\n');
                n++;
            }
        }
        details.append("(").append(n).append(" matching properties)\n");
        details.append("\n--- full getprop ---\n").append(all);
    }

    private void services() {
        String out = sh("service list");
        details.append(out);
        List<String> hits = new ArrayList<>();
        for (String l : out.split("\n")) {
            String low = l.toLowerCase(Locale.US);
            if (low.contains("can") || low.contains("mcu") || low.contains("syu") || low.contains("fyt")
                    || low.contains("car") || low.contains("serial") || low.contains("obd")) {
                hits.add(l.trim());
            }
        }
        summary.append("CAN/MCU-looking system services: ").append(hits.isEmpty() ? "none" : hits.size())
                .append('\n');
        for (String h : hits) summary.append("   ").append(h).append('\n');
    }

    private void allPackages() {
        PackageManager pm = ctx.getPackageManager();
        List<String> names = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) names.add(pi.packageName);
        java.util.Collections.sort(names);
        for (String n : names) details.append("  ").append(n).append('\n');
    }

    // ---------------------------------------------------------------- helpers

    private interface Section { void run() throws Exception; }

    private void section(String title, Section s) {
        details.append("\n------ ").append(title).append(" ------\n");
        try {
            s.run();
        } catch (Throwable t) {
            details.append("ERROR: ").append(t).append('\n');
            summary.append(title).append(": error ").append(t.getClass().getSimpleName()).append('\n');
        }
    }

    private void line(String k, String v) {
        details.append("  ").append(k).append(": ").append(safe(v)).append('\n');
    }

    private static boolean matches(String s) {
        String l = s.toLowerCase(Locale.US);
        for (String k : KEYWORDS) {
            if (k.length() <= 2) {
                // short tokens only count as whole dot/underscore-separated parts
                for (String part : l.split("[._\\-:\\[\\]\\s]+")) if (part.equals(k)) return true;
            } else if (l.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) { return s == null ? "-" : s; }

    static String prop(String key) {
        return sh("getprop " + key).trim();
    }

    static String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy();
            return sb.toString();
        } catch (Exception e) {
            return "(failed: " + e + ")\n";
        }
    }

    private static String readFile(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        } catch (Exception e) {
            sb.append("(cannot read ").append(path).append(": ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }
}
