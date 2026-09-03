# Where this stands

Working notes for picking the work back up. Written 2026-09-03.

The plugin is installed and running on the WSL2 Docker Confluence 7.8.1 at
`http://confluence:18090`. Deploy with `CONFLUENCE_USER=bskim CONFLUENCE_PASS=... ./deploy.sh`.

## Done since the first commit

Driven by the code review in `REVIEW-code-quality-plugin.txt` (against `203df60`), in the order
that review recommended.

### Security — all four blockers closed, verified against the running instance

| Item | What it was | What it is now |
|---|---|---|
| B-1 | `https://x-access-token:ghp_xxx@github.com/...` stored in clear and returned by REST to any logged-in user | Credentials are split out of the URL on save **and** on read, and moved into the encrypted token field. Reproduced with a fake token, then confirmed gone. |
| B-2 | No scheme check: `file:///...` cloned the server's own disk, `http://10.0.4.7:6379/` probed internal ports | `RemoteUrl` accepts https and ssh only. Loopback, link-local (cloud metadata) and wildcard hosts refused. |
| B-5 | `javascript:` URL reached the browser as an `href` | Closed by the same scheme check, plus a client-side `^https?://` guard on the link. |
| B-3 | Key in `PluginSettings` = the same database as the ciphertext; CBC with no MAC; decryption failure silently became "no token" | AES-256-GCM, key in a `0600` file under the Confluence **shared** home (so a Data Center failover can still read it), and failures raise instead of degrading. Old CBC blobs still decrypt and are re-encrypted on next save. |

**Private address ranges are deliberately still allowed** - an internal GitLab on 10.x is the
main thing this gets pointed at. The port-scanning signal was closed where it actually leaked:
`GitClient.probe` now returns a category (`notAuthorized`, `notFound`, `notGitRepository`,
`timeout`, `unreachable`) and logs the detail, instead of handing back the remote's own error.

### B-4 — reports are scoped by space

A repository links to one or more spaces and is visible to whoever can view any of them, which
reuses Confluence's own permissions instead of inventing a second model. **No spaces linked
means administrators only**, fail-closed: these reports carry private file paths, commit
subjects and author addresses, and "not configured" must not mean "world readable".

Not-found and not-permitted return the same 404, so walking `repo=1,2,3` reveals nothing.
The form has a space picker fed by `GET /repos/spaces`, and saving with nothing selected asks
for confirmation first.

**The two already-registered repositories have no spaces linked, so right now only
administrators see them.** Link a space to give a team access.

### Determinism — A-1 and A-2, the claim the README leads with

- **A-1**: `NgramIndex` capped each bucket at 64 locations, so which ones survived depended on
  insertion order - and a full run inserts files as commits touch them while an incremental run
  materialises a tree in path order. Same commit, different copy/move verdict.
  The review suggested marking saturated windows as boilerplate; that does not work, because
  whether a window counts as boilerplate then depends on the history the index went through
  rather than the tree it holds. **The cap is gone.** The index keeps every location and a tree
  too large to index fails the run with a message (`MAX_INDEX_ENTRIES`). Wrong-but-plausible is
  worse than stopping.
- **A-2**: sampling bucketed only the replayed range, so a 28-day incremental run sampled daily
  while a five-year full run sampled every ninth day - the same commit came out sampled in one
  and not the other, which moved the 90-day reference point. `sampleIndices` now takes the whole
  chain. `floorForSampledGaps` additionally pulls the replay floor back to cover any sampled
  commit whose cached row has no statics.
- `STATIC_SAMPLE_TARGET` said 40 while the call site used 40x5; it is 200 and used directly.

### A-3 — tests exist now (43, all green)

`CopyPasteClassifierTest` (6): scattered idioms are not copies (the case that made v1 report
13.7%), a six-line block copied across files, a move within a file, a move across files, a copy
whose source survived, and **classification independent of index insertion order** - that last
one failed before the A-1 fix and passes after, which is the only evidence the fix works.

`AnalysisEngineTest` (3): builds a real repository with JGit and controlled commit dates. The
14-day censoring boundary, churn attribution back to the commit that added the line, and a
full-versus-incremental comparison **field for field including `sampled()`** - the manual probe
used to skip exactly that comparison.

`RemoteUrlTest` (8) and `GitClientTest` (4): the security findings had no regression test at
all, which for a one-line check is how it comes back. Scheme and host refusal, credential
extraction, **private ranges still allowed on purpose** (pinned so a later hardening pass cannot
quietly close the product), and the D-1 behaviour above. Each of the two D-1 tests was run
against the unfixed code and fails there on the assertion it exists for; the two that guard
against *over*-discarding pass either way, which is what they are for.

Two of the engine fixtures were wrong at first and are worth remembering: a diff moves whichever side
is cheaper, so a 6-line block next to a 2-line tail reports the *tail* as the addition; and a
`previousRunAt` in the future pushes the replay floor past the end so nothing is replayed and the
test passes while checking nothing. Both now assert their own setup.

### Cohort thresholds

Python is in (`p75 5.3`, `p90 8.7`, n=19, `tools/cohort-python-2026-09-02.tsv`). Java and JS
were measured but **are not wired in yet** - see below.

### Smaller things

- Macro browser: the title was showing the raw i18n key, so the macro was unfindable. The title
  key is `<plugin-key>.<macro-name>.label` and cannot be overridden by a descriptor element.
  Icon is an **attribute** on `<xhtml-macro>` pointing at a web-resource - a child `<icon>`
  element and a download resource named `icon` are both silently ignored.

  **This is finished, and an earlier version of this file said otherwise.** The note claiming
  the browser still reported `icon: None` was written before the attribute fix and never
  re-checked. Verified from `/plugins/macrobrowser/browse-macros.action`: the icon resolves to
  80x80 with the right location, the resource returns `image/png` at 3,571 bytes, and it is
  legible at the 24px the browser list uses. Twenty-two of this instance's 85 macros declare an
  icon at all; ours is one, in the same form another plugin on the same instance uses.

  The same metadata did still hold two unresolved keys - `body.label` and `body.desc`.
  Confluence generates them whatever the body type, and this macro declares `bodyType: NONE`,
  so they were invisible. Added anyway: the macro *title* sat there unresolved once too, and
  that one was very visible. Nothing in the macro metadata reads as a raw key now.
- Row action icons now use the supplied artwork as CSS masks, so they still inherit
  `currentColor` for hover and disabled states. `MakeIcons` generates the sizes.
- Required fields marked with `*`, inline validation instead of `window.alert`.
- Probe reports whether the token was actually verified: a public repository accepts any token
  because GitHub ignores credentials it does not need, and "reachable" was reading as "the token
  is fine".
- `D-7`: a non-numeric `id` in the probe body no longer throws a 500.

### D-1 - the clone now follows the URL

`GitClient.sync` used the URL for the first clone only, so re-pointing a registration left every
later fetch going to the old remote. The run succeeded, the report rendered, the numbers were
simply another codebase's - the worst shape a bug can take on a server where a personal
repository and a company one sit side by side.

The check lives in `sync`, not at the point of edit: it compares the clone's own
`remote.origin.url` with the registration on every run, so it also repairs anything re-pointed
before the fix existed. A clone it cannot read the remote of counts as stale, and so does one
whose stored URL still carries a token - re-cloning is what removes that token from disk.

`RemoteUrl.canonical` decides "same remote" for both this and the cache drop in
`RepositoryService.update`, which have to agree: dropping the cache while keeping the clone
leaves a report built half from each. It ignores a trailing `.git`, a trailing slash, host case
and userinfo, because none of those change which repository it is - and comparing raw strings
replayed an entire history when somebody added a `.git`.

**A bare user name now stays in the URL.** Only a password is stripped. Writing the test found
that `ssh://git@github.com/...` was being reduced to `ssh://github.com/...`: JGit takes the ssh
login from the URI, not from the credentials provider, so that would have connected as whatever
account Confluence runs under and been refused. The scp-like form was never affected, which is
probably why it went unnoticed.

Verified on the running instance as well as in tests: repository 1 re-pointed from `captureV` to
this plugin's own repository moved from head `4ddb39e8` to `c210c7a9`, and back again.

### Display coherence - C-1 to C-11, all closed

Ten of them (C-6 was already done). None of these were wrong arithmetic; every one was the page
saying something the data does not support, which is worse, because it looks like a measurement.

| Item | Was | Is |
|---|---|---|
| C-1 | "+42.9%" in large type above a chip reading "no change" - the noise floor stopped the verdict, not the number | The percentage is emitted only when it clears the floor and has a baseline. The absolute change stays on the chip. |
| C-2 | A baseline of 0 duplicated lines made any growth "+100%"; a baseline of 0 calls/KLOC scored an undefined 0% as **good** | Undefined is UNKNOWN. Direction is still reported from the absolute change. |
| C-3 | Legacy boxes printed "0.0%" with no baseline - "measured, did not move" | A dimmed em-dash, titled "no comparable baseline". Deltas are boxed `Double` so null survives to the JSON. |
| C-4 | "vs 90d ago" on a baseline 300 days old, whenever no sample fell inside the window | `referenceDays` - the real gap. Reads "19일 전 대비" on the reference repository. |
| C-5 | Graded on the ratio while showing only a line count, so the verdict could not be checked | The ratio sits under the figure: "HEAD 코드의 1.34%". |
| C-7 | `dupDeltaWarn`/`dupDeltaCrit` were dead settings; +21 lines and +30,000 lines earned the same badge | `stateDupDelta` now feeds the grade. |
| C-8 | The tile said "412 clone pairs" and the table said "412" while listing 60 | "클론 412쌍 중 60쌍". Verified with a synthetic 412/60 report. |
| C-9 | One uncensored commit in a bucket of 54 drew a solid bar | Partial buckets are drawn faded over the censored hatch, and say "커밋 6개 중 2개 집계". |
| C-10 | Churn silently dropped every commit whose window was open | "관측 창 안의 커밋 4개 제외" under the figure. |
| C-11 | The bus-factor finding always claimed identity merging was doing work, including at 3 authors / 3 identities | A second code, `busFactorClean`, for when nothing was merged. |

Two things came out of doing this rather than out of the review.

**C-7's fix reintroduced the problem C-1 fixed, one level up.** Grading the direction by percentage
made captureV **critical**: 50 duplicated lines to 76 is +52%, and it is 26 lines. A percentage
may now only raise the alarm when the lines behind it are worth one - `dupDeltaCritLines`, 20x the
detector's minimum clone. It caps at warn below that. The floor stops a small base from inventing
a direction; this stops it from inventing a severity.

**A finding code with no argument list published raw `{0}` placeholders to the page** and nothing
failed - the report built, the page rendered, the sentence was simply wrong. `findingArgs` now
throws on an unknown code, and `ReportLocalizerTest` walks every code the builder can emit in
every language, asserting no unfilled placeholder and no bare message key. It fails against the
unfixed code on exactly that string.

### Second review - `203df60..791b1eb`, everything it raised

The second pass could not run the build (Maven Central 403 in that sandbox), so it read the
sources. It confirmed A-1, A-2, A-3, B-2/B-3/B-4/B-5, C-6, D-1 and D-7, agreed with the three
cohort judgements, and found seven new things. All seven are closed.

**2-1 was the serious one, and it was mine, from this morning.** Making a bare user name
"addressing" is right for ssh and wrong for https:
`https://ghp_xxxxxxxx@github.com/acme/billing.git` is a documented way to authenticate to both
GitHub and GitLab, has no colon in it, and so came straight through `carriesSecret` and `parse`
into the URL column in clear text - which is B-1 reopened, with the same REST and report-page
exposure. `parse` now splits by scheme: under ssh a password-less name stays (JGit takes the
login from the URI), under https the whole userinfo is a credential and a name with no password
is taken as the token. `carriesSecret` follows the same rule, so a clone whose stored remote
looks like this is discarded and the token leaves the disk. Verified live: registering that URL
stored `https://github.com/acme/billing.git` with `hasToken: true` and an empty user name, and
the ssh form kept its `git@`.

**2-3 and 2-4 were both consequences of removing the bucket cap**, which is worth noting: the
cap's removal was right and it moved cost somewhere else.

- 2-3: buckets grew one slot at a time, which a 64-entry cap made harmless and an uncapped
  index makes quadratic. Measured directly, one window repeated N times:
  N=10,000 97ms; 20,000 362ms; 40,000 707ms; 80,000 2,340ms - against 0-1ms with capacity
  doubling. Buckets now double and carry their length in slot 0.
- 2-4: `MAX_INDEX_ENTRIES = 3_000_000` had no basis. Measured 92-95 bytes per entry
  (200k/500k/1M, all-distinct windows), so the old ceiling was ~285MB - on the 1GB heap the
  Confluence container actually runs with, over a quarter of the instance, and an
  OutOfMemoryError is not the polite stop the cap exists to provide. The ceiling is now derived
  from the running heap at 10%: ~1M entries on 1GB, ~4M on 4GB, and roughly one entry per line
  of analysable code.

**2-2** cannot be fixed where it was claimed to be fixed. `checkHost` resolves at save time and
JGit resolves again at connect time, so a name that answers differently for the two passes
through. Closing it properly means connecting to the validated address while keeping TLS
verification pinned to the name, and getting that wrong is worse than the hole. So: the host is
re-checked before every fetch (`RemoteUrl.revalidate`, called from `AnalysisJobManager`, which
also catches rows written before scheme validation existed), and the class comment no longer
claims to defend the metadata address - that is egress policy on the node. The comment used to
promise something the code cannot do, which is worse than promising nothing.

**2-5** the key file was written with the umask's permissions and restricted afterwards.
Measured: `rw-r--r--` at the moment the key hits disk. It is now created `0600` before anything
is written, falling back to the old order only where POSIX permissions are unavailable.

**2-6** `isLoggedIn`/`isAdmin` asked SAL while the space check took its subject from
`AuthenticatedUserThreadLocal`, so on a stack where SAL sees a user and the thread local is
empty, `hasPermission(null, ...)` is an *anonymous* check - one space open to anonymous viewers
would have admitted that request. One resolution path now, from the user key SAL answered with,
and an unresolvable user is refused rather than downgraded.

**2-7** is a cost, not a defect, and is now documented on `sampleIndices`: crossing a multiple of
`STATIC_SAMPLE_TARGET` distinct days re-lays-out the sample set, which costs a full replay and
can move the trend reference by up to a day.

**E-4** took two attempts and the test is why. `Math.max` on the cutoff, as suggested, makes one
future-dated commit poison the watermark permanently; capping at the analysis clock does not
work either, because commits replay oldest-first by parentage, so while the walk sits at a
commit 100 days old "now minus the window" is still 100 days ahead of it and prunes exactly the
lines the next commit was about to churn. The window is now measured back from the latest
*believable* commit time - not after the analysis started - and never moves backwards. The
first version of the test passed against the unfixed code because the mis-dated commit sat
after the line was added rather than between the addition and the rewrite.

`tools/IncrementalProbe.java` is **deleted** rather than fixed, as the review asked.

### Duplication thresholds - measured for all three languages

Blocked on two things, and a review round added a third. All resolved by measurement.

**Guava's 55.9% was its layout, and the fix is a general rule.** It ships `guava` and
`android/guava`: 602 files at the same relative paths, 164 of 199 sampled pairs byte-identical.
Rather than special-case it, `MirrorTrees` now finds subtrees that are copies of other subtrees
- shared relative paths above a floor, a path-overlap share, and a content check on a sample -
and leaves the smaller side out of the *static duplication measurement only*. Guava drops from
55.90% to 4.81%, and the clones that remain are the ones that should:
`ForwardingBlockingDeque` in two packages, `LocalCache` against `MapMakerInternalMap`,
`CompactHashMap` against `CompactHashSet`, `ImmutableListMultimap` against
`ImmutableSetMultimap` - primitive and structural specialisation, which is duplication Java
forces on you.

Three things about that feature are deliberate. It is **declared**: the report carries a caveat
naming every dropped subtree, its line count and how identical the sample was, placed first
because it changes the denominator. The **denominator moves with the numerator** - taking mirror
lines out of one and not the other halves the answer instead of correcting it. And the
**copy-paste ratio still counts** a line added to both copies, because that is work done twice.
`MirrorTreesTest` spends four of its six tests on trees that must *not* be called mirrors,
since a false positive hides exactly what the plugin is for.

**Kotlin contamination was a probe that could not see it.** `square/moshi` measured 2 Java lines
and a LOC floor dropped it silently. Neither `Language` nor `dominantLanguage()` can help -
`.kt` is invisible to both - so `CohortProbe` now reports the dominant source *extension* over
the whole tree. It flagged five repositories: moshi and okhttp (Kotlin), `d3/d3` (a bundle
package, 93 lines), `dbt-core` (rewritten in Rust, 46 Python files left), and `sveltejs/svelte`,
which is a false alarm - 45,986 real lines of TypeScript compiler behind a majority of
`.svelte` test fixtures. Surfacing for a decision is the guard's job; deciding is not.

**A third outlier turned up: `moment` at 50%.** Checked-in build output. `min/locales.js` is
every locale file concatenated, and after excluding `min/` it was still 48% because `moment.js`
and `locale/` at the repository root are the compiled form of `src/`. Mirror detection cannot
see a copy that is one file rather than a subtree, and no general pattern catches build output
at the root. `min/`, `benchmark/` (singular - its two plurals were already there, which is how
Guava's benchmark tree stayed in), `coverage/`, `demo/` and `playground/` were added to the
exclusions; moment itself is dropped from the cohort with that reason written down. For a user's
repository in the same shape the answer is the per-repository exclude field.

**fastapi's 25.1% is real** - E-2 answered. Its eight HTTP-verb methods each repeat the same
380-line `Annotated[..., Doc(...)]` parameter block by hand. The `AnalysisConfig` comment
claiming the cohort falls to single digits was simply wrong and now says what the data says.

**E-1 closed by pooling, not by hedging.** Leave-one-out is the honest measure of "the p90 rests
on two points", so it is now computed:

| language | n | p25 | median | p75 (worst shift) | p90 (worst shift) |
|---|---|---|---|---|---|
| Java | 41 | 1.62 | 2.96 | **6.04** (0.22) | 11.01 (0.49) |
| JS/TS | 33 | 1.31 | 2.37 | **4.53** (0.20) | 13.05 (**2.87**) |
| Python | 38 | 1.59 | 2.62 | **5.46** (0.41) | 11.69 (**2.39**) |
| pooled | 112 | | 2.56 | 5.63 (0.31) | **11.27** (0.26) |

So warn is this language's p75 - stable to a fifth of a point - and act is the pooled p90 across
all 112 repositories, because a per-language p90 that one repository can move by three points is
not a measurement. Pooling is defensible here because the three medians sit within half a point
of each other. Bands: Java 6.0 / 11.3, JS 4.5 / 11.3, Python 5.5 / 11.3. The report's basis
sentence states both cohort sizes and why they differ.

The Java-tail prediction from the second review did not hold, incidentally: with mirrors handled,
Java has the *thinnest* right tail of the three.

Raw data and per-repository exclusions: `tools/cohort-{java,js,py}-2026-09-03.tsv` and the
matching `.summary.txt`. `ALGO_VERSION` is at 3, because mirror exclusion changes stored numbers.

### Operational robustness - D-2 to D-6 closed

**D-2, orphans.** Deleting a repository mid-analysis left two kinds of wreckage. On disk, the
request removed the row and discarded the clone while the analysis thread was still inside
`sync` - which does not check for cancellation - so the clone finished into a directory nobody
owned. `GitClient.discardOrphans` sweeps those after every run: a sweep rather than tighter
coordination, because a node killed during a clone leaves the same thing and no care on the
delete path covers that. In the database, `persistSuccess` wrote `CqCommit`, `CqClone` and
`CqRun` rows for a repository that was already gone; it now checks the row inside the
transaction and discards the result.

Verified live by planting a `987.git` directory and running an analysis: swept, with `1.git` and
`3.git` untouched. The first attempt at that test failed for the right reason and found a real
gap - the planted directory was root-owned, so Confluence could not delete it,
`deleteRecursively` swallowed the failure, and `discardOrphans` counted it as removed. It
reports failure now, and an orphan that cannot be deleted is logged as a warning instead of as
a success.

**D-3, sixty seconds on a request thread.** The probe made two `ls-remote` attempts at thirty
seconds each, so a host that accepts a connection and never answers held a Confluence request
thread for a minute. Fifteen seconds now - a user-interface budget, not a network one - and a
`timeout` or `unreachable` result no longer triggers the credentialed retry, because nothing
answered and a credential cannot change that. Measured against a black-hole address:
**15.1s without a token, 15.7s with one**, against 60s before.

**D-5, no ceiling anywhere.** Three, each failing loudly rather than degrading:

- A clone will not start with under 2GB free where clones are kept. That disk is the Confluence
  home, so filling it stops the instance writing attachments and indexes; it is not a reporting
  problem.
- A clone over 4GB is refused after the fetch, git offering no way to ask a remote its size in
  advance. For scale, the largest repository in the reference cohorts is about 200MB.
- `MAX_COMMITS` already bounded the walk at 20,000, which bounds the cached rows in heap and the
  rows one transaction writes as well - roughly 8MB, so the per-commit cache needs no separate
  ceiling. But it **truncated in silence**: the span, the commit count, the authors and the
  trend all described the newest slice while the report presented them as the repository. There
  is a `historyTruncated` caveat now.

**D-4** made the job claim one atomic map operation, and **D-6** asks for the `hasReport` column
once instead of per row - at forty repositories and a two-second poll that was twenty
transactions a second repeating one table scan. Both shipped with the threshold work.

### E-3 - measured, then narrowed

Marked `[estimate]` by the review, and it was right. `tools/ImportProbe.java` ran the engine
over **50,429 commits in 25 repositories with full history** - the cohort clones are shallow and
useless for this. The old rule (over 200 lines added, and more than half of what the parent
held) flagged 25 roots and 37 other commits. Reading all 37 showed two kinds of mistake.

**File splits.** "Split up click into a package" moved 1,130 of its 1,165 added lines; loguru's
`__init__.py` split, got's `index.js` split and one tqdm merge looked the same. The code was
already in the repository - this is the refactoring the report exists to reward, and it was
being excluded from the refactoring ratio. So: a commit whose added lines are mostly *moved* is
a relocation, not an arrival. No genuine import in the sample had most of its lines moved.

**Ordinary work on a small repository.** loguru's "Add rotating file handler" added 205 lines to
a 208-line tree. Four early feature-branch merges in google/auto added 296-809 lines to trees of
530-947. Half of a small tree is a normal week, so the ratio test needs a denominator worth
dividing by - a thousand lines, the same floor below which a repository is too small to put in a
threshold cohort at all.

Two escape hatches keep the real cases: a tree with less than 200 lines has no codebase to
compare against, so code arriving there is the initial body however small the repository looks
(that keeps "moved cli over from the sandbox" and "Initial code drop"), and 2,000 lines in one
commit is wholesale whatever it landed on. Between 1,000 and 2,000 the verdict on the sample
does not move, so the exact figure is not carrying the decision.

Net: 15 of the 37 reclassified, and each of the 22 that remain is a genuine wholesale event - an
initial import, a rewrite, a vendored package, or a repository-wide mechanical pass. Six tests,
half of them commits that must *not* be excluded and half that must, because a tightened rule is
one that can now miss things. The two "must not" tests fail against the old rule on exactly
their own assertions.

Two things came out of it rather than out of the review.

**The label was wrong.** A third of what the rule keeps is formatters, lint migrations and
whitespace passes - "bulk imports excluded" was a checkably untrue statement about them. It
reads "wholesale commits excluded" now, and the caveat lists what qualifies and says that moving
code between files does not.

**`MessageFormat` was eating apostrophes.** "somebody's work" reached the report as "somebodys
work", because a single quote is `MessageFormat`'s own escape character. One string was
affected; the class of fault will recur, so `tools/make-i18n.py` now refuses to generate when a
finding or caveat with a `{n}` placeholder contains a lone apostrophe. Verified by planting the
fault: exit 1, naming the key.

`ALGO_VERSION` is at 4. `CommitStats.parentLines` carries the import test's denominator so the
verdict can be audited - "added 4,124 lines to a tree of 0" is checkable, "this was an import"
is not.

### A mirror one file wide - reported, not excluded

`moment` was the case: `min/locales.js` is every locale file concatenated, and after excluding
`min/` the repository still measured 48% because `moment.js` and `locale/` at the root are the
compiled form of `src/`. `MirrorTrees` cannot see it - the copy is one file, not a subtree - and
no exclude pattern catches build output sitting at a repository root.

**The decision was to report it and not to exclude it.** A subtree mirror rests on 25 or more
shared paths, which is strong structural evidence; a single file rests on content overlap alone,
and auto-excluding on weaker evidence inverts the risk that was accepted for mirrors. The
remedy already exists one field away - the per-repository exclude patterns - and a finding that
names the file, its partner count and three of the partners lets the reader check the claim
rather than trust it. Nothing is hidden: the duplication figures still include the file, and the
finding says so.

**The discriminator is breadth, and it was measured rather than chosen.** A bundle holds the
content of dozens of files; a module somebody copied wholesale mirrors one - and that one has to
keep being reported, because it is the finding the plugin exists for. Over the 124
most-duplicated files in the 112 cohort repositories (`tools/BundleProbe.java`), clone partners
per file run:

| statistic | partners |
|---|---|
| median | 2 |
| p90 | 4 |
| p95 | 5 |
| p99 | 8 |
| highest ordinary file | 11 - svelte's generated `index.d.ts`, itself bundle-shaped |
| `moment.js` | **69** |
| `min/locales.js`, with `min/` left in | **115** |

Nothing sits between 11 and 69. Twenty is picked for being in the empty middle, not for being a
round number: typeorm's per-database query runners (8 partners) and netty's channel classes (5)
keep being reported. Run against the whole cohort through the product's own
`DuplicateDetector.bundleSuspects`, exactly one file in 112 repositories is flagged -
`moment.js` - and the three live repositories flag none.

Five tests, and four of them are shapes that must *not* be called bundles: a wholesale copy of
one module, a family of eight similar drivers, a file that merely shares a preamble widely, and
a file under the line floor.

One incident worth keeping: the render check failed with *"No argument list for finding code:
bundleFile"* because the jar was stale - `atlas-mvn test` compiles but does not repackage. That
is the guard added earlier in the day doing its job, and it is the reason it exists: without it
the page would have rendered `{0}` and looked fine at a glance.

## Next, in order

1. **A known limitation, not on the list but worth writing down.** `DuplicateDetector` keeps at
   most 16 locations per window hash, so in a tree where one block appears hundreds of times
   only the first 16 are paired and duplication is understated. It is deterministic - the walk
   is in path order - and pre-existing, but it is a real ceiling on very repetitive trees.

## Things worth knowing before touching the code

- **Active Objects from the analysis thread needs SAL's `TransactionTemplate`**, reads included,
  and entities must not be read outside it - hence `RepoSnapshot`. Both failures only appear
  after a real install.
- Bumping `AnalysisConfig.ALGO_VERSION` invalidates every cached row on purpose. It is at 2.
- `src/main/resources/code-quality*.properties` are generated by `tools/make-i18n.py`. Edit that.
- The verification probes under `tools/` are `main` classes, not tests. `IncrementalProbe` is
  **deleted**: `AnalysisEngineTest` covers it, and the probe still carried the
  `x.sampled() && y.sampled()` guard that skipped the disagreement it existed to find. A tool
  that passes for the wrong reason is worse than no tool - somebody runs it and believes it.
- **Verifying the page needs a synthetic report.** The two registered repositories cannot
  produce a truncated clone table, a mixed-censoring bucket or a missing baseline, so those
  three fixes were checked by editing a report payload and rendering it with `tools/RenderProbe`
  plus headless Chrome. Region shots: put an iframe at a negative `top` in a wrapper file next
  to the rendered page (a `data:` URL cannot load a relative `src`) and pass
  `--allow-file-access-from-files`.
- Screenshots: WSL cannot run `.exe` files unless the `WSLInterop` binfmt handler is registered.
  It is now persisted in `/usr/lib/binfmt.d/WSLInterop.conf`, but `systemd-binfmt` is what
  removes it, so check `ls /proc/sys/fs/binfmt_misc/` if Chrome stops working.
  A `file://` page cannot load CSS-referenced images, so masked icons look missing in a local
  harness and fine on the real page - screenshot Confluence, not a harness, when checking them.
