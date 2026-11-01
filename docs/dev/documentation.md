# Writing the documentation

- The files under `docs/` are the only source. Do not edit the wiki or a copy.
- A page starts with `# Title`. Link to other pages with relative paths.
- A page is part of the documentation (and of `praksis help`) only if `docs/index.md` lists it.
- `docs/PLAN.md` is the design log; it is not part of the help.
- The topic name of a page is its path without `.md`, for example `user/concepts`.
- The wiki is output only. `tools/build-wiki.py OUTPUT_DIR` builds it from `docs/` (page names from
  the titles, links rewritten, `index.md` as Home and the sidebar), and the workflow
  `.github/workflows/wiki.yml` pushes the result. Two pages must not have the same title.
- `docs/reference/cli.md` and `docs/modules/*.md` are generated (the pages of the built-in modules
  `praksis` and `entity` too). After a change to a command or a
  module, run the build with `-Dpraksis.docs.update=true` and commit the result.
- Pages are shown by `praksis help` and in the Help tab of Praksis Desktop. The tab reads headings,
  paragraphs, bullet and numbered lists, tables, block quotes, indented or fenced code, and in the
  text bold, inline code and links. HTML (except comments), images and nested lists are not shown,
  and the documentation check rejects them.
