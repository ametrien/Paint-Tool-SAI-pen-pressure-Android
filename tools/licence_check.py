"""No SAI licence number may get into the repository: not in code, tests,
comments, docs or commit messages. A licence certificate is named
sai-<number>.slc, and the number together with the e-mail's password
downloads certificates from SYSTEMAX. One real number was once in this
repository's comments and tests as an example.

    python3 -I tools/licence_check.py [DIR]          every file git knows in DIR (tracked, and new ones not ignored)
    python3 -I tools/licence_check.py --stdin        text on stdin (a diff, git log -p): lines removed ("-") don't count
    python3 -I tools/licence_check.py --files F...   these files

Two rules:
1. "sai-" or "sai_" followed by 6 or more digits is a licence number, unless
   it is the one example allowed: sai-000123456789 (made up).
2. Every number in the private list (one per line, never in the repository;
   default ~/Documents/sai-android-build/private/licence-numbers.txt, or
   --private FILE, or $SAIPEN_LICENCE_NUMBERS) may appear nowhere, with or
   without "sai-".

Exit 1 and one line per find, with the number masked; 0 when clean.
Run by ./build.sh and by the git hooks (pre-commit, pre-push).
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ALLOWED = b"000123456789"
PATTERN = re.compile(rb"sai[-_](\d{6,})", re.IGNORECASE)
DEFAULT_PRIVATE = Path.home() / "Documents" / "sai-android-build" / "private" / "licence-numbers.txt"


def mask(number):
    n = number.decode("ascii", "replace")
    return n[:3] + "…" if len(n) > 3 else "…"


def private_numbers(path):
    if path is None:
        env = os.environ.get("SAIPEN_LICENCE_NUMBERS")
        path = Path(env) if env else DEFAULT_PRIVATE
    try:
        text = Path(path).read_bytes()
    except OSError:
        return []
    return [n for n in (line.strip() for line in text.splitlines()) if n and not n.startswith(b"#")]


def finds(data, private):
    """(line number, masked number) for each find in data (bytes)."""
    out = []
    for i, line in enumerate(data.split(b"\n"), 1):
        for m in PATTERN.finditer(line):
            if m.group(1) != ALLOWED:
                out.append((i, mask(m.group(1))))
        for n in private:
            if n in line:
                out.append((i, mask(n)))
    return out


def tree_files(root):
    r = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                       capture_output=True)
    if r.returncode != 0:
        sys.exit("licence_check: not a git repository: " + str(root))
    return [Path(root) / p.decode() for p in r.stdout.split(b"\0") if p]


def main(argv):
    private_path = None
    if "--private" in argv:
        i = argv.index("--private")
        private_path = Path(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    private = private_numbers(private_path)
    found = []
    if argv[:1] == ["--stdin"]:
        lines = [l for l in sys.stdin.buffer.read().split(b"\n") if not (l.startswith(b"-") and not l.startswith(b"---"))]
        found += [("stdin", i, n) for i, n in finds(b"\n".join(lines), private)]
    else:
        files = [Path(f) for f in argv[1:]] if argv[:1] == ["--files"] else tree_files(argv[0] if argv else ".")
        for f in files:
            if not f.is_file():
                continue
            try:
                data = f.read_bytes()
            except OSError:
                continue
            found += [(str(f), i, n) for i, n in finds(data, private)]
    for where, line, n in found:
        print(f"licence_check: a SAI licence number ({n}) in {where}:{line}", file=sys.stderr)
    if found:
        print("licence_check: use sai-000123456789.slc as the example; never a real one", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
