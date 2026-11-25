"""Rewrite one tar stream: every OLD byte string becomes NEW, in file
contents and in symlink targets. stdin -> stdout, plain (uncompressed) tar.

    zstd -dc in.tzst | python3 -I tools/rewrite_tar.py OLD NEW | zstd -19 -o out.tzst
    zstd -dc in.tzst | python3 -I tools/rewrite_tar.py --list-forbidden

Why it exists: Winlator's rootfs was built for /data/data/com.winlator/.
That path is compiled into ~170 binaries (the dynamic loader, libc, Wine's
ntdll.so and wineserver, Box64, X11 and GStreamer libraries) and written in
a few text files and one symlink of the Wine prefix (dosdevices/z:). SAI Pen
has its own package name, so its files live elsewhere.

THE TRAP: a binary only survives an in-place rewrite when the new path has
EXACTLY the old length (no offsets move). That is why the package name is
12 characters, like com.winlator. This script refuses anything else.

It streams the tar entry by entry (never unpacks to disk): the Mac's disk
ignores letter case in file names and would silently merge files that
differ only in case. Order, modes, owners and times stay as they were.

It also leaves out Microsoft's fonts: Winlator ships Arial, Times New Roman,
Courier New, Verdana, Georgia and others (Microsoft's or Monotype's, see each
font's copyright line) in the Wine prefix, and Wine's Tahoma Bold swapped for
Microsoft's. Their licence does not let us pass them on as loose font files.
A font is recognised by its own copyright notice (the "name" table), not by
its file name, so a renamed or newly added one is caught too. Wine's own
fonts (Tahoma regular, Marlett, Symbol, Webdings, Wingdings, the .fon files)
stay.

And it leaves out any other file of Microsoft's (microsoft_reason below):
at Winlator 11.2 that is MFC 4.2, mfc42.dll in the prefix's syswow64.

And the Fraunhofer FDK AAC codec (fdk_reason below): libfdk_aac.so and
GStreamer's plugin for it, libgstfdkaac.so. Its licence is incompatible with
the GPL and allows use only with a patent licence for AAC. SAI needs no AAC.

And Winlator's own extra programs, which SAI never uses (app_reason below):
7-Zip, the file manager wfm.exe with its libcdio.dll, winaddons.exe,
TestD3D.exe, GPUInfo.exe and winpreexec; and Winlator's winhandler.exe,
which our own (winhandler/) replaces. Only Winlator's Start menu and file
manager start them, and SAI Pen's Wine desktop has neither. Several have no
published source, and every one we ship would need its source in the release.

--list-forbidden lists every file of the stream we may not pass on, with why.

Prints to stderr: "<entries> <files changed> <occurrences> <links changed>
<fonts left out> <other Microsoft files left out> <FDK AAC files left out>
<Winlator programs left out>".
"""
import io
import struct
import sys
import tarfile

FONT_ENDINGS = (".ttf", ".ttc", ".otf")
NOT_OURS = (b"Microsoft", b"Monotype")


def font_notices(data):
    """The copyright (name 0) and trademark (name 7) lines of a TrueType or
    OpenType font, as bytes in whatever encoding the font uses; [] if it
    cannot be read."""
    out = []
    try:
        if data[:4] == b"ttcf":                       # a collection: its first font
            data_off = struct.unpack(">I", data[12:16])[0]
        else:
            data_off = 0
        n = struct.unpack(">H", data[data_off + 4:data_off + 6])[0]
        for i in range(n):
            rec = data_off + 12 + 16 * i
            tag, _, off, _ = struct.unpack(">4sIII", data[rec:rec + 16])
            if tag != b"name":
                continue
            _, count, strings = struct.unpack(">HHH", data[off:off + 6])
            for j in range(count):
                r = off + 6 + 12 * j
                _, _, _, name_id, length, at = struct.unpack(">HHHHHH", data[r:r + 12])
                if name_id in (0, 7):
                    raw = data[off + strings + at:off + strings + at + length]
                    out.append(raw)
                    out.append(raw.replace(b"\0", b""))  # UTF-16 as plain letters
    except struct.error:
        return []
    return out


def is_microsoft_font(name, data):
    if not name.lower().endswith(FONT_ENDINGS):
        return False
    return any(w in notice for notice in font_notices(data) for w in NOT_OURS)


# Any other file of Microsoft's: its bytes name the company ("Microsoft
# Corp", as plain letters or as UTF-16, the way a DLL's version resource
# holds it) and it is not one of Wine's own. Wine's DLLs name Microsoft too
# (in strings and GUID names), so they are told apart by the line Wine's
# linker writes into every DLL it builds, in the DOS header at the start of
# the file: "Wine builtin DLL" (or "Wine placeholder DLL" for the stubs in a
# prefix). A real Windows DLL has "This program cannot be run in DOS mode"
# there instead. At Winlator 11.2 @ ecd5ab2 this catches MFC 4.2 (mfc42.dll)
# in the Wine prefix and every DLL of the Windows components (242 of them),
# and none of Wine's ~500 files that name Microsoft.
MICROSOFT = (b"Microsoft Corp", "Microsoft Corp".encode("utf-16-le"))
WINE_HEADERS = (b"Wine builtin DLL", b"Wine placeholder DLL")


def is_wine_file(data):
    return data[:2] == b"MZ" and any(w in data[:0x100] for w in WINE_HEADERS)


def microsoft_reason(name, data):
    """Why this file is Microsoft's, or None if it is not."""
    if is_microsoft_font(name, data):
        return "Microsoft font"
    if any(w in data for w in MICROSOFT) and not is_wine_file(data):
        return "names Microsoft Corp, not Wine's"
    return None


# The FDK AAC codec, by what is inside, not by name: the library holds its
# own allocator's symbol, the GStreamer plugin its registration function.
FDK_MARKS = (b"FDKaalloc", b"gst_plugin_fdkaac_register")


def fdk_reason(name, data):
    if any(w in data for w in FDK_MARKS):
        return "FDK AAC codec"
    return None


# Winlator's programs SAI does not use, by their place in rootfs_patches.tzst
# (Winlator 11.2 @ ecd5ab2). Who starts them there:
#   opt/apps/*            Winlator's Start menu (wine_startmenu.json), and
#                         7-Zip from wfm's context menu (WineUtils.java)
#   wfm.exe, libcdio.dll  the file manager, started only when a container is
#                         run with no program (XServerDisplayActivity); SAI
#                         Pen always runs sai2.exe. libcdio.dll is wfm's.
#   winpreexec            Wine's loader runs it only when WINPREEXEC is set,
#                         and nothing in Winlator or SAI Pen sets it
# winaddons.exe downloads add-ons from the internet; TestD3D.exe, GPUInfo.exe
# and winpreexec have no published source.
WINLATOR_APPS = frozenset((
    "opt/apps/winaddons.exe",
    "opt/apps/7-Zip/7zFM.exe",
    "opt/apps/7-Zip/7z.dll",
    "opt/apps/7-Zip/7zG.exe",
    "opt/apps/TestD3D.exe",
    "opt/apps/GPUInfo.exe",
    "home/xuser/.wine/drive_c/windows/wfm.exe",
    "home/xuser/.wine/drive_c/windows/libcdio.dll",
    "usr/local/bin/winpreexec",
    # not unused, but replaced: SAI Pen puts our own winhandler.exe
    # (winhandler/, built by build.sh) in its place before every session.
    # Winlator's has no published source.
    "home/xuser/.wine/drive_c/windows/winhandler.exe",
))
# their folder, left empty without them
WINLATOR_APP_DIRS = frozenset(("opt/apps/7-Zip",))


def tar_path(name):
    """A member's path as listed above: no leading "./" or "/", no trailing "/"."""
    while name.startswith("./"):
        name = name[2:]
    return name.strip("/")


def app_reason(name):
    if tar_path(name) in WINLATOR_APPS:
        return "Winlator program SAI does not use"
    return None


def forbidden_reason(name, data):
    # by name first: then each count below stays what it was before
    return app_reason(name) or microsoft_reason(name, data) or fdk_reason(name, data)


def is_fdk_link(m):
    """A symlink or hard link to the FDK library (libfdk_aac.so.2 and the like)."""
    return (m.issym() or m.islnk()) and "fdk_aac" in m.linkname.rsplit("/", 1)[-1]


# Wine's ntdll.so reads the SMBIOS system serial number from this file, which
# Android does not have: the serial was empty everywhere, and SAI, which
# builds its System ID from these strings, gave every device the same ID.
# The path becomes <data folder>files/id (same length, in place), written
# per device for a new container by DeviceSerial.java.
DMI_SERIAL = b"/sys/class/dmi/id/product_serial\0"


def serial_path(new):
    p = new + b"files/id\0"
    if len(p) != len(DMI_SERIAL):
        sys.exit("rewrite_tar: %r does not fit in place of %r" % (p, DMI_SERIAL))
    return p


def main():
    if sys.argv[1:] == ["--list-forbidden"]:
        src = tarfile.open(fileobj=sys.stdin.buffer, mode="r|")
        for m in src:
            if m.isreg():
                why = forbidden_reason(m.name, src.extractfile(m).read())
                if why:
                    print("%s  (%s)" % (m.name, why))
            elif is_fdk_link(m):
                print("%s  (link to the FDK AAC codec)" % m.name)
        return
    old, new = sys.argv[1].encode(), sys.argv[2].encode()
    if len(old) != len(new):
        sys.exit("rewrite_tar: '%s' and '%s' differ in length" % (sys.argv[1], sys.argv[2]))
    src = tarfile.open(fileobj=sys.stdin.buffer, mode="r|")
    dst = tarfile.open(fileobj=sys.stdout.buffer, mode="w|", format=src.format or tarfile.PAX_FORMAT)
    entries = files = hits = links = fonts = others = fdk = apps = 0
    for m in src:
        entries += 1
        data = None
        if is_fdk_link(m):
            fdk += 1
            continue
        if m.isdir() and tar_path(m.name) in WINLATOR_APP_DIRS:
            continue
        if m.isreg():
            data = src.extractfile(m).read()
            why = forbidden_reason(m.name, data)
            if why:
                if why == "Microsoft font":
                    fonts += 1
                elif why == "FDK AAC codec":
                    fdk += 1
                elif why == "Winlator program SAI does not use":
                    apps += 1
                else:
                    others += 1
                continue
            n = data.count(old)
            if n:
                data = data.replace(old, new)
                files += 1
                hits += n
            data = data.replace(DMI_SERIAL, serial_path(new))
        if (m.issym() or m.islnk()) and old in m.linkname.encode("utf-8", "surrogateescape"):
            m.linkname = m.linkname.replace(sys.argv[1], sys.argv[2])
            links += 1
        if data is not None:
            dst.addfile(m, io.BytesIO(data))
        else:
            dst.addfile(m)
    dst.close()
    sys.stderr.write("%d %d %d %d %d %d %d %d\n" % (entries, files, hits, links, fonts, others, fdk, apps))


if __name__ == "__main__":
    main()
