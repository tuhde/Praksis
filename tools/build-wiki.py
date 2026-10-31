#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Builds the GitHub wiki from docs/ into a directory.

    tools/build-wiki.py OUTPUT_DIR

docs/index.md is the list of pages. Every listed page becomes one wiki page named after its title
("# Getting started" -> Getting-started.md), relative links between pages are rewritten to wiki
links, index.md becomes Home.md and its sections become _Sidebar.md. Files in OUTPUT_DIR that are
not produced here (except .git) are removed, so the wiki is exactly the output of this script.
Nothing is pushed here; see .github/workflows/wiki.yml.
"""
import posixpath
import re
import shutil
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parent.parent / "docs"
ENTRY = re.compile(r"^- \[([^\]]+)\]\(([^)]+\.md)\)\s*$")
LINK = re.compile(r"(\]\()([^)\s]+)(\))")


def wiki_name(title):
    return re.sub(r"[^\w.-]+", "-", title.strip()).strip("-")


def main(out):
    index = (DOCS / "index.md").read_text(encoding="utf-8").splitlines()
    pages = [m.group(2) for m in map(ENTRY.match, index) if m]
    names = {}
    for page in pages:
        first = (DOCS / page).read_text(encoding="utf-8").splitlines()[0]
        if not first.startswith("# "):
            sys.exit(f"{page} does not start with a title")
        name = wiki_name(first[2:])
        if name in names.values() or name == "Home":
            sys.exit(f"{page}: the wiki name {name} is used twice")
        names[page] = name
    names["index.md"] = "Home"

    def rewrite(text, page):
        fenced = False
        lines = []
        for line in text.splitlines():
            if line.startswith("```") or line.startswith("~~~"):
                fenced = not fenced
            if not fenced and not line.startswith("    "):
                line = LINK.sub(lambda m: m.group(1) + target(m.group(2), page) + m.group(3), line)
            lines.append(line)
        return "\n".join(lines) + "\n"

    def target(link, page):
        if link.startswith("#") or ":" in link:
            return link
        path, _, frag = link.partition("#")
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(page), path))
        if resolved not in names:
            sys.exit(f"{page} links to {link}, which is not a listed page")
        return names[resolved] + ("#" + frag if frag else "")

    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    for entry in out.iterdir():
        if entry.name == ".git":
            continue
        shutil.rmtree(entry) if entry.is_dir() else entry.unlink()
    for page, name in names.items():
        text = (DOCS / page).read_text(encoding="utf-8")
        (out / f"{name}.md").write_text(rewrite(text, page), encoding="utf-8")

    sidebar = ["**[Home](Home)**", ""]
    for line in index:
        if line.startswith("## "):
            sidebar += ["", f"**{line[3:]}**", ""]
        m = ENTRY.match(line)
        if m:
            sidebar.append(f"- [{m.group(1)}]({names[m.group(2)]})")
    (out / "_Sidebar.md").write_text("\n".join(sidebar) + "\n", encoding="utf-8")
    print(f"{len(names)} pages written to {out}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
