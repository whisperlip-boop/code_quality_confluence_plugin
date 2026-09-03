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

Not a guess and not a placeholder. 112 public repositories over 1,000 measured lines, cloned
shallow and run through this same detector with these same exclusions on 2026-09-03.

| language | n | p25 | median | p75 | p90 | p90 if one repo is dropped |
|---|---|---|---|---|---|---|
| Java | 41 | 1.62% | 2.96% | **6.04%** | 11.01% | +-0.49 |
| JS/TS | 33 | 1.31% | 2.37% | **4.53%** | 13.05% | **+-2.87** |
| Python | 38 | 1.59% | 2.62% | **5.46%** | 11.69% | **+-2.39** |
| pooled | 112 | | 2.56% | 5.63% | **11.27%** | +-0.26 |

**Warn is this language's p75. Act is the pooled p90 across all three cohorts.** The last column
is why: a single-language p90 built on thirty-odd repositories moves by up to three points when
any one of them is dropped, and an "act now" line that fragile is not a measurement. The pooled
one moves by a quarter of a point. Pooling is defensible because the three distributions sit on
top of each other - the medians are within half a point - so the tail is telling you about the
detector and about how people write code, not about the language.

Bands in use: Java 6.0 / 11.3, JS 4.5 / 11.3, Python 5.5 / 11.3. The report says which cohort
each half came from.

Three repositories are worth knowing about, because each one is a different kind of answer to
"why is this number so high":

- **Guava, 55.9%.** Its layout, not its code: it ships `guava` and `android/guava`, 602 files at
  the same relative paths with 164 of 199 sampled pairs byte-identical. This is now handled by a
  rule rather than an exception - see mirrored subtrees below - and Guava measures 4.81%.
- **moment, 50%.** Checked-in build output: `min/locales.js` is every locale file concatenated,
  and `moment.js` and `locale/` at the repository root are the compiled form of `src/`. `min/`
  is excluded now, but output at the repository root cannot be caught by a pattern, so moment is
  dropped from the cohort with that reason recorded in
  `tools/cohort-js-2026-09-03.summary.txt`. If your repository is in the same shape, use the
  per-repository exclude field.
- **fastapi, 25.1%.** Real. Its eight HTTP-verb methods each repeat the same 380-line
  `Annotated[..., Doc(...)]` parameter block by hand. It stays in the distribution.

Raw measurements and every exclusion with its reason: `tools/cohort-{java,js,py}-2026-09-03.tsv`
and the matching `.summary.txt`. Regenerate with `tools/clone-cohort.sh`,
`tools/CohortProbe.java` and `tools/cohort-stats.py`. **Replace these with your own
repositories' distribution once you have thirty or more** - a measured threshold beats a
borrowed one, and a borrowed one at least says whose it is.

## Mirrored subtrees

A repository that keeps two copies of the same tree - a second flavour of a library for another
platform, a vendored dependency, a package cloned per build target - is half a copy of itself,
and measured whole its duplication ratio describes its directory layout instead of its code.

So a subtree that holds the same relative paths as another subtree, and whose sampled files
match after normalisation, is left out of the static duplication measurement. Three things about
that:

- **It is declared.** The report opens its caveats by naming every dropped subtree, its line
  count and how identical the sample was. Quietly removing half a repository from its own
  denominator would be worse than reporting 56%.
- **The denominator moves with the numerator.** Duplication is reported over the lines actually
  measured. Taking the mirror out of one side only would halve the answer rather than fix it.
- **The copy-paste ratio still counts it.** A line added to both copies is counted as copied,
  because that is what it is: work done twice.

Firing wrongly is the failure that matters here, since it hides exactly what this tool is for -
so four of the six tests in `MirrorTreesTest` are trees that must *not* be called mirrors.

## Architecture

Everything runs inside Confluence - no worker, no external database, no Node runtime:

- **JGit**, inlined into the plugin jar, keeps a bare mirror of each repository under
  `<confluence-home>/plugin-data/code-quality/repos/`. Bare and never checked out: the analysis
  reads blobs straight from the object database.
- Analyses run on a **single background thread**. Two clones plus two full history walks at once
  on a Confluence node is how a reporting feature turns into an outage.
- Per-commit metrics are cached in Active Objects. A rerun replays only new commits plus a
  trailing window wide enough that every commit whose churn could still change is re-derived.
  The incremental path is verified to produce numbers identical to a full recompute, field for
  field, by `AnalysisEngineTest`.
- The duplicate detector and the error/connectivity scanners are Java reimplementations of what
  the proof of concept did with jscpd and tree-sitter, which a JVM plugin cannot load.
- **Every limit fails loudly.** A clone will not start with under 2GB free, a clone over 4GB is
  refused, a tree too large to index stops the run, and a history longer than 20,000 commits is
  analysed as its newest slice *and says so on the report*. The reason is the same each time: a
  number that looks right and is not costs more than a run that stops and explains itself.
- Clone directories with no repository behind them are swept after every run, which also covers
  a node killed mid-clone.

## Access

Registering, editing and analysing require Confluence administrator rights - it hands the
instance a credential and asks it to clone from the internet.

Reading is scoped by space. A repository links to one or more spaces and is visible to whoever
can view any of them, which reuses the permissions the instance already has rather than
inventing a second model: a personal repository goes in a personal space, a team's in the
team's. **A repository with no spaces linked is visible to administrators only** - these reports
carry a private codebase's file paths, commit subjects and author addresses, and the safe
reading of "not configured yet" is "not shared". Not-found and not-permitted answer alike, so
repository ids cannot be enumerated.

Access tokens are stored with AES-256-GCM under a key kept in a `0600` file in the Confluence
**shared** home - not in the database, and shared across a Data Center cluster so a failover can
still read them. Credentials pasted into the clone URL itself are moved into that field and
stripped from the URL. What this does not protect against is anyone who can read the Confluence
home, run code in the JVM, or install a plugin; Confluence Server has no secret store that hides
a key from its own administrators. Register a read-only, repository-scoped token.

Only `https://` and `ssh://` clone URLs are accepted. `file://` would read the server's own
disk, and an unchecked scheme reached the browser as a link.

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

## Tests

```bash
atlas-mvn test
```

Thirty-eight of them, and they exist because the numbers are the product. Six pin the copy-paste
classifier: scattered language idioms must not count as copying (the case that made the first
version report 13.7%), a block copied across files, a move within a file, a move across files, a
copy whose source survived, and **classification independent of the order files entered the
index**. That last one failed before the index cap was removed and passes after, which is the
only real evidence the fix worked.

Three build a repository with JGit and controlled commit dates: the 14-day censoring boundary,
churn attributed back to the commit that added the line, and a full-versus-incremental run
compared field for field - `sampled()` included, because a run that samples different commits
picks a different trend reference and reports a different delta with no change to the code.

Eight cover URL handling, where a single missing check was each of three security findings: only
https and ssh accepted, loopback and link-local refused, credentials split out of the URL, a bare
user name kept (JGit takes the ssh login from the URI), and **private ranges still allowed** -
pinned deliberately, so a later hardening pass cannot quietly close the main use case.

Four clone real repositories over the local transport to pin that the clone follows the URL. Two
of them fail against the unfixed code; the other two guard the opposite mistake, discarding a
clone that was still good.

Six cover mirrored subtrees, four of them trees that must not be treated as one.

Six decide when a commit counts as wholesale rather than as authored work - three of them
commits that must be excluded and three that must not, since the rule was narrowed after
measuring 50,429 commits and a narrowed rule is one that can now miss things.

One pins that a mis-dated commit cannot silence churn for the rest of the history, and one that
a token in the user position of an https URL is a credential rather than a login name - both
fail against the code they were written for.

Three walk every finding and caveat the report can emit, in every language, asserting that none
comes out holding an unfilled `{0}`. The wording lives in three places that have to agree - the
code the builder emits, the argument list keyed off it, and the message in each bundle - and
nothing connects them at compile time: adding a finding without its argument list put
"bus factor {0}, across {1} author(s)" on the page while everything built and rendered.

## Verification tools

Not part of the plugin; they run the engine outside Confluence.

| Tool | What it does |
|---|---|
| `tools/Probe.java` | Runs the engine on a bare clone and dumps the report JSON |
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
