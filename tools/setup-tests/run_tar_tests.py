#!/usr/bin/env python3
"""Sandbox tests for TarExtractor. Usage: run_tar_tests.py <classpath> [rootfs.tar.xz]

Only creates and removes its own temp directories. Never touches project data.
"""
import hashlib, io, os, shutil, stat, subprocess, sys, tarfile, tempfile, gzip

CP = sys.argv[1]
REAL_XZ = sys.argv[2] if len(sys.argv) > 2 else None
fails = 0
tmp_root = tempfile.mkdtemp(prefix="tartest_")


def check(name, ok, extra=""):
    global fails
    print(("PASS " if ok else "FAIL ") + name + (" " + extra if (extra and not ok) else ""))
    if not ok:
        fails += 1


def extract(gz_path, dest):
    p = subprocess.run(["java", "-cp", CP, "ExtractCli", gz_path, dest],
                       capture_output=True, text=True, timeout=240)
    return p.returncode, p.stdout.strip()


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def build(name, fmt, entries, gz=True):
    """entries: list of (TarInfo, bytes|None). Returns path of .tar.gz."""
    path = os.path.join(tmp_root, name + ".tar.gz")
    with gzip.open(path, "wb") as g:
        with tarfile.open(fileobj=g, mode="w", format=fmt) as t:
            for ti, data in entries:
                t.addfile(ti, io.BytesIO(data) if data is not None else None)
    return path


def ti(name, type_=tarfile.REGTYPE, mode=0o644, size=0, link=""):
    i = tarfile.TarInfo(name)
    i.type, i.mode, i.size, i.linkname = type_, mode, size, link
    return i


def reg(name, data, mode=0o644):
    return (ti(name, mode=mode, size=len(data)), data)


def mode_of(p):
    return stat.S_IMODE(os.lstat(p).st_mode)


# ---------- A. real rootfs repacked as .tar.gz ----------
if REAL_XZ:
    gz = os.path.join(tmp_root, "real.tar.gz")
    with open(gz, "wb") as out:
        x = subprocess.Popen(["xz", "-dc", REAL_XZ], stdout=subprocess.PIPE)
        z = subprocess.Popen(["gzip", "-1"], stdin=x.stdout, stdout=out)
        z.wait()
    dest = os.path.join(tmp_root, "real_out")
    rc, out = extract(gz, dest)
    check("real rootfs extracts", rc == 0, out)
    print("   ", out)
    bad = []
    n = 0
    with tarfile.open(gz, "r:gz") as t:
        for m in t:
            p = os.path.join(dest, m.name.lstrip("./"))
            n += 1
            if m.isdir():
                if not os.path.isdir(p) or mode_of(p) != ((m.mode & 0o7777) | 0o700):
                    bad.append(("dir", m.name, oct(m.mode), oct(mode_of(p)) if os.path.lexists(p) else None))
            elif m.issym():
                if not os.path.islink(p) or os.readlink(p) != m.linkname:
                    bad.append(("sym", m.name, m.linkname))
            elif m.isreg():
                f = t.extractfile(m)
                h = hashlib.sha256(f.read()).hexdigest()
                if (not os.path.isfile(p) or os.path.islink(p) or sha(p) != h
                        or mode_of(p) != ((m.mode & 0o7777) | 0o400)):
                    bad.append(("file", m.name, oct(m.mode), oct(mode_of(p)) if os.path.lexists(p) else None))
    check("real rootfs: every entry type/target/content/mode matches (%d entries)" % n,
          not bad, str(bad[:5]))
    check("real rootfs: /tmp is 1777 (sticky kept)", mode_of(os.path.join(dest, "ubuntu/rootfs/tmp")) == 0o1777)
    check("real rootfs: passwd keeps setuid 4755",
          mode_of(os.path.join(dest, "ubuntu/rootfs/usr/bin/passwd")) == 0o4755)
    # Paths on disk must equal archive members, plus implicit parent dirs the archive never listed.
    names = set()
    with tarfile.open(gz, "r:gz") as t:
        for m in t:
            names.add(m.name.strip("/").lstrip("./").rstrip("/"))
    ancestors = set()
    for nme in names:
        parts = nme.split("/")
        for i in range(1, len(parts)):
            ancestors.add("/".join(parts[:i]))
    disk = set()
    for r, ds, fs_ in os.walk(dest):
        for x in ds + fs_:
            disk.add(os.path.relpath(os.path.join(r, x), dest))
    extra = disk - names - ancestors
    missing = names - disk
    check("real rootfs: no unexpected/missing paths (implicit parents allowed)",
          not extra and not missing, "extra=%s missing=%s" % (sorted(extra)[:3], sorted(missing)[:3]))

# ---------- B. synthetic edge cases ----------
# B1 PAX long + unicode names
longdir = "/".join(["d%02d_" % i + "x" * 20 for i in range(10)])  # >200 chars
uni = "caf\u00e9_\u65e5\u672c.txt"
for fmt, label in ((tarfile.PAX_FORMAT, "pax"), (tarfile.GNU_FORMAT, "gnu")):
    arc = build("long_" + label, fmt, [
        (ti("./", tarfile.DIRTYPE, 0o755), None),
        (ti("./" + longdir, tarfile.DIRTYPE, 0o755), None),
        reg("./" + longdir + "/" + uni, b"hello-long"),
        (ti("./lnk", tarfile.SYMTYPE, 0o777, link="/" + longdir + "/" + uni + "_target_" + "y" * 120), None),
    ])
    d = os.path.join(tmp_root, "o_long_" + label)
    rc, out = extract(arc, d)
    p = os.path.join(d, longdir, uni)
    check("%s: long path + unicode name" % label, rc == 0 and os.path.isfile(p) and open(p, "rb").read() == b"hello-long", out)
    check("%s: long symlink target preserved" % label,
          os.path.islink(os.path.join(d, "lnk")) and os.readlink(os.path.join(d, "lnk")).endswith("y" * 120))

# B2 modes, deferred dir chmod, hardlink, devices, duplicates
arc = build("modes", tarfile.PAX_FORMAT, [
    (ti("ro_dir", tarfile.DIRTYPE, 0o555), None),
    reg("ro_dir/child.txt", b"child", 0o644),
    (ti("sticky", tarfile.DIRTYPE, 0o1777), None),
    reg("suid", b"#!x", 0o4755),
    reg("sgid", b"#!x", 0o2755),
    reg("zero", b"z", 0o000),
    reg("orig", b"original-data", 0o755),
    (ti("hard", tarfile.LNKTYPE, 0o755, link="orig"), None),
    (ti("dev", tarfile.CHRTYPE, 0o666), None),
    (ti("fifo", tarfile.FIFOTYPE, 0o666), None),
    reg("dup", b"first"),
    reg("dup", b"second-wins"),
    (ti("rel/../ok_but_dotdot_free", tarfile.REGTYPE, 0o644, 0), b"") if False else reg("./dot/./slash.txt", b"dots"),
])
d = os.path.join(tmp_root, "o_modes")
rc, out = extract(arc, d)
check("modes archive extracts", rc == 0, out)
check("read-only dir keeps child (deferred chmod); dir mode 0555|0700 = 0755",
      os.path.isfile(os.path.join(d, "ro_dir/child.txt")) and mode_of(os.path.join(d, "ro_dir")) == 0o755)
check("sticky dir 1777", mode_of(os.path.join(d, "sticky")) == 0o1777)
check("setuid 4755 kept", mode_of(os.path.join(d, "suid")) == 0o4755)
check("setgid 2755 kept (may be dropped by kernel if group differs)",
      mode_of(os.path.join(d, "sgid")) in (0o2755, 0o755))
check("mode 000 file gets owner-read forced (0400)", mode_of(os.path.join(d, "zero")) == 0o400)
check("hardlink shares content", open(os.path.join(d, "hard"), "rb").read() == b"original-data")
check("device/fifo skipped, not created",
      not os.path.lexists(os.path.join(d, "dev")) and not os.path.lexists(os.path.join(d, "fifo")))
check("duplicate entry: later wins", open(os.path.join(d, "dup"), "rb").read() == b"second-wins")
check("./dot/./slash.txt normalised", os.path.isfile(os.path.join(d, "dot/slash.txt")))

# B3 security
outside = os.path.join(tmp_root, "outside")
os.makedirs(outside)
cases = {
    "traversal": [reg("../evil_trav", b"x")],
    "traversal_mid": [reg("a/../../evil_mid", b"x")],
    "absolute": [reg("/tmp/abs_evil_xyz", b"x")],
    "symlink_escape": [(ti("esc", tarfile.SYMTYPE, 0o777, link=outside), None), reg("esc/pwn", b"x")],
    "symlink_escape_dir": [(ti("esc2", tarfile.SYMTYPE, 0o777, link=outside), None),
                           (ti("esc2/sub", tarfile.DIRTYPE, 0o755), None)],
}
for name, entries in cases.items():
    arc = build("sec_" + name, tarfile.PAX_FORMAT, entries)
    d = os.path.join(tmp_root, "o_sec_" + name)
    rc, out = extract(arc, d)
    check("security/%s rejected" % name, rc != 0 and out.startswith("ERROR"), out)
check("nothing written outside dest",
      not os.path.exists(os.path.join(tmp_root, "evil_trav")) and not os.path.exists(os.path.join(tmp_root, "evil_mid"))
      and not os.path.exists("/tmp/abs_evil_xyz") and os.listdir(outside) == [])

# dangling symlink pointing outside, then a regular file with the same name: must replace the link, not write through it
dangling_target = os.path.join(tmp_root, "outside_dangling_target")
arc = build("sec_dangling", tarfile.PAX_FORMAT, [
    (ti("dl", tarfile.SYMTYPE, 0o777, link=dangling_target), None), reg("dl", b"inside-data")])
d = os.path.join(tmp_root, "o_sec_dangling")
rc, out = extract(arc, d)
p_ = os.path.join(d, "dl")
check("dangling symlink replaced by regular file, nothing written outside",
      rc == 0 and os.path.isfile(p_) and not os.path.islink(p_) and open(p_, "rb").read() == b"inside-data"
      and not os.path.exists(dangling_target), out)
arc = build("sec_dangling_dir", tarfile.PAX_FORMAT, [
    (ti("dd", tarfile.SYMTYPE, 0o777, link=dangling_target + "_dir"), None), reg("dd/child", b"x")])
rc, out = extract(arc, os.path.join(tmp_root, "o_sec_dangling_dir"))
check("write through dangling symlinked dir rejected", rc != 0 and not os.path.exists(dangling_target + "_dir"), out)

# B4 corruption
good = build("good", tarfile.PAX_FORMAT, [reg("f1", b"A" * 5000), reg("f2", b"B" * 5000)])
raw = gzip.open(good, "rb").read()
def gz_bytes(b, p):
    with gzip.open(p, "wb") as g: g.write(b)
    return p
bad_ck = bytearray(raw); bad_ck[10] ^= 0xFF  # corrupt name byte of first header -> checksum fails
rc, out = extract(gz_bytes(bytes(bad_ck), os.path.join(tmp_root, "badck.tar.gz")), os.path.join(tmp_root, "o_badck"))
check("bad header checksum rejected", rc != 0, out)
rc, out = extract(gz_bytes(raw[:1024 + 2000], os.path.join(tmp_root, "trunc_tar.tar.gz")), os.path.join(tmp_root, "o_trunc1"))
check("truncated tar data rejected", rc != 0, out)
cut = open(good, "rb").read()
open(os.path.join(tmp_root, "trunc_gz.tar.gz"), "wb").write(cut[:len(cut) // 2])
rc, out = extract(os.path.join(tmp_root, "trunc_gz.tar.gz"), os.path.join(tmp_root, "o_trunc2"))
check("truncated gzip rejected", rc != 0, out)
rc, out = extract(gz_bytes(b"", os.path.join(tmp_root, "empty.tar.gz")), os.path.join(tmp_root, "o_empty"))
check("empty archive: no crash (OK with zero entries)", rc == 0 and "files=0" in out, out)

shutil.rmtree(tmp_root, ignore_errors=True)
print("ALL TAR TESTS PASSED" if fails == 0 else "%d TAR TESTS FAILED" % fails)
sys.exit(0 if fails == 0 else 1)
