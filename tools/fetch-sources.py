"""The source archives to attach to each release, next to the APK.

    python3 -I tools/fetch-sources.py            download into dist/sources/, check each SHA-256
    python3 -I tools/fetch-sources.py --record   the same, and write each SHA-256 into the list

What to fetch is in third_party/sources.tsv: the source of every part of
the APK under the GPL or the LGPL. The GPL 2 and the LGPL 2.1 count source offered from the same place
as the binary as given with it, so each GitHub release carries these files
next to the APK (README, "Licences", says so).

A file already in dist/sources/ with the right SHA-256 is not downloaded
again. A different SHA-256 stops the script: the archive changed upstream,
and someone has to look at why before it goes out with a release.
Afterwards dist/sources/SHA256SUMS lists every file.
"""
import gzip
import hashlib
import io
import sys
import tarfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
LIST = HERE / "third_party" / "sources.tsv"
OUT = HERE / "dist" / "sources"


def rows():
    """(line index, file, covers, licence, url, sha256) for each entry."""
    for i, line in enumerate(LIST.read_text(encoding="utf-8").split("\n")):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) == 4:  # no SHA-256 yet (editors drop a trailing tab)
            cols.append("")
        if len(cols) == 5:
            cols.append("")
        if len(cols) != 6:
            sys.exit(f"fetch-sources: line {i + 1} of {LIST.name} has {len(cols)} columns, not 5 or 6")
        yield (i, *cols)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def keep_only(path, folders):
    """Repack a .tar.gz with only these top folders (under the archive's
    own top folder), the same bytes every time: members in their order,
    their own times, gzip without a time stamp."""
    keep = folders.split()
    buf = io.BytesIO()
    with tarfile.open(path, "r:gz") as src, tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as dst:
        n = 0
        for m in src:
            parts = m.name.split("/", 2)
            if len(parts) < 2 or parts[1] not in keep:
                continue
            dst.addfile(m, src.extractfile(m) if m.isreg() else None)
            n += 1
    if n == 0:
        raise ValueError("none of " + folders + " is in the archive")
    with open(path, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", mtime=0, filename="") as gz:
        gz.write(buf.getvalue())


def download(url, dest):
    part = dest.with_name(dest.name + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "source-fetcher"})
    with urllib.request.urlopen(req, timeout=120) as r, open(part, "wb") as f:
        while True:
            block = r.read(1 << 20)
            if not block:
                break
            f.write(block)
    head = part.read_bytes()[:64].lstrip().lower()
    if head.startswith((b"<!doctype", b"<html")):
        part.unlink()
        raise ValueError("got a web page, not an archive")
    part.replace(dest)


def main():
    record = sys.argv[1:] == ["--record"]
    if sys.argv[1:] and not record:
        sys.exit(__doc__)
    OUT.mkdir(parents=True, exist_ok=True)
    lines = LIST.read_text(encoding="utf-8").split("\n")
    sums, bad = [], 0
    for i, name, covers, licence, url, want, only in rows():
        dest = OUT / name
        if dest.exists() and want and sha256(dest) == want:
            print(f"ok   {name} (already here)")
            sums.append((want, name))
            continue
        try:
            download(url, dest)
            if only:
                keep_only(dest, only)
        except Exception as e:  # report every failure, then fail once
            print(f"FAIL {name}: {url}: {e}")
            bad += 1
            continue
        got = sha256(dest)
        size = dest.stat().st_size // 1024
        if want and got != want:
            print(f"FAIL {name}: SHA-256 {got}, the list says {want}")
            bad += 1
            continue
        if not want and not record:
            print(f"FAIL {name}: no SHA-256 in the list yet (run with --record and look at the diff)")
            bad += 1
            continue
        if record and not want:
            lines[i] = "\t".join((name, covers, licence, url, got) + ((only,) if only else ()))
        print(f"ok   {name} ({size} KB)")
        sums.append((got, name))
    if record:
        LIST.write_text("\n".join(lines), encoding="utf-8")
    (OUT / "SHA256SUMS").write_text("".join(f"{h}  {n}\n" for h, n in sums), encoding="utf-8")
    total = sum((OUT / n).stat().st_size for _, n in sums) // (1 << 20)
    print(f"{len(sums)} archive(s), {total} MB, in {OUT.relative_to(HERE)}; SHA256SUMS written")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
