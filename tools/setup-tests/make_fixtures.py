#!/usr/bin/env python3
"""Builds tiny Ubuntu-like .tar.gz fixtures for SetupManagerTest. Usage: make_fixtures.py <outdir>"""
import gzip, hashlib, io, os, sys, tarfile

out = sys.argv[1]
os.makedirs(out, exist_ok=True)


def ti(name, type_=tarfile.REGTYPE, mode=0o644, size=0, link=""):
    i = tarfile.TarInfo(name)
    i.type, i.mode, i.size, i.linkname = type_, mode, size, link
    return i


def build(path, entries):
    with gzip.open(path, "wb") as g:
        with tarfile.open(fileobj=g, mode="w", format=tarfile.GNU_FORMAT) as t:
            for i, data in entries:
                t.addfile(i, io.BytesIO(data) if data is not None else None)
    with open(path, "rb") as f:
        open(path.replace(".tar.gz", ".sha256"), "w").write(hashlib.sha256(f.read()).hexdigest())


osrel = b'PRETTY_NAME="Ubuntu 24.04.5 LTS"\nVERSION_ID="24.04"\nID=ubuntu\n'
bash = b"#!/bin/sh\necho fake bash\n"
build(os.path.join(out, "good.tar.gz"), [
    (ti("./", tarfile.DIRTYPE, 0o755), None),
    (ti("./usr", tarfile.DIRTYPE, 0o755), None),
    (ti("./usr/bin", tarfile.DIRTYPE, 0o755), None),
    (ti("./usr/bin/bash", mode=0o755, size=len(bash)), bash),
    (ti("./bin", tarfile.SYMTYPE, 0o777, link="usr/bin"), None),
    (ti("./etc", tarfile.DIRTYPE, 0o755), None),
    (ti("./etc/os-release", mode=0o644, size=len(osrel)), osrel),
    (ti("./etc/resolv.conf", tarfile.SYMTYPE, 0o777, link="../run/systemd/resolve/stub-resolv.conf"), None),
    (ti("./tmp", tarfile.DIRTYPE, 0o1777), None),
])
build(os.path.join(out, "badlayout.tar.gz"), [
    (ti("./etc", tarfile.DIRTYPE, 0o755), None),
    (ti("./etc/os-release", mode=0o644, size=len(osrel)), osrel),
])
# ---- offline (bundled-style) fixtures: ubuntu/manifest.json + ubuntu/rootfs/... in a .tar.xz ----
import lzma


def build_xz(path, entries):
    with tarfile.open(path, "w:xz", format=tarfile.PAX_FORMAT) as t:
        for i, data in entries:
            t.addfile(i, io.BytesIO(data) if data is not None else None)


pw = b"passwd-binary"
build_xz(os.path.join(out, "good_offline.tar.xz"), [
    (ti("ubuntu/manifest.json", mode=0o644, size=2), b"{}"),
    (ti("ubuntu/rootfs", tarfile.DIRTYPE, 0o755), None),
    (ti("ubuntu/rootfs/usr", tarfile.DIRTYPE, 0o755), None),
    (ti("ubuntu/rootfs/usr/bin", tarfile.DIRTYPE, 0o755), None),
    (ti("ubuntu/rootfs/usr/bin/bash", mode=0o755, size=len(bash)), bash),
    (ti("ubuntu/rootfs/usr/bin/passwd", mode=0o4755, size=len(pw)), pw),
    (ti("ubuntu/rootfs/bin", tarfile.SYMTYPE, 0o777, link="usr/bin"), None),
    (ti("ubuntu/rootfs/etc", tarfile.DIRTYPE, 0o755), None),
    (ti("ubuntu/rootfs/etc/os-release", mode=0o644, size=len(osrel)), osrel),
    (ti("ubuntu/rootfs/etc/resolv.conf", tarfile.SYMTYPE, 0o777, link="../run/systemd/resolve/stub-resolv.conf"), None),
    (ti("ubuntu/rootfs/tmp", tarfile.DIRTYPE, 0o1777), None),
])
build_xz(os.path.join(out, "badlayout_offline.tar.xz"), [
    (ti("ubuntu/rootfs/etc", tarfile.DIRTYPE, 0o755), None),
    (ti("ubuntu/rootfs/etc/os-release", mode=0o644, size=len(osrel)), osrel),
])
print("fixtures in", out)
