"""Tests for tools/rewrite_tar.py: Winlator's programs SAI does not use, and
Winlator's winhandler.exe (ours replaces it), are left out by their place in
the archive, and nothing next to them is.

    python3 -I tests/test_rewrite_tar.py

Run by ./build.sh with the other tests that need no Winlator. The archive is
built here in memory, with the paths written as Winlator's rootfs_patches.tzst
writes them ("./opt/apps/..."), so no Winlator checkout is needed.
"""
import io
import os
import subprocess
import sys
import tarfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "..", "tools", "rewrite_tar.py")

OLD, NEW = "/data/data/com.winlator/", "/data/data/sai.penpress/"

# the ten files, as Winlator 11.2 @ ecd5ab2 has them in rootfs_patches.tzst
APPS = [
    "./opt/apps/winaddons.exe",
    "./opt/apps/7-Zip/7zFM.exe",
    "./opt/apps/7-Zip/7z.dll",
    "./opt/apps/7-Zip/7zG.exe",
    "./opt/apps/TestD3D.exe",
    "./opt/apps/GPUInfo.exe",
    "./home/xuser/.wine/drive_c/windows/wfm.exe",
    "./home/xuser/.wine/drive_c/windows/libcdio.dll",
    "./usr/local/bin/winpreexec",
    "./home/xuser/.wine/drive_c/windows/winhandler.exe",
]
# what sits next to them and must stay; a file Winlator might add to
# opt/apps later is not on the list (the build's count of 10 is what catches
# a change there)
KEEP = [
    "./home/xuser/.wine/drive_c/windows/notepad.exe",
    "./home/xuser/.wine/drive_c/windows/winhandler.exe.bak",
    "./opt/apps/something-new.exe",
    "./usr/local/bin/winpreexec.conf",
    "./opt/apps/7-Zip/../TestD3D.txt",
]
DIRS = ["./opt/", "./opt/apps/", "./opt/apps/7-Zip/", "./usr/local/bin/"]


def make_tar(names, dirs=(), body=b"MZ plain program, path " + OLD.encode()):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as t:
        for d in dirs:
            m = tarfile.TarInfo(d)
            m.type = tarfile.DIRTYPE
            m.mode = 0o755
            t.addfile(m)
        for n in names:
            m = tarfile.TarInfo(n)
            m.size = len(body)
            m.mode = 0o644
            t.addfile(m, io.BytesIO(body))
    return buf.getvalue()


def run(args, data):
    p = subprocess.run([sys.executable, "-I", TOOL] + args, input=data,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise AssertionError("rewrite_tar.py failed: " + p.stderr.decode())
    return p.stdout, p.stderr.decode()


def members(data):
    with tarfile.open(fileobj=io.BytesIO(data), mode="r") as t:
        return {m.name: (t.extractfile(m).read() if m.isreg() else None) for m in t}


class WinlatorPrograms(unittest.TestCase):
    def test_the_ten_are_left_out_and_counted(self):
        out, err = run([OLD, NEW], make_tar(APPS + KEEP, DIRS))
        got = members(out)
        for n in APPS:
            self.assertNotIn(n, got, n + " should be left out")
        stats = err.split()
        self.assertEqual(len(stats), 8, "eight numbers: " + err)
        self.assertEqual(stats[7], "10", "Winlator programs left out: " + err)
        # and no other count takes them: they are not fonts, Microsoft's or FDK
        self.assertEqual(stats[4:7], ["0", "0", "0"], err)

    def test_what_is_next_to_them_stays_and_is_rewritten(self):
        out, err = run([OLD, NEW], make_tar(APPS + KEEP, DIRS))
        got = members(out)
        for n in KEEP:
            self.assertIn(n, got, n + " should stay")
            self.assertIn(NEW.encode(), got[n], n + " should be rewritten")
            self.assertNotIn(OLD.encode(), got[n])
        self.assertEqual(err.split()[1], str(len(KEEP)), "files changed: " + err)

    def test_the_empty_7zip_folder_goes_the_others_stay(self):
        out, _ = run([OLD, NEW], make_tar(APPS + KEEP, DIRS))
        got = members(out)
        self.assertNotIn("./opt/apps/7-Zip", got)
        self.assertNotIn("./opt/apps/7-Zip/", got)
        for d in ("./opt", "./opt/apps", "./usr/local/bin"):
            self.assertTrue(d in got or d + "/" in got, d + " should stay")

    def test_paths_without_dot_slash_are_caught_too(self):
        plain = [n[2:] for n in APPS]
        out, err = run([OLD, NEW], make_tar(plain))
        self.assertEqual(members(out), {})
        self.assertEqual(err.split()[7], "10", err)

    def test_list_forbidden_names_each_one(self):
        out, _ = run(["--list-forbidden"], make_tar(APPS + KEEP, DIRS))
        lines = out.decode().splitlines()
        self.assertEqual(len(lines), len(APPS), out.decode())
        for n in APPS:
            self.assertIn("%s  (Winlator program SAI does not use)" % n, lines)

    def test_list_forbidden_is_silent_on_what_may_stay(self):
        out, _ = run(["--list-forbidden"], make_tar(KEEP, DIRS))
        self.assertEqual(out, b"")


if __name__ == "__main__":
    unittest.main(verbosity=2)
