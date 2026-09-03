# Code Quality Insights (Confluence)

Registers GitHub repositories on a Confluence page and reports code quality with metrics that
do **not** have lines of code in the denominator - so growing a codebase cannot improve the
score on its own.

Built for Confluence Server / Data Center 7.x (developed against 7.8.1 in Docker, targeting
7.12.3).

## Why not TD/LOC, V/KLOC, Complexity, Duplicates

Every one of those divides by LOC. When most new code is written by a model, the denominator
inflates first, and generated code passes linters and static rules by default - so the
numerator grows slowly. Maintainability falls while the ratio looks flat or improves.

The reference repository shows it directly: duplicated lines went from 60 to 85 (+42%) over
twenty days while the duplication *ratio* stayed between 1.0% and 1.6%. A dashboard showing
only the ratio would have reported "no change".

## What it measures

| Metric | Source | Notes |
|---|---|---|
| Copy-paste ratio | git history | Share of added lines that already existed as a block of 3+ lines |
| Refactoring ratio | git history | moved / (moved + copied) - code that left its old home vs. code that now exists twice |
| Two-week churn | git history | Added lines rewritten within 14 days, with right-censoring |
| Duplicated lines | HEAD tree | Absolute count first, ratio second; level and direction graded separately |
| Error-swallowing density | HEAD tree | Bare / over-broad / empty-bodied / log-and-continue handlers per KLOC |
| Function connectivity | HEAD tree | Call-shaped tokens per KLOC - **an approximation**, labelled as such |

Plus ownership concentration, bus factor, and suspected duplicate git identities. The report
also carries the four **legacy** metrics it replaces - duplication ratio, cyclomatic complexity,
comment density, average function length - ungraded and above the new ones, so the comparison is
on one page. All four divide by lines of code or function count; watching them stay flat while
the metrics below move is the argument for the whole thing.

Languages: Python, Java, JavaScript/TypeScript. Other files are skipped rather than analysed
badly.

## No AI at analysis time

Nothing in the analysis consults a model or any network service except the git clone itself.
Every number comes from diff parsing, hash lookups and line scanning, so the same commit
always yields the same value - which is the only reason the trend lines mean anything. An LLM
does not give the same output twice, and a metric that wobbles cannot answer
"is duplication going up or down".

Every cached row carries the algorithm version. Bumping `AnalysisConfig.ALGO_VERSION`
invalidates the cache and forces a recompute, because a trend line that mixes two algorithm
versions measures the tooling, not the code.

## Design decisions worth knowing

- **Blocks, not lines.** The first classifier judged each added line on its own and reported a
  13.7% copy-paste ratio for the reference repository - almost all of it language idioms
  (`painter.end()`, `@staticmethod`). Requiring three consecutive matching lines dropped it to
  4.2%, and every remaining block was a real duplicate.
- **A move is a block whose source this commit deleted.** Searching the post-commit file for the
  block instead reports a within-file move as a copy. That mistake made the first cut report
  zero moves on a repository that had twelve.
- **Churn is censored, not zeroed.** A commit newer than 14 days has an open observation window;
  its churn is "not known", and the report greys it out and excludes it from the average. Skip
  this and the newest commits always look healthiest.
- **Bulk imports are excluded.** A commit that lands a whole codebase makes every ratio
  meaningless, so the root commit and any commit adding more than half its parent's code are
  detected and left out of the ratios.
- **Identities are not merged on a guess.** `.mailmap` and GitHub's `noreply` form are honoured;
  a name-prefix rule is not, because it would also merge two genuinely different people. Likely
  duplicates are reported as a finding pointing at `.mailmap` instead.
- **First-parent history.** A merge commit is analysed as one change against its branch point,
  so a pull request is attributed to its merge. This keeps the tree state linear, which is what
  makes incremental analysis possible at all.
- **No dual-axis charts.** Duplicated lines and duplication ratio are two stacked panels sharing
  an x-axis. On one plot the alignment of the two scales would be arbitrary.
- **Level and direction are separate verdicts.** "Is this bad now" and "is this getting worse"
  are different questions, and they disagree: one reference repository sat at 4.3% while
  cleaning up, another at 1.3% while filling up fast. One badge hid whichever axis lost. The
  combined grade takes the worse axis *and names it* ("act - trend").
- **A direction needs a floor.** +52% sounds alarming and was 26 lines. Below
  `DUP_MIN_LINES x 4` = 20 lines of absolute change, no direction is claimed - derived from the
  detector's own minimum clone size, not chosen.
- **A level with no baseline gets no grade.** The absolute thresholds come from a measured
  cohort (below) and apply only to the language they were measured on. Everything else shows
  "no baseline set". An invented 5%/10% would still be sitting there unexamined in six months.
- **Import, package and require lines are not duplication.** They are identical across a
  package by construction. On the Java reference repository they were 47% of all reported
  duplication - a twelve-line `import javax.ws.rs.*` block counted as a twelve-line clone.
- **Generated lookup tables are not code.** A file that is overwhelmingly literals with no
  calls, assignments or keywords is dropped. This is what separated a usable distribution from
  an unusable one: with them in, rich measured 20.4% duplication (one Unicode table per version)
  and black 22.3% (its `profiling/` data dumps).
- **Tests, docs and examples are out of scope.** They are repetitive by design, so including
  them makes the duplication ratio a measure of how many tutorial files a project ships.
- **Every database access goes through SAL's `TransactionTemplate`, reads included.** Analyses
  run on a background thread, and on Confluence that thread has no Hibernate session bound to
  it: `ActiveObjects.executeInTransaction` alone fails there with
  *"No Hibernate Session bound to thread"*, which is exactly how the first install failed.
- **The service hands out value snapshots, not Active Objects entities.** AO returns proxies
  that hit the database on every getter, so an entity fetched inside a transaction and read
  after it closed fails the same way. `RepoSnapshot` copies the values out while the
  transaction is still open. Any new method that touches AO needs both of these.

## Where the duplication thresholds come from

Not a guess and not a placeholder. 19 public Python repositories over 1,000 LOC - requests,
flask, click, jinja, werkzeug, tornado, celery, scrapy, sqlalchemy, pydantic, fastapi, httpx,
black, pytest, attrs, arrow, loguru, gunicorn, rich - cloned shallow and run through this same
detector with these same exclusions on 2026-09-02:

| percentile | duplication ratio |
|---|---|
| p10 | 0.79% |
| p25 | 1.22% |
| p50 | 2.66% |
| p75 | **5.32%** -> warn |
| p90 | **8.74%** -> act |

fastapi (25.1%) and httpx (14.1%) stay at the top and belong there: fastapi repeats its whole
annotated parameter list per HTTP verb, httpx mirrors `Client` and `AsyncClient` method for
method. Both are real and well known.

Raw measurements are in `tools/cohort-python-2026-09-02.tsv`; regenerate with
`tools/clone-cohort.sh` plus `tools/CohortProbe.java`. **Replace this with your own
repositories' distribution once you have five or more** - and measure a cohort per language,
because a Python-derived number applied to Java is a guess wearing a measurement's clothes.
That is why the Java reference repository shows no level grade.

## Architecture

Everything runs inside Confluence - no worker, no external database, no Node runtime:

- **JGit**, inlined into the plugin jar, keeps a bare mirror of each repository under
  `<confluence-home>/plugin-data/code-quality/repos/`. Bare and never checked out: the analysis
  reads blobs straight from the object database.
- Analyses run on a **single background thread**. Two clones plus two full history walks at once
  on a Confluence node is how a reporting feature turns into an outage.
- Per-commit metrics are cached in Active Objects. A rerun replays only new commits plus a
  trailing window wide enough that every commit whose churn could still change is re-derived.
  The incremental path is verified to produce numbers identical to a full recompute
  (`tools/IncrementalProbe.java`).
- The duplicate detector and the error/connectivity scanners are Java reimplementations of what
  the proof of concept did with jscpd and tree-sitter, which a JVM plugin cannot load.

## Usage

1. Install the jar through **Manage apps**.
2. Insert the **code-quality** macro on a page, or open
   **Administration → Code Quality Insights**.
3. Add a repository (HTTPS clone URL; an access token is needed for private repositories and is
   stored encrypted, never returned to the browser). **Test connection** checks it before saving.
4. Press **Analyze**, then **Report**.

The report page ships Korean and English in the same document, switched from the control in the
top-right corner - Korean by default, and the choice is remembered per browser. Both languages
are embedded rather than fetched, so switching keeps the reader's scroll position and the page
still works after it is saved or printed.

The admin link sits in the Confluence admin sidebar under **Configuration**
(`system.admin/configuration`), or go straight to
`/plugins/servlet/code-quality/admin`.

Registering and analysing require Confluence administrator rights - it hands the instance a
credential and asks it to clone from the internet. Reading a report only needs a login.

Thresholds ship with defaults back-solved from industry figures and one reference repository.
They are weak evidence and the report says so. Run five to ten of your own repositories
through it, look at the real distribution, and override per repository.

## Build

```bash
./build.sh          # atlas-mvn clean package, then copies the jar to the Windows Downloads folder
CONFLUENCE_USER=bskim CONFLUENCE_PASS='...' ./deploy.sh   # build and upload to the Docker instance
python3 tools/make-i18n.py   # regenerate the .properties files after editing the message source
```

`src/main/resources/code-quality*.properties` are generated: Confluence reads plugin properties
as ISO-8859-1, so Korean has to be `\uXXXX` escaped. Edit `tools/make-i18n.py`, never the
`.properties` files.

## Verification tools

Not part of the plugin; they run the engine outside Confluence.

| Tool | What it does |
|---|---|
| `tools/Probe.java` | Runs the engine on a bare clone and dumps the report JSON |
| `tools/IncrementalProbe.java` | Asserts an incremental run matches a full one commit for commit |
| `tools/RenderProbe.java` | Renders the report page (both languages) to a file so the layout can be looked at |
| `tools/MakeIcons.java` | Rasterises `images/code-quality.png` into the two UPM icon sizes |

Cross-check against the Python proof of concept on `whisperlip-boop/captureV` (27 commits):

| Metric | Proof of concept | This plugin |
|---|---|---|
| Copy-paste ratio | 4.4% (52 lines) | 4.2% (49 lines) |
| Refactoring ratio | 18.8% (12 moved) | 19.7% (12 moved) |
| Two-week churn | 2.4% | 2.4% |
| Error-swallowing density | 0.69/KLOC | 0.71/KLOC |
| Connectivity change | -3.2% | -3.1% |
| Full-history runtime | ~4 min | ~0.4 s |

Duplication differs by design (76 lines vs 85): normalised line-window hashing rather than
jscpd's token matching. Both point at the same blocks, including the magnifier logic cloned
between `color_pick_overlay.py` and `region_overlay.py`.

## Verified on a running instance

Installed on Confluence 7.8.1 (WSL2 Docker) and driven end to end:

| Check | Result |
|---|---|
| Plugin installs and enables | OK |
| AO tables created, REST resource responds | OK |
| JGit clones from GitHub inside OSGi | OK (`ls-remote` found 1 branch) |
| Full analysis of captureV on the background thread | OK, numbers identical to the offline probe |
| Re-run against the live database | identical numbers, no duplicate rows |
| Report page, both languages, switcher | OK |
| Macro renders on a page, web-resource batch loads | OK |
| Empty state for a repository with no run | OK in both languages |
| Anonymous access to the servlets and REST | 401 |
| Missing repository | 404 |
| Repository delete, cache dropped | OK |

## Known limits

- Error-swallowing and connectivity are line scanners with block tracking, not parsers.
- AI contribution is not measured. There is no reliable way to tell from a diff which lines a
  model wrote; commit-size and typing-speed heuristics are not trustworthy. It becomes
  measurable only through a commit-trailer convention, which is a process decision - and the
  share of AI-written code is not a quality metric in either direction.
- Mutation score and change-failure rate need a test run and a deployment system. They belong in
  CI, with the plugin collecting rather than computing them.
- These are team-level diagnostics. Used to rate individuals they get gamed immediately.
