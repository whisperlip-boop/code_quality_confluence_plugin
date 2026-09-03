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

### A-3 — tests exist now (9, all green)

`CopyPasteClassifierTest` (6): scattered idioms are not copies (the case that made v1 report
13.7%), a six-line block copied across files, a move within a file, a move across files, a copy
whose source survived, and **classification independent of index insertion order** - that last
one failed before the A-1 fix and passes after, which is the only evidence the fix works.

`AnalysisEngineTest` (3): builds a real repository with JGit and controlled commit dates. The
14-day censoring boundary, churn attribution back to the commit that added the line, and a
full-versus-incremental comparison **field for field including `sampled()`** - the manual probe
used to skip exactly that comparison.

Two of these fixtures were wrong at first and are worth remembering: a diff moves whichever side
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
- Row action icons now use the supplied artwork as CSS masks, so they still inherit
  `currentColor` for hover and disabled states. `MakeIcons` generates the sizes.
- Required fields marked with `*`, inline validation instead of `window.alert`.
- Probe reports whether the token was actually verified: a public repository accepts any token
  because GitHub ignores credentials it does not need, and "reachable" was reading as "the token
  is fine".
- `D-7`: a non-numeric `id` in the probe body no longer throws a 500.

## Next, in order

1. **D-1 - a changed URL does not move the clone.** `GitClient` uses the URL for the first clone
   only; afterwards it fetches from the existing `origin`. `RepositoryService.update` drops the
   cache but never calls `gitClient.discard()`. Re-point a repository and the screen says one
   thing while the content is the other, permanently. This matters more than its position in the
   review suggests, because personal and company repositories are going to sit side by side on
   the real server. Fix by discarding on `remoteChanged`, or by rewriting `remote.origin.url`
   before the fetch. Pin it with a test.
2. **Java and JS thresholds.** Measured but withheld:

   | language | n | p25 | p50 | p75 | p90 |
   |---|---|---|---|---|---|
   | Python | 19 | 1.22 | 2.66 | 5.32 | 8.74 |
   | Java | 18 | 2.11 | 3.67 | 6.41 | 12.20 |
   | JS/TS | 17 | 0.88 | 2.97 | 4.64 | 11.83 |

   Two things to settle first. `google_guava` measured **55.9%** (114,898 duplicated lines in
   205k LOC) and needs looking at before it goes into a distribution. And the Java cohort list
   contains Kotlin projects - `square_moshi` came out at 2 LOC, `square_okhttp` at 129 - which
   should be replaced rather than silently dropped by the 1000-LOC filter.
   Raw data: `/tmp/cohort-java.tsv`, `/tmp/cohort-js.tsv` (regenerate with
   `tools/clone-cohort.sh` plus `tools/CohortProbe.java`).
3. **E-1 - p90 rests on two points** in all three languages. Consider a more stable statistic for
   the "act" band, or a larger cohort, and show `n` in the UI.
4. **E-2 - a comment that disagrees with its data.** `AnalysisConfig` says excluding tests and
   generated tables brings the cohort into single digits; fastapi is still 25.1% and httpx 14.1%.
   Both look like real duplication, so the comment should change - but fastapi barely moved when
   `docs_src` was excluded and is worth a look.
5. **C-1 to C-11, D-2 to D-6** from the review: display coherence and operational robustness.
   C-6 (a hard-coded cohort size of 19 that would misreport a custom threshold's provenance) is
   already fixed; the rest are open.
6. **E-3, E-4**: bulk-import detection may over-fire on a young repository; `ChurnTracker` prunes
   on the current commit's timestamp, so one future-dated commit empties it.

## Things worth knowing before touching the code

- **Active Objects from the analysis thread needs SAL's `TransactionTemplate`**, reads included,
  and entities must not be read outside it - hence `RepoSnapshot`. Both failures only appear
  after a real install.
- Bumping `AnalysisConfig.ALGO_VERSION` invalidates every cached row on purpose. It is at 2.
- `src/main/resources/code-quality*.properties` are generated by `tools/make-i18n.py`. Edit that.
- The verification probes under `tools/` are `main` classes, not tests. `IncrementalProbe` has
  been superseded by `AnalysisEngineTest` and its `x.sampled() && y.sampled()` guard is the hole
  the review found; the tool still has it.
- Screenshots: WSL cannot run `.exe` files unless the `WSLInterop` binfmt handler is registered.
  It is now persisted in `/usr/lib/binfmt.d/WSLInterop.conf`, but `systemd-binfmt` is what
  removes it, so check `ls /proc/sys/fs/binfmt_misc/` if Chrome stops working.
  A `file://` page cannot load CSS-referenced images, so masked icons look missing in a local
  harness and fine on the real page - screenshot Confluence, not a harness, when checking them.
