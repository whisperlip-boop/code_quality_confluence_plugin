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

### A-3 — tests exist now (26, all green)

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

## Next, in order

1. **Java and JS thresholds.** Measured but withheld:

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
2. **E-1 - p90 rests on two points** in all three languages. Consider a more stable statistic for
   the "act" band, or a larger cohort, and show `n` in the UI.
3. **E-2 - a comment that disagrees with its data.** `AnalysisConfig` says excluding tests and
   generated tables brings the cohort into single digits; fastapi is still 25.1% and httpx 14.1%.
   Both look like real duplication, so the comment should change - but fastapi barely moved when
   `docs_src` was excluded and is worth a look.
4. **D-2 to D-6** from the review: operational robustness. Orphans left by deleting during a
   run, up to 60 seconds blocking a request thread, the check-then-act race in `submit()`, no
   ceiling on clone size, and an N+1 transaction behind the repository list.
5. **E-3**: bulk-import detection may over-fire on a young repository. E-4 is closed.

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
