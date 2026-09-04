/*
 * What the macro's `repository` parameter accepts, in one place.
 *
 * Its own web resource because two documents need it and they are not the same document. The
 * page and the preview frame load the app; the editor loads only the macro browser override,
 * and that override was falling back to comparing the parameter against the stored name
 * exactly - so a macro whose parameter held an id, a clone URL, or a bare name for a
 * repository stored as `owner/repo` opened with nothing ticked. There was a fallback for a
 * missing matcher and the fallback was what always ran.
 *
 * Kept free of everything else - no DOM, no REST, no strings - so loading it into the editor
 * costs a few hundred bytes and cannot affect the editor's own styling or behaviour.
 */
(function (global) {
    'use strict';

    /** What every screen shows for a repository. The server derives it; this is the guard. */
    function label(repo) {
        return repo.label || repo.name;
    }

    /** Lower-cased, trimmed, without a trailing .git or slash, so pasted URLs compare. */
    function normaliseRef(value) {
        var text = String(value === undefined || value === null ? '' : value).trim()
            .toLowerCase();
        while (text.charAt(text.length - 1) === '/') {
            text = text.slice(0, -1);
        }
        if (text.slice(-4) === '.git') {
            text = normaliseRef(text.slice(0, -4));
        }
        return text;
    }

    /** `owner/repo` out of a clone URL, in either the https or the scp-like form. */
    function ownerAndRepo(url) {
        var text = normaliseRef(url);
        if (text === '') {
            return '';
        }
        var afterScheme = text.indexOf('://');
        if (afterScheme >= 0) {
            text = text.substring(afterScheme + 3);
        }
        var at = text.lastIndexOf('@');
        if (at >= 0) {
            text = text.substring(at + 1);
        }
        text = text.replace(':', '/');
        var parts = text.split('/').filter(function (part) {
            return part !== '';
        });
        return parts.length >= 3 ? parts[parts.length - 2] + '/' + parts[parts.length - 1] : '';
    }

    /**
     * Does this repository answer to what the macro was told to show?
     *
     * The parameter used to be compared to the stored name exactly, so a user had to know which
     * of two naming conventions a row happened to carry - names entered by hand when the form
     * still had a Name field, and `owner/repo` derived from the URL ever since - and pasting the
     * clone URL, the obvious guess, matched nothing at all.
     *
     * Deliberately not a substring match: `api` would answer for both `acme/api` and
     * `acme-fork/api`, and quietly showing two rows is not what "show only this one" asked for.
     */
    function identifies(repo, wanted) {
        var want = normaliseRef(wanted);
        if (want === '') {
            return false;
        }
        if (String(repo.id) === want) {
            return true;
        }
        // Both sides are reduced to whatever canonical forms they have, so any URL shape a
        // person might paste - https, browse, scp-like - lands on the same owner/repo as the
        // stored one.
        var wants = [want];
        var wantedOwnerRepo = ownerAndRepo(wanted);
        if (wantedOwnerRepo !== '') {
            wants.push(wantedOwnerRepo);
            wants.push(wantedOwnerRepo.split('/').pop());
        }

        var candidates = [normaliseRef(repo.name), normaliseRef(repo.url)];
        var fromUrl = ownerAndRepo(repo.url);
        if (fromUrl !== '') {
            candidates.push(fromUrl);
            candidates.push(fromUrl.split('/').pop());
        }
        var fromName = normaliseRef(repo.name);
        if (fromName.indexOf('/') >= 0) {
            candidates.push(fromName.split('/').pop());
        }

        for (var i = 0; i < candidates.length; i++) {
            if (candidates[i] === '') {
                continue;
            }
            for (var j = 0; j < wants.length; j++) {
                if (candidates[i] === wants[j]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The references a parameter holds, in the order written, without the blanks. */
    function refs(selection) {
        return String(selection === undefined || selection === null ? '' : selection)
            .split(',')
            .map(function (part) {
                return part.trim();
            })
            .filter(function (part) {
                return part !== '';
            });
    }

    /**
     * Is this repository one of the selected ones?
     *
     * <p>The parameter holds a comma-separated list, because the macro's picker is a
     * multi-select. An empty list selects nothing rather than everything: with a picker,
     * "nothing ticked" plainly means nothing ticked, and the alternative made the preview show
     * a full list before any choice had been made. The administration screen is the exception -
     * see {@code data-context} - because listing everything is what that screen is for.</p>
     */
    function matchesSelection(repo, selection) {
        var wanted = refs(selection);
        for (var i = 0; i < wanted.length; i++) {
            if (identifies(repo, wanted[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The references in a selection that name none of the repositories given.
     *
     * <p>The macro browser needs this to avoid destroying data. Its picker rewrites the
     * parameter from the ticked boxes, and the list it ticks from is `GET /repos`, which is
     * filtered by permission. An editor who can see repository A but not B, opening a macro
     * that names both, would tick A, save "A", and drop B without a word - no error, no
     * warning, nothing on screen to notice. A reference that resolves to nothing visible is
     * not a reference to nothing.</p>
     *
     * <p>Returned as written rather than normalised, because they are about to be written back
     * into the parameter and the account that can see them should find them unchanged.</p>
     */
    function unresolved(repos, selection) {
        var list = repos || [];
        return refs(selection).filter(function (ref) {
            for (var i = 0; i < list.length; i++) {
                if (identifies(list[i], ref)) {
                    return false;
                }
            }
            return true;
        });
    }

    global.CqRepoMatch = {
        identifies: identifies,
        label: label,
        matchesSelection: matchesSelection,
        normaliseRef: normaliseRef,
        ownerAndRepo: ownerAndRepo,
        refs: refs,
        unresolved: unresolved
    };
}(typeof window !== 'undefined' ? window : this));
