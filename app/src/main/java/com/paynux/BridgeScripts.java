package com.paynux;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Skrip klien jembatan guest -> aplikasi (ditulis ke /usr/local di rootfs setiap
 * sesi dimulai). Berbicara dengan BridgeServer lewat localhost + token.
 */
final class BridgeScripts {
    private BridgeScripts() { }

    static final String LIB =
            "# paynux-bridge: klien ke aplikasi PayNux (localhost + token di ~/.paynux/bridge)\n"
            + "__pn_cfg=\"${HOME:-/root}/.paynux/bridge\"\n"
            + "\n"
            + "__pn_enc() {\n"
            + "    local LC_ALL=C s=\"$1\" out=\"\" i c\n"
            + "    for ((i = 0; i < ${#s}; i++)); do\n"
            + "        c=\"${s:i:1}\"\n"
            + "        case \"$c\" in\n"
            + "            [a-zA-Z0-9._~/-]) out+=\"$c\" ;;\n"
            + "            *) out+=\"$(printf '%%%02X' \"'$c\")\" ;;\n"
            + "        esac\n"
            + "    done\n"
            + "    printf '%s' \"$out\"\n"
            + "}\n"
            + "\n"
            + "paynux_call() {\n"
            + "    if [ ! -r \"$__pn_cfg\" ]; then\n"
            + "        echo \"paynux: bridge not available (open the PayNux app first)\" >&2\n"
            + "        return 2\n"
            + "    fi\n"
            + "    local port token line a reply msg\n"
            + "    { IFS= read -r port; IFS= read -r token; } < \"$__pn_cfg\"\n"
            + "    line=\"$token\"\n"
            + "    for a in \"$@\"; do line+=$'\\t'\"$(__pn_enc \"$a\")\"; done\n"
            + "    if ! exec 3<>\"/dev/tcp/127.0.0.1/$port\"; then\n"
            + "        echo \"paynux: cannot reach the app (is PayNux still running?)\" >&2\n"
            + "        return 2\n"
            + "    fi\n"
            + "    printf '%s\\n' \"$line\" >&3\n"
            + "    IFS= read -r -t 60 reply <&3\n"
            + "    exec 3<&- 3>&-\n"
            + "    case \"$reply\" in\n"
            + "        OK*) msg=\"${reply#OK}\"; printf '%s\\n' \"${msg# }\"; return 0 ;;\n"
            + "        ERR*) msg=\"${reply#ERR}\"; echo \"paynux: ${msg# }\" >&2; return 1 ;;\n"
            + "        *) echo \"paynux: no reply from app\" >&2; return 2 ;;\n"
            + "    esac\n"
            + "}\n";

    static final String NOTIFY =
            "#!/bin/bash\n"
            + "# Kirim notifikasi Android dari terminal.\n"
            + "# Pakai: paynux-notify [-t judul] teks...   atau   perintah | paynux-notify -t judul\n"
            + ". /usr/local/lib/paynux-bridge.sh\n"
            + "title=\"PayNux\"\n"
            + "while getopts \"t:\" o; do\n"
            + "    case \"$o\" in\n"
            + "        t) title=\"$OPTARG\" ;;\n"
            + "        *) echo \"usage: paynux-notify [-t title] text...\" >&2; exit 64 ;;\n"
            + "    esac\n"
            + "done\n"
            + "shift $((OPTIND - 1))\n"
            + "text=\"$*\"\n"
            + "if [ -z \"$text\" ] && [ ! -t 0 ]; then text=\"$(head -c 1000)\"; fi\n"
            + "[ -n \"$text\" ] || { echo \"usage: paynux-notify [-t title] text...\" >&2; exit 64; }\n"
            + "paynux_call notify \"$title\" \"$text\" >/dev/null\n";

    static final String INSTALL =
            "#!/bin/bash\n"
            + "# Pasang APK lewat installer Android.  Pakai: paynux-install file.apk\n"
            + ". /usr/local/lib/paynux-bridge.sh\n"
            + "[ $# -eq 1 ] || { echo \"usage: paynux-install file.apk\" >&2; exit 64; }\n"
            + "f=\"$(realpath -- \"$1\" 2>/dev/null)\"\n"
            + "[ -f \"$f\" ] || { echo \"paynux-install: not a file: $1\" >&2; exit 66; }\n"
            + "paynux_call install \"$f\"\n";

    static final String OPEN =
            "#!/bin/bash\n"
            + "# Buka tautan http(s) di browser Android.  Pakai: paynux-open https://...\n"
            + ". /usr/local/lib/paynux-bridge.sh\n"
            + "[ $# -eq 1 ] || { echo \"usage: paynux-open https://...\" >&2; exit 64; }\n"
            + "paynux_call open \"$1\" >/dev/null\n";

    static final String XDG_OPEN =
            "#!/bin/bash\n"
            + "# xdg-open minimal: alur login CLI agent (OAuth) membuka browser Android.\n"
            + ". /usr/local/lib/paynux-bridge.sh\n"
            + "case \"${1:-}\" in\n"
            + "    http://*|https://*) paynux_call open \"$1\" >/dev/null ;;\n"
            + "    *) echo \"xdg-open: only http(s) links are supported in PayNux\" >&2; exit 3 ;;\n"
            + "esac\n";

    static void write(File root) throws IOException {
        File lib = new File(root, "usr/local/lib");
        File bin = new File(root, "usr/local/bin");
        if (!lib.isDirectory() && !lib.mkdirs()) return;
        if (!bin.isDirectory() && !bin.mkdirs()) return;
        put(new File(lib, "paynux-bridge.sh"), LIB, false);
        put(new File(bin, "paynux-notify"), NOTIFY, true);
        put(new File(bin, "paynux-install"), INSTALL, true);
        put(new File(bin, "paynux-open"), OPEN, true);
        put(new File(bin, "xdg-open"), XDG_OPEN, true);
    }

    private static void put(File f, String text, boolean exec) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        f.setReadable(true, false);
        f.setExecutable(exec, false);
    }
}
