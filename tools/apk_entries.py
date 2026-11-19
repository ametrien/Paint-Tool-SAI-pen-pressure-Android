"""Read an APK's files straight from the zip, never unpacked to disk.

    python3 -I tools/apk_entries.py --list-tzst F.apk     names of the .tzst archives in it
    python3 -I tools/apk_entries.py --cat F.apk NAME      one file's bytes, to stdout
    python3 -I tools/apk_entries.py --tar F.apk           every other file as one tar stream

Why: a release APK's shrunk resource names differ only in letter case
(res/2r.xml and res/2R.xml), and the Mac's disk ignores case, so unzip asks
which one to keep, and keeping one hides the other from `build.sh check-apk`.
LICENSES.txt is left out of --tar: it quotes Microsoft's name in licence texts.
"""
import io
import sys
import tarfile
import zipfile


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    mode, apk = sys.argv[1], sys.argv[2]
    z = zipfile.ZipFile(apk)
    if mode == "--list-tzst":
        for n in z.namelist():
            if n.endswith(".tzst"):
                print(n)
    elif mode == "--cat" and len(sys.argv) == 4:
        sys.stdout.buffer.write(z.read(sys.argv[3]))
    elif mode == "--tar":
        out = tarfile.open(fileobj=sys.stdout.buffer, mode="w|")
        for info in z.infolist():
            n = info.filename
            if n.endswith("/") or n.endswith(".tzst") or n.endswith("saipen/LICENSES.txt"):
                continue
            data = z.read(info)
            m = tarfile.TarInfo(n)
            m.size = len(data)
            out.addfile(m, io.BytesIO(data))
        out.close()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
