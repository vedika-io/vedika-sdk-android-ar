"""Guard: the retired Vandrevala number must not reach a user from this repository.

The current Vandrevala line is +91 9999 666 555 and is never flagged. The retired toll-free number is
the eleven digits 1 8 6 0 2 6 6 2 3 4 5. Twin of vedika `crisis/helpline_guard.rs` and of
vedika-harness `tests/test_retired_helpline_guard.py` (row NEW-ORG-HELPLINE-GUARD-SIBLING-REPOS-1).

What is scanned: every tracked file (`git ls-files -z`; symlinks by their target string; when git cannot
answer, every file outside .git, node_modules and __pycache__). The only exclusion is this file, which
builds its controls from digit groups on purpose.

What is caught, after normalizing (line continuations and \\n \\r \\t escapes to whitespace, HTML entities,
\\uXXXX / \\xXX / %XX escapes, HTML tags up to 200 characters, NFKC, every Unicode decimal digit to ASCII,
format characters such as zero-width space deleted): the eleven digits in order with any whitespace and at
most 3 other non-alphanumeric characters between neighbours, so "1860 266 2345", "1-860-2662-345",
"(1860) 2662 345", "| 1860 | 2662 | 345 |", "tel:+9118602662345" and a line-wrapped number are all caught,
with or without a +91, 91, 0091 or 0 prefix. A digit directly before or after the run keeps it clean.
Spoken digit words (English, Devanagari and roman Hindi) in a run of 11 are caught as well.

Not caught (say so in review): number words ("eighteen sixty"), digit words of other languages, a number
split over string literals, markup longer than 200 characters between digits, double-escaped text, base64,
compressed content, images and PDFs.

Run: python3 -m unittest tests/test_retired_helpline_guard.py   (or pytest)
"""
import html
import os
import re
import subprocess
import tempfile
import unicodedata
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SELF = Path(__file__).resolve()

# The retired number, in groups, so this file never holds it in one piece.
_RETIRED_DIGITS = "".join(("18", "60", "26", "62", "34", "5"))
_SEP = r"(?:\s*[^0-9A-Za-z\s]{0,3}\s*)"
_RETIRED = re.compile(
    r"(?<![0-9])(?:(?:\+?\s?91|0091|0)" + _SEP + r")?" + _SEP.join(_RETIRED_DIGITS) + r"(?![0-9])"
)
# Defensive catch for a mis-converted copy: 91 followed by the number without its leading 1.
_NO_LEADING_ONE = re.compile(r"(?<![0-9])\+?\s?91" + _SEP + _SEP.join(_RETIRED_DIGITS[1:]) + r"(?![0-9])")

_WORDS = {
    "0": "zero oh o shunya shoonya sifar \u0936\u0942\u0928\u094d\u092f",
    "1": "one ek \u090f\u0915",
    "2": "two do \u0926\u094b",
    "3": "three teen tin \u0924\u0940\u0928",
    "4": "four char chaar \u091a\u093e\u0930",
    "5": "five panch paanch \u092a\u093e\u0902\u091a \u092a\u093e\u0901\u091a \u092a\u0902\u091a",
    "6": "six chhe chah chhah \u091b\u0939 \u091b\u0903",
    "7": "seven saat sat \u0938\u093e\u0924",
    "8": "eight aath ath \u0906\u0920",
    "9": "nine nau nav \u0928\u094c \u0928\u094d\u0928\u0947",
}
_WORD_TO_DIGIT = {w: d for d, ws in _WORDS.items() for w in ws.split()}
_MULT = {"double": 2, "triple": 3, "\u0921\u092c\u0932": 2, "\u091f\u094d\u0930\u093f\u092a\u0932": 3}
_FORMAT_CHARS = re.compile(r"[\u00ad\u200b-\u200f\u2060-\u2064\ufeff]")
_TAG = re.compile(r"<[^<>]{0,200}>")


def _normalize(text, strip_tags=True):
    t = re.sub(r"\\\r?\n", " ", text)
    t = t.replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
    t = html.unescape(t)
    t = re.sub(r"\\u\{([0-9A-Fa-f]{1,6})\}", lambda m: _chr(int(m.group(1), 16)), t)
    t = re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: _chr(int(m.group(1), 16)), t)
    t = re.sub(r"\\x([0-9A-Fa-f]{2})", lambda m: _chr(int(m.group(1), 16)), t)
    t = re.sub(r"%([0-9A-Fa-f]{2})", lambda m: _chr(int(m.group(1), 16)), t)
    if strip_tags:
        t = _TAG.sub(" ", t)
    t = unicodedata.normalize("NFKC", t)
    t = "".join(str(unicodedata.digit(c)) if unicodedata.category(c) == "Nd" else c for c in t)
    return _FORMAT_CHARS.sub("", t)


def _chr(n):
    try:
        return chr(n)
    except (ValueError, OverflowError):
        return " "


def _spoken_digits(text):
    """Digit strings spoken in runs of digit words (at most 6 separator characters between words)."""
    runs, cur, mult = [], [], 1
    for m in re.finditer(r"[^\W\d_ऀ-ॿ]+|[ऀ-ॿ]+|[^\s\w]|\s+|\d+|_", text):
        tok = m.group(0)
        low = tok.lower()
        if low in _MULT:
            mult = _MULT[low]
        elif low in _WORD_TO_DIGIT:
            cur.append(_WORD_TO_DIGIT[low] * mult)
            mult = 1
        elif tok.isspace() or (not tok.isalnum() and len(tok) <= 6):
            continue
        else:
            if cur:
                runs.append("".join(cur))
            cur, mult = [], 1
    if cur:
        runs.append("".join(cur))
    return runs


def retired_hits(text):
    """Every place the retired number is written in `text`, as a short excerpt; empty when there is none."""
    hits = []
    for strip in (False, True):  # tags kept (a tel: href) and tags dropped (digits split by markup)
        t = _normalize(text, strip)
        hits += [m.group(0).strip()[:60] for m in _RETIRED.finditer(t)]
        hits += [m.group(0).strip()[:60] for m in _NO_LEADING_ONE.finditer(t)]
        hits += ["spoken:" + r for r in _spoken_digits(t) if _RETIRED_DIGITS in r]
    return list(dict.fromkeys(hits))


def _tracked_files(root):
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True
        ).stdout
        top = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--show-toplevel"], capture_output=True, check=True, text=True
        ).stdout.strip()
        if Path(top).resolve() != Path(root).resolve():
            raise RuntimeError("different top level")
        return "git", [Path(root) / p for p in out.decode("utf-8", "replace").split("\0") if p]
    except (OSError, subprocess.CalledProcessError, RuntimeError):
        files = []
        for d, dirs, names in os.walk(root):
            dirs[:] = [x for x in dirs if x not in (".git", "node_modules", "__pycache__")]
            files += [Path(d) / n for n in names]
        return "walk", files


def scan(root, skip=()):
    mode, files = _tracked_files(root)
    found = []
    for p in files:
        if p.resolve() in skip or p.is_dir():
            continue
        try:
            raw = os.readlink(p) if p.is_symlink() else None
            data = raw if raw is not None else p.read_bytes().decode("utf-8", "replace")
        except OSError:
            continue
        for h in retired_hits(data) + retired_hits(str(p.relative_to(root))):
            found.append("%s: %s" % (p.relative_to(root), h))
    return mode, found


def _c(*groups):
    return " ".join(groups)


POSITIVE = {
    "spaced groups": _c("18" "60", "26" "62", "34" "5"),
    "dashed": "-".join(("18" "60", "26" "62", "34" "5")),
    "dashed 4-3-4": "-".join(("18" "60", "266", "23" "45")),
    "plain": _RETIRED_DIGITS,
    "plus 91": "+91 " + _RETIRED_DIGITS,
    "tel link": "tel:+91" + _RETIRED_DIGITS,
    "split by markup": "<b>18" "60</b> 26" "62 <i>34" "5</i>",
    "tel link dashed": "tel:" + "-".join(("18" "60", "26" "62", "34" "5")),
    "leading zero": "0" + _RETIRED_DIGITS,
    "parens": "(" + "18" "60" + ") 26" "62 34" "5",
    "markdown table": "| " + " | ".join(("18" "60", "26" "62", "34" "5")) + " |",
    "wrapped line": "18" "60 26" "62\n34" "5",
    "dots": ".".join(("18" "60", "26" "62", "34" "5")),
    "slashes": " / ".join(("18" "60", "26" "62", "34" "5")),
    "html entities": "&#49;&#56;&#54;&#48;" + " 26" "62 34" "5",
    "full width digits": "".join(chr(0xFF10 + int(d)) for d in _RETIRED_DIGITS),
    "devanagari digits": "".join(chr(0x0966 + int(d)) for d in _RETIRED_DIGITS),
    "zero width": "18" "\u200b60 26" "62 34" "5",
    "json list": '["9152987821", "' + "-".join(("18" "60", "26" "62", "34" "5")) + '"]',
    "spoken english": "one eight six zero two six six two three four five",
    "spoken hinglish": "ek aath chhe shunya do chhe chhe do teen char panch",
    "spoken double": "one eight six zero two double six two three four five",
    "spoken devanagari": "\u090f\u0915 \u0906\u0920 \u091b\u0939 \u0936\u0942\u0928\u094d\u092f \u0926\u094b \u091b\u0939 \u091b\u0939 \u0926\u094b \u0924\u0940\u0928 \u091a\u093e\u0930 \u092a\u093e\u0902\u091a",
}
NEGATIVE = {
    "current number": "+91 9999 666 555",
    "current number spoken": "nine nine nine nine, six six six, five five five",
    "icall": "9152987821",
    "aasra": "9820466726",
    "digit before": "1" + _RETIRED_DIGITS + " 9",
    "digit after": _RETIRED_DIGITS[:-1] + "6",
    "other tollfree": "1800 11 4000",
    "emergency": "112 and 988",
}


class RetiredHelplineGuard(unittest.TestCase):
    def test_positive_controls_are_caught(self):
        missed = [k for k, v in POSITIVE.items() if not retired_hits(v)]
        self.assertEqual(missed, [], "guard misses its own positive controls")

    def test_negative_controls_stay_clean(self):
        flagged = {k: retired_hits(v) for k, v in NEGATIVE.items() if retired_hits(v)}
        self.assertEqual(flagged, {}, "guard flags a number that is not the retired one")

    def test_a_planted_file_is_found_by_the_repo_scan(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "-C", d, "init", "-q"], check=True)
            (Path(d) / "page.html").write_text("<a href='tel:+91" + _RETIRED_DIGITS + "'>Call</a>")
            (Path(d) / "ok.txt").write_text("+91 9999 666 555")
            subprocess.run(["git", "-C", d, "add", "."], check=True)
            mode, found = scan(Path(d))
            self.assertEqual(mode, "git")
            self.assertEqual([f.split(":")[0] for f in found], ["page.html"])

    def test_repository_never_carries_the_retired_number(self):
        mode, found = scan(ROOT, skip=(SELF,))
        self.assertEqual(
            found, [], "retired helpline number found (scan mode %s); the current line is +91 9999 666 555" % mode
        )


if __name__ == "__main__":
    unittest.main()
