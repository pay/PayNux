package com.paynux.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text for the About dialog. */
public final class AboutInfo {
    private AboutInfo() {}

    /** PRETTY_NAME from /etc/os-release, or null. */
    public static String prettyName(String osRelease) {
        if (osRelease == null) return null;
        Matcher m = Pattern.compile("(?m)^PRETTY_NAME=(?:\"([^\"]*)\"|'([^']*)'|(\\S+))\\s*$").matcher(osRelease);
        if (!m.find()) return null;
        String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    public static String format(String versionName, int versionCode, String distro,
                                String androidRelease, int sdkInt, String abi) {
        StringBuilder sb = new StringBuilder();
        sb.append("PayNux\nLinux On Your Pocket\n\n");
        sb.append("Version ").append(versionName == null ? "?" : versionName)
                .append(" (").append(versionCode).append(")\n");
        sb.append(distro == null ? "Ubuntu: not installed" : distro + " (PRoot)").append('\n');
        sb.append("Android ").append(androidRelease == null ? "?" : androidRelease)
                .append(" (API ").append(sdkInt).append(")");
        if (abi != null && !abi.isEmpty()) sb.append(" \u00b7 ").append(abi);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Device information (neofetch style), shown live in the About dialog
    // ------------------------------------------------------------------

    /** Raw values collected by the app. Anything unknown keeps its default and is shown as "-". */
    public static final class Data {
        public String appVersionName;
        public int appVersionCode;
        public String distro;
        public String manufacturer, model;
        public String androidRelease;
        public int sdkInt;
        public String securityPatch;
        public String kernel;
        public int cpuCores;
        public String abi;
        public long ramTotalBytes = -1, ramAvailBytes = -1;
        public long storageTotalBytes = -1, storageFreeBytes = -1;
        public int screenW, screenH, dpi;
        public int batteryPercent = -1;
        public Boolean charging;
        public long uptimeMs = -1;
        public int termRows, termCols;
    }

    public static final class Row {
        public enum Kind { TITLE, SUBTITLE, SECTION, KV, BLANK }

        public final Kind kind;
        public final String label;
        public final String value;

        Row(Kind kind, String label, String value) {
            this.kind = kind;
            this.label = label;
            this.value = value;
        }
    }

    public static List<Row> rows(Data d) {
        List<Row> r = new ArrayList<Row>();
        r.add(new Row(Row.Kind.TITLE, "", "PayNux"));
        r.add(new Row(Row.Kind.SUBTITLE, "", "Linux On Your Pocket"));
        r.add(new Row(Row.Kind.BLANK, "", ""));
        r.add(new Row(Row.Kind.SECTION, "", "VERSION"));
        r.add(kv("App", d.appVersionName == null ? null : d.appVersionName + " (" + d.appVersionCode + ")"));
        r.add(kv("Ubuntu", d.distro == null ? "not installed" : d.distro + " (PRoot)"));
        r.add(new Row(Row.Kind.BLANK, "", ""));
        r.add(new Row(Row.Kind.SECTION, "", "DEVICE"));
        r.add(kv("Model", model(d.manufacturer, d.model)));
        r.add(kv("Android", d.androidRelease == null ? null : d.androidRelease + " (API " + d.sdkInt + ")"));
        r.add(kv("Patch", d.securityPatch));
        r.add(kv("Kernel", d.kernel));
        r.add(kv("CPU", d.cpuCores <= 0 ? null
                : d.cpuCores + " cores" + (d.abi == null || d.abi.isEmpty() ? "" : " \u00b7 " + d.abi)));
        r.add(kv("RAM", used(d.ramTotalBytes, d.ramAvailBytes)));
        r.add(kv("Storage", used(d.storageTotalBytes, d.storageFreeBytes)));
        r.add(kv("Screen", d.screenW <= 0 ? null : d.screenW + "\u00d7" + d.screenH + " \u00b7 " + d.dpi + " dpi"));
        r.add(kv("Battery", battery(d.batteryPercent, d.charging)));
        r.add(kv("Uptime", uptime(d.uptimeMs)));
        r.add(new Row(Row.Kind.BLANK, "", ""));
        r.add(new Row(Row.Kind.SECTION, "", "TERMINAL"));
        r.add(kv("Size", d.termCols <= 0 ? null : d.termCols + "\u00d7" + d.termRows));
        return r;
    }

    private static Row kv(String label, String value) {
        return new Row(Row.Kind.KV, label, value == null || value.isEmpty() ? "-" : value);
    }

    /** "samsung SM-S918B"; the manufacturer is not repeated when the model already starts with it. */
    static String model(String manufacturer, String model) {
        if (model == null || model.isEmpty()) return manufacturer;
        if (manufacturer == null || manufacturer.isEmpty()) return model;
        return model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))
                ? model : manufacturer + " " + model;
    }

    /** 1024-based, one decimal: "10.4 GB", "512.0 MB". */
    public static String bytes(long b) {
        if (b < 0) return "-";
        double v = b;
        String[] unit = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < unit.length - 1) { v /= 1024; i++; }
        return i == 0 ? ((long) v) + " B" : String.format(Locale.US, "%.1f %s", v, unit[i]);
    }

    /** "4.2 / 10.3 GB used" from total and free; null when unknown. */
    public static String used(long total, long free) {
        if (total <= 0 || free < 0 || free > total) return null;
        String t = bytes(total);
        String[] tp = t.split(" ");
        String u = bytes(total - free);
        String[] up = u.split(" ");
        // same unit on both sides when possible: "4.2 / 10.3 GB used"
        return up[1].equals(tp[1]) ? up[0] + " / " + t + " used" : u + " / " + t + " used";
    }

    public static String battery(int percent, Boolean charging) {
        if (percent < 0) return null;
        return percent + "%" + (charging == null ? "" : charging.booleanValue() ? " \u00b7 charging" : "");
    }

    /** "3d 1h 55m", "1h 05m", "12m 03s", "<1m". */
    public static String uptime(long ms) {
        if (ms < 0) return null;
        long s = ms / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h " + String.format(Locale.US, "%02d", m) + "m";
        if (h > 0) return h + "h " + String.format(Locale.US, "%02d", m) + "m";
        if (m > 0) return m + "m " + String.format(Locale.US, "%02d", sec) + "s";
        return "<1m";
    }

    /** Width of the label column: longest label + 2 spaces. */
    public static int labelWidth(List<Row> rows) {
        int w = 0;
        for (Row r : rows) if (r.kind == Row.Kind.KV) w = Math.max(w, r.label.length());
        return w + 2;
    }

    /** Plain aligned text (also used by the tests). */
    public static String toPlainText(List<Row> rows) {
        int w = labelWidth(rows);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.kind == Row.Kind.KV) {
                sb.append("  ").append(r.label);
                for (int k = r.label.length(); k < w; k++) sb.append(' ');
                sb.append(r.value);
            } else {
                sb.append(r.value);
            }
            if (i < rows.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
