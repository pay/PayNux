import os, pty, select, time, re, sys
HOOK = sys.argv[1] if len(sys.argv) > 1 else "/tmp/hook.sh"

def session(cmds, env_extra=None, preamble=""):
    pid, fd = pty.fork()
    if pid == 0:
        env = dict(os.environ, TERM="xterm-256color", PS1="$ ", HOME="/tmp", HISTFILE="/tmp/.hist_test", INPUTRC="/dev/null")
        if env_extra: env.update(env_extra)
        os.execvpe("bash", ["bash", "--norc", "--noprofile", "-i"], env)
    out = b""
    def pump(t):
        nonlocal out
        end = time.time() + t
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], 0.05)
            if r:
                try: d = os.read(fd, 65536)
                except OSError: return
                if not d: return
                out += d
    pump(0.6)
    if preamble:
        os.write(fd, preamble.encode() + b"\r"); pump(0.4)
    os.write(fd, ("source " + HOOK + "\r").encode()); pump(0.5)
    for c, wait in cmds:
        os.write(fd, c.encode() + b"\r"); pump(wait)
    os.write(fd, b"exit\r"); pump(0.3)
    try: os.waitpid(pid, 0)
    except Exception: pass
    return out

OSC = re.compile(rb"\x1b\]777;paynux-done;(\d+);(\d+);([^\x07]*)\x07")
fails = 0
def check(name, ok, extra=""):
    global fails
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + extra)); fails += (not ok)

e = {"PAYNUX_NOTIFY_AFTER": "1"}
o = session([("sleep 2", 3.0)], e)
m = OSC.findall(o)
check("long command (2s, threshold 1) emits one report", len(m) == 1, repr(o[-200:]))
check("report: exit 0, duration >= 2 (SECONDS granularity), command 'sleep 2'", len(m) == 1 and m[0][0] == b"0" and int(m[0][1]) >= 1 and m[0][2] == b"sleep 2", str(m))

o = session([("true", 0.5), ("echo hi", 0.5), ("", 0.4)], e)
check("fast commands and empty Enter emit nothing", len(OSC.findall(o)) == 0, repr(o[-200:]))

o = session([("bash -c 'sleep 2; exit 7'", 3.0)], e)
m = OSC.findall(o)
check("failing command keeps its exit code (7), not clobbered by the prompt hooks", len(m) == 1 and m[0][0] == b"7", str(m))

o = session([("sleep 2", 3.0)], {"PAYNUX_NOTIFY_AFTER": "0"})
check("PAYNUX_NOTIFY_AFTER=0 disables it", len(OSC.findall(o)) == 0)

o = session([("sleep 2", 3.0)], {"PAYNUX_NOTIFY_AFTER": "5"})
check("below the threshold (2s < 5s) emits nothing", len(OSC.findall(o)) == 0)

o = session([("sleep 2", 3.0)], {"PAYNUX_NOTIFY_AFTER": "abc"})
check("garbage threshold does not break the shell or emit", len(OSC.findall(o)) == 0 and b"$ " in o)

# existing PROMPT_COMMAND (incl. history -a, like /etc/paynux.sh) is preserved and our hook runs first
o = session([("bash -c 'sleep 2; exit 5'", 3.0), ("echo after", 0.5)], e, preamble="PROMPT_COMMAND='history -a; echo PCMARK'")
m = OSC.findall(o)
check("pre-existing PROMPT_COMMAND still runs", o.count(b"PCMARK") >= 2, repr(o[-200:]))
check("rc survives a pre-existing 'history -a' (hook runs first)", len(m) == 1 and m[0][0] == b"5", str(m))

# nothing visible is added by PS0 and no stray text appears when the report is not sent
o = session([("echo visible", 0.5)], e)
import re as _re
clean = _re.sub(rb"\x1b\[\?2004[hl]", b"", o)
check("PS0 adds no visible text", b"echo visible\r\n\rvisible\r\n" in clean or b"echo visible\r\nvisible\r\n" in clean, repr(clean[-160:]))

# sanitising: ';' and control chars never reach the OSC fields
o = session([("echo 'a;b'; sleep 2", 3.0)], e)
m = OSC.findall(o)
check("';' removed from the command field (one clean report, 3 fields)", len(m) == 1 and b";" not in m[0][2], str(m))
o = session([("sleep 2 # " + "x" * 80, 3.0)], e)
m = OSC.findall(o)
check("command text truncated to 40 characters", len(m) == 1 and len(m[0][2]) <= 40, str(m))
print("ALL HOOK TESTS PASSED" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
