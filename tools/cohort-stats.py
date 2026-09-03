#!/usr/bin/env python3
"""Turns CohortProbe output into a threshold band, with every exclusion written down.

    tools/cohort-stats.py /tmp/cohort3-java.tsv java

A repository is dropped only for a reason this prints. The reason belongs in the output
because a cohort that quietly shrinks reports an `n` it does not have - and `n` is the whole
basis for saying a threshold is measured rather than guessed.
"""
import sys

MIN_LOC = 1000

# Repositories dropped by hand, and why. Nothing else is removed.
DROP = {
    "square_moshi": "Kotlin project: 2 Java lines",
    "square_okhttp": "Kotlin project: 129 Java lines",
    "d3_d3": "bundle package that re-exports submodules: 93 lines",
    "dbt-labs_dbt-core": "rewritten in Rust: 46 Python files remain",
    "moment_moment": "ships its build output at the repository root - moment.js and locale/ "
                     "are the compiled form of src/ - so the ratio measures its release "
                     "process. No general exclude pattern can catch output at the root, and "
                     "loosening the mirror thresholds enough to match a UMD wrapper would "
                     "start swallowing real modules.",
}

EXT_OK = {
    "java": {"java"},
    "js": {"js", "jsx", "mjs", "cjs", "ts", "tsx", "svelte", "vue"},
    "py": {"py", "pyi"},
}


def percentile(values, q):
    """Linear interpolation between order statistics, as numpy and Excel do it."""
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = (len(ordered) - 1) * q
    low = int(pos)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (pos - low)


def main():
    path, key = sys.argv[1], sys.argv[2]
    rows, dropped = [], []
    with open(path, encoding="utf-8") as handle:
        header = handle.readline().rstrip("\n").split("\t")
        for line in handle:
            cells = line.rstrip("\n").split("\t")
            if len(cells) < len(header):
                continue
            row = dict(zip(header, cells))
            name = row["repo"]
            if row.get("loc") in ("ERROR", None):
                dropped.append((name, "probe failed"))
                continue
            loc, ratio = int(row["loc"]), float(row["ratioPct"])
            if name in DROP:
                dropped.append((name, DROP[name]))
                continue
            if loc < MIN_LOC:
                dropped.append((name, "under %d measured lines (%d)" % (MIN_LOC, loc)))
                continue
            if row.get("topExt") and row["topExt"] not in EXT_OK[key]:
                dropped.append((name, "dominant extension .%s, kept anyway" % row["topExt"]))
            rows.append((name, loc, ratio, row.get("mirrors", "-")))

    ratios = [r[2] for r in rows]
    print("== %s: n=%d" % (key, len(rows)))
    for name, reason in dropped:
        print("   dropped %-32s %s" % (name, reason))
    for name, loc, ratio, mirrors in rows:
        if mirrors and mirrors != "-":
            print("   mirror  %-32s %s" % (name, mirrors))
    print("   min %.2f  p25 %.2f  median %.2f  p75 %.2f  p80 %.2f  p85 %.2f  p90 %.2f  max %.2f"
          % (min(ratios), percentile(ratios, .25), percentile(ratios, .5),
             percentile(ratios, .75), percentile(ratios, .80), percentile(ratios, .85),
             percentile(ratios, .90), max(ratios)))
    top = sorted(rows, key=lambda r: -r[2])[:6]
    print("   right tail: " + ", ".join("%s %.1f%%" % (n, r) for n, _, r, _ in top))

    # How much a band moves if any single repository turns out not to belong. This is the
    # honest answer to "the p90 rests on two points": a number, not a reassurance.
    for label, q in (("p75", .75), ("p85", .85), ("p90", .90)):
        base = percentile(ratios, q)
        worst, culprit = 0.0, ""
        for i, row in enumerate(rows):
            without = percentile(ratios[:i] + ratios[i + 1:], q)
            if abs(without - base) > worst:
                worst, culprit = abs(without - base), row[0]
        print("   %s %.2f  leave-one-out worst shift %+.2f (%s)"
              % (label, base, worst, culprit))


if __name__ == "__main__":
    main()
