"""Condenses a release's notes (release-notes/vX.Y.Z.md) into F-Droid's "What's new" (at most
500 characters): the bold lead of each item, with a link to the full notes.

    python3 changelog.py NOTES.md VERSION > changelog.txt
"""
import os
import re
import sys

LIMIT = 500
RELEASES = os.environ.get("SOURCE_URL", "https://github.com/603Madison/SuperAwesomeMagicalMusic") + "/releases/tag/v"

notes, version = sys.argv[1], sys.argv[2]
text = open(notes, encoding="utf-8").read()
leads = [m.group(1).strip().rstrip(".") for m in re.finditer(r"^\s*-\s+\*\*(.+?)\*\*", text, re.M)]
link = f"\n\nFull notes: {RELEASES}{version}"
if leads:
    lines = []
    for lead in leads:
        candidate = "\n".join(lines + [f"• {lead}"])
        if len(candidate) + len(link) > LIMIT:
            break
        lines.append(f"• {lead}")
    body = "\n".join(lines)
else:
    plain = re.sub(r"[*`#]", "", text).strip()
    body = plain[: LIMIT - len(link) - 1].rsplit(" ", 1)[0] + "…" if len(plain) > LIMIT - len(link) else plain
print((body + link)[:LIMIT])
