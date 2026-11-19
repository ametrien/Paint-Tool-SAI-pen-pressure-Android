"""Licence texts and copyright notices of every part of the APK, in one file.

    python3 -I tools/collect-licenses.py fetch   download what sources.tsv lists
    python3 -I tools/collect-licenses.py         join them into LICENSES.txt

third_party/licenses/sources.tsv says which file covers what and where it
comes from; the downloaded files are committed, so a build needs no network.
Files whose URL is "-" are written by hand in that folder. build.sh copies
LICENSES.txt into the APK, and the app shows it under "Licences".
"""
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
DIR = HERE / "third_party" / "licenses"
OUT = HERE / "LICENSES.txt"
RULE = "=" * 78


def entries():
    for line in (DIR / "sources.tsv").read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, title, url = line.split("\t")
        yield name, title, url


def fetch():
    bad = 0
    for name, title, url in entries():
        if url == "-":
            continue
        req = urllib.request.Request(url, headers={"User-Agent": "licence-collector"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read().decode("utf-8", "replace")
        except Exception as e:  # report every failure, then fail once
            print(f"FAIL {name}: {url}: {e}")
            bad += 1
            continue
        if text.lstrip().lower().startswith(("<!doctype", "<html")):
            print(f"FAIL {name}: {url} gave a web page, not a licence")
            bad += 1
            continue
        (DIR / name).write_text(text.replace("\r\n", "\n"), encoding="utf-8")
        print(f"ok   {name} ({len(text)} chars)")
    sys.exit(1 if bad else 0)


def join():
    parts = ["Licences of SAI Pen for Android and of everything inside it.\n"
             "THIRD_PARTY.md says which part comes from where. First the licence texts,\n"
             "then each project's own copyright notice and licence.\n"]
    missing = []
    for name, title, url in entries():
        f = DIR / name
        if name == "GPL-3.0.txt":
            f = HERE / "LICENSE"
        elif name == "LGPL-2.1.txt" and not f.exists():
            missing.append(name)
            continue
        if not f.exists():
            missing.append(name)
            continue
        src = "" if url == "-" else f"\n(from {url})"
        parts.append(f"{RULE}\n{title}{src}\n{RULE}\n\n{f.read_text(encoding='utf-8').strip()}\n")
    if missing:
        sys.exit("collect-licenses: missing " + ", ".join(missing) + " (run with 'fetch')")
    OUT.write_text("\n".join(parts), encoding="utf-8")
    print(f"wrote {OUT.name} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    fetch() if sys.argv[1:] == ["fetch"] else join()
