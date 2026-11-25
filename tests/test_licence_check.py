"""Tests for tools/licence_check.py: no SAI licence number gets into the
repository, except the one made-up example.

    python3 -I tests/test_licence_check.py

Run by ./build.sh with the other tests that need no Winlator. The numbers
here are built from pieces, so this file itself has none for the check to find.
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "..", "tools", "licence_check.py")
EXAMPLE = "sai-" + "000123" + "456789"           # the one allowed
OTHER = "sai-" + "987654" + "321098"             # any other number
PRIVATE = "5551" + "2345" + "6789"               # a "real" one, in the private list


def run(args, stdin=None, private=None):
    env = dict(os.environ, SAIPEN_LICENCE_NUMBERS=private or os.devnull)
    return subprocess.run([sys.executable, "-I", TOOL] + args, input=stdin, capture_output=True, env=env)


class LicenceCheck(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.d = self.dir.name

    def tearDown(self):
        self.dir.cleanup()

    def file(self, name, text):
        p = os.path.join(self.d, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        return p

    def private_list(self):
        return self.file("numbers.txt", "# the owner's\n" + PRIVATE + "\n")

    def test_the_example_is_allowed(self):
        f = self.file("a.java", "// a certificate like " + EXAMPLE + ".slc\n")
        self.assertEqual(run(["--files", f]).returncode, 0)

    def test_any_other_number_fails_and_is_masked(self):
        f = self.file("a.java", "ok\nString n = \"" + OTHER + ".slc\";\n")
        r = run(["--files", f])
        self.assertEqual(r.returncode, 1)
        err = r.stderr.decode()
        self.assertIn("a.java:2", err)
        self.assertNotIn(OTHER[4:], err, "the number itself must not be printed")

    def test_underscore_and_capitals_count_too(self):
        f = self.file("a.md", OTHER.upper().replace("-", "_") + "\n")
        self.assertEqual(run(["--files", f]).returncode, 1)

    def test_a_short_number_is_not_a_licence(self):
        f = self.file("a.md", "sai-2 and sai-12345 and sai2-Alpha-20261120-64bit\n")
        self.assertEqual(run(["--files", f]).returncode, 0)

    def test_a_private_number_fails_even_without_sai(self):
        f = self.file("a.txt", "id " + PRIVATE + "\n")
        self.assertEqual(run(["--files", f], private=self.private_list()).returncode, 1)
        self.assertEqual(run(["--files", f]).returncode, 0, "without the private list it is just digits")

    def test_stdin_counts_added_lines_not_removed_ones(self):
        removed = ("--- a/x\n+++ b/x\n-old " + OTHER + "\n+new " + EXAMPLE + "\n").encode()
        self.assertEqual(run(["--stdin"], stdin=removed).returncode, 0)
        added = ("+++ b/x\n+new " + OTHER + "\n").encode()
        self.assertEqual(run(["--stdin"], stdin=added).returncode, 1)
        message = ("commit abc\n    Licence " + OTHER + "\n").encode()
        self.assertEqual(run(["--stdin"], stdin=message).returncode, 1, "a commit message counts")

    def test_a_tree_checks_what_git_knows_not_what_it_ignores(self):
        subprocess.run(["git", "init", "-q", self.d], check=True)
        self.file(".gitignore", "secret.txt\n")
        self.file("secret.txt", OTHER + "\n")
        self.file("clean.java", EXAMPLE + "\n")
        self.assertEqual(run([self.d]).returncode, 0)
        self.file("new.java", OTHER + "\n")              # new, not added yet: still counts
        self.assertEqual(run([self.d]).returncode, 1)


if __name__ == "__main__":
    unittest.main()
