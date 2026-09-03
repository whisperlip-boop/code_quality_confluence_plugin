/*
 * Repository table for the code-quality macro and the standalone admin page.
 *
 * Plain DOM on purpose: this same file is loaded as a Confluence web-resource and inlined into
 * a page that has no AUI, so it cannot depend on AJS being there. Labels arrive with the list
 * response rather than from a client-side bundle, for the same reason.
 */
(function (global) {
    'use strict';

    var STRINGS = {};
    var CAN_MANAGE = false;
    var POLL_MS = 2000;

    function contextPath() {
        if (window.AJS && typeof AJS.contextPath === 'function') {
            return AJS.contextPath();
        }
        var path = window.location.pathname;
        var marker = path.indexOf('/plugins/servlet');
        return marker >= 0 ? path.substring(0, marker) : '';
    }

    var API = contextPath() + '/rest/code-quality/1.0/repos';

    /**
     * True when this is the macro browser's preview pane.
     *
     * <p>The preview renders in an iframe the macro browser creates as
     * {@code name="macro-browser-preview-frame"}, and our script runs inside it against the
     * same session - so the Delete button in a preview really deletes. That alone settles what
     * a preview should contain. It also answers the reasonable complaint that a preview has no
     * business showing when the last analysis ran: what the parameter controls is which
     * repositories appear, and that is what the preview is for.</p>
     */
    var IN_MACRO_PREVIEW = (function () {
        try {
            if (window.name === 'macro-browser-preview-frame') {
                return true;
            }
            return !!(window.frameElement && window.frameElement.id === 'macro-preview-iframe');
        } catch (ignored) {
            // A cross-origin frame cannot be asked; the page rendering is the safe default.
            return false;
        }
    }());

    /**
     * Does this repository answer to what the macro was told to show?
     *
     * The macro's `repository` parameter used to be compared to the stored name exactly, so a
     * user had to know which of two naming conventions a row happened to carry - names entered
     * by hand when the form still had a Name field, and `owner/repo` derived from the URL ever
     * since - and pasting the clone URL, the obvious guess, matched nothing at all.
     *
     * Deliberately not a substring match: `api` would answer for both `acme/api` and
     * `acme-fork/api`, and quietly showing two rows is not what "show only this one" asked for.
     */
    /**
     * Is this repository one of the selected ones?
     *
     * <p>The parameter holds a comma-separated list, because the macro's picker is a
     * multi-select. An empty list selects nothing rather than everything: with a picker,
     * "nothing ticked" plainly means nothing ticked, and the alternative made the preview show
     * a full list before any choice had been made. The administration screen is the exception -
     * see {@code data-context} - because listing everything is what that screen is for.</p>
     */
    /** What every screen shows for a repository. The server derives it; this is the guard. */
    function label(repo) {
        return repo.label || repo.name;
    }

    function matchesSelection(repo, selection) {
        var refs = String(selection === undefined || selection === null ? '' : selection)
            .split(',');
        for (var i = 0; i < refs.length; i++) {
            if (identifies(repo, refs[i])) {
                return true;
            }
        }
        return false;
    }

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

    function text(key, args) {
        var value = STRINGS[key];
        if (value === undefined || value === null) {
            return key;
        }
        if (!args) {
            return value;
        }
        return value.replace(/\{(\d+)\}/g, function (match, index) {
            var replacement = args[Number(index)];
            return replacement === undefined ? match : String(replacement);
        });
    }

    function request(method, url, body) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            xhr.open(method, url, true);
            xhr.setRequestHeader('Accept', 'application/json');
            if (body !== undefined) {
                xhr.setRequestHeader('Content-Type', 'application/json');
            }
            // Confluence rejects state-changing REST calls from the browser without this.
            xhr.setRequestHeader('X-Atlassian-Token', 'no-check');
            xhr.onload = function () {
                var parsed = null;
                try {
                    parsed = xhr.responseText ? JSON.parse(xhr.responseText) : null;
                } catch (e) {
                    parsed = null;
                }
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve(parsed);
                } else {
                    var failure = new Error(parsed && parsed.error ? parsed.error
                        : 'HTTP ' + xhr.status);
                    if (parsed && parsed.reason) {
                        failure.reason = parsed.reason;
                    }
                    reject(failure);
                }
            };
            xhr.onerror = function () {
                reject(new Error(text('ui.probeNetwork')));
            };
            xhr.send(body === undefined ? null : JSON.stringify(body));
        });
    }

    function el(tag, attrs, children) {
        var node = document.createElement(tag);
        if (attrs) {
            Object.keys(attrs).forEach(function (name) {
                var value = attrs[name];
                if (value === null || value === undefined || value === false) {
                    return;
                }
                if (name === 'class') {
                    node.className = value;
                } else if (name === 'text') {
                    node.textContent = value;
                } else if (name.indexOf('on') === 0) {
                    node.addEventListener(name.substring(2), value);
                } else if (value === true) {
                    node.setAttribute(name, '');
                } else {
                    node.setAttribute(name, value);
                }
            });
        }
        (children || []).forEach(function (child) {
            if (child) {
                node.appendChild(child);
            }
        });
        return node;
    }

    var PLUGIN_KEY = 'co.bskim.confluence.code-quality';
    var ICON_BASE = contextPath() + '/download/resources/' + PLUGIN_KEY
        + ':code-quality-resources/images/';

    /*
     * The icons are single-colour silhouettes, so they are painted as CSS masks rather than
     * dropped in as <img>. That keeps the behaviour the inline SVGs had - the glyph inherits
     * currentColor, so it dims when the button is disabled and turns blue (or red, for delete)
     * on hover - which an <img> cannot do. Where masks are unsupported the image is shown
     * as-is: the original artwork colour, still perfectly legible.
     */
    var MASK_SUPPORTED = (function () {
        var style = document.createElement('span').style;
        return 'maskImage' in style || 'webkitMaskImage' in style;
    }());

    function icon(name) {
        var url = ICON_BASE + name + '-icon.png';
        var glyph = el('span', { 'class': 'cq-icon', 'aria-hidden': 'true' });
        if (MASK_SUPPORTED) {
            glyph.style.webkitMaskImage = 'url("' + url + '")';
            glyph.style.maskImage = 'url("' + url + '")';
        } else {
            glyph.classList.add('cq-icon-plain');
            glyph.style.backgroundImage = 'url("' + url + '")';
        }
        return glyph;
    }

    function iconButton(name, label, handler, options) {
        var opts = options || {};
        return el('button', {
            'class': 'cq-icon-btn' + (opts.danger ? ' cq-danger' : ''),
            type: 'button',
            title: label,
            'aria-label': label,
            disabled: opts.disabled ? true : null,
            onclick: handler
        }, [icon(name)]);
    }

    /*
     * Only http(s) reaches an href. An unvalidated scheme here was stored XSS: an administrator
     * saving a javascript: URL fired it for every reader of the table.
     */
    function linkable(url) {
        return /^https?:\/\//i.test(url || '');
    }

    function relativeTime(millis) {
        if (!millis) {
            return text('ui.never');
        }
        var deltaSeconds = Math.round((millis - Date.now()) / 1000);
        if (window.Intl && Intl.RelativeTimeFormat) {
            var units = [
                ['year', 31536000], ['month', 2592000], ['day', 86400],
                ['hour', 3600], ['minute', 60], ['second', 1]
            ];
            var formatter = new Intl.RelativeTimeFormat(document.documentElement.lang || 'en',
                { numeric: 'auto' });
            for (var i = 0; i < units.length; i++) {
                var span = units[i][1];
                if (Math.abs(deltaSeconds) >= span || units[i][0] === 'second') {
                    return formatter.format(Math.round(deltaSeconds / span), units[i][0]);
                }
            }
        }
        return new Date(millis).toLocaleString();
    }

    // ---------------------------------------------------------------- rendering

    function App(root) {
        this.root = root;
        // 'admin' lists everything and manages it; 'macro' shows a chosen few, read-mostly.
        this.context = root.getAttribute('data-context') === 'admin' ? 'admin' : 'macro';
        this.only = (root.getAttribute('data-only') || '').trim();
        this.heading = (root.getAttribute('data-title') || '').trim();
        this.repos = [];
        /** Every name the server offered, before this macro's filter narrowed it. */
        this.available = [];
        this.editing = null;
        this.pollTimer = null;
    }

    App.prototype.start = function () {
        var self = this;
        this.load().catch(function (error) {
            self.root.textContent = '';
            self.root.appendChild(el('div', {
                'class': 'cq-app-error',
                text: text('ui.error', [error.message])
            }));
        });
    };

    App.prototype.load = function () {
        var self = this;
        return request('GET', API).then(function (payload) {
            STRINGS = payload.strings || STRINGS;
            CAN_MANAGE = !!payload.canManage;
            self.repos = payload.repos || [];
            if (self.context !== 'admin') {
                // Kept so the empty case can say what it filtered and out of how many, which
                // is the difference between "you asked for something that is not here" and
                // "nothing is registered". Those used to look identical.
                self.available = self.repos.map(function (repo) {
                    return label(repo);
                });
                self.repos = self.repos.filter(function (repo) {
                    return matchesSelection(repo, self.only);
                });
            }
            self.render();
            self.schedulePoll();
        });
    };

    App.prototype.render = function () {
        this.root.textContent = '';
        this.root.appendChild(this.editing ? this.renderForm() : this.renderTable());
    };

    App.prototype.renderTable = function () {
        var self = this;
        // Two columns in the preview, five on a page - see renderPreviewRow.
        var columns = IN_MACRO_PREVIEW
            ? ['name', 'url']
            : ['name', 'url', 'sync', 'status', 'actions'];
        var cols = el('colgroup', null, columns.map(function (name) {
            return el('col', { 'class': 'cq-col-' + name });
        }));
        var headings = [
            el('th', { text: text('ui.header.name') }),
            el('th', { text: text('ui.header.url') })
        ];
        if (!IN_MACRO_PREVIEW) {
            headings.push(el('th', { text: text('ui.header.lastSync') }));
            headings.push(el('th', { text: text('ui.header.status') }));
            headings.push(el('th', {
                text: text('ui.header.actions'), style: 'text-align:right'
            }));
        }
        var head = el('thead', null, [el('tr', null, headings)]);

        var body = el('tbody', null, this.repos.map(function (repo) {
            return IN_MACRO_PREVIEW ? self.renderPreviewRow(repo) : self.renderRow(repo);
        }));

        var children = [
            el('h3', { 'class': 'cq-panel-title',
                text: this.heading || text('ui.repositories') })
        ];

        if (this.repos.length === 0 && this.context !== 'admin' && this.only === '') {
            // Nothing picked. The macro browser disables Save while the parameter is empty, so
            // this is a macro saved before the parameter became required, or storage edited by
            // hand - either way the fix is to open the macro and choose.
            children.push(el('p', {
                'class': 'cq-empty',
                text: IN_MACRO_PREVIEW ? text('ui.pickInDialog') : text('ui.noneSelected')
            }));
        } else if (this.repos.length === 0 && this.only) {
            // Registered-and-filtered-out is not the same as not-registered, and saying the
            // second when the first is true sends the reader off to add a repository that is
            // already there. The names are listed because otherwise there is nothing on screen
            // to tell them what would have worked.
            children.push(el('p', { 'class': 'cq-empty' }, [
                el('span', { text: text('ui.filterNoMatch',
                    [this.only, this.available.length]) }),
                this.available.length
                    ? el('span', { 'class': 'cq-filter-list',
                        text: text('ui.filterAvailable', [this.available.join(', ')]) })
                    : null
            ]));
        } else if (this.repos.length === 0) {
            children.push(el('p', {
                'class': 'cq-empty',
                text: CAN_MANAGE ? text('ui.emptyAdmin') : text('ui.empty')
            }));
        } else {
            children.push(el('table', { 'class': 'cq-table' }, [cols, head, body]));
        }

        var bar = [];
        if (CAN_MANAGE && this.context === 'admin') {
            bar.push(el('button', {
                'class': 'cq-btn cq-btn-primary',
                type: 'button',
                text: text('ui.new'),
                onclick: function () {
                    self.editing = { id: 0, name: '', url: '', branch: '', authUser: '',
                        hasToken: false, excludes: '' };
                    self.render();
                }
            }));
        }
        if (bar.length) {
            children.push(el('div', { 'class': 'cq-bar' }, bar));
        }
        children.push(el('p', {
            'class': 'cq-note',
            text: IN_MACRO_PREVIEW ? text('ui.previewNote')
                : (CAN_MANAGE ? text('ui.deterministic')
                    : text('ui.deterministic') + ' ' + text('ui.adminOnly'))
        }));

        return el('div', { 'class': 'cq-panel' }, children);
    };

    /**
     * One row as the preview shows it: which repository, and where it is cloned from.
     *
     * <p>No actions, because a live Delete button in a preview is a hazard rather than a
     * feature, and no last-analysed time or status, because neither is something the macro's
     * parameters decide. The preview answers "which repositories will this show", which is the
     * only question the dialog can change the answer to.</p>
     */
    App.prototype.renderPreviewRow = function (repo) {
        var nameCell = [el('span', { 'class': 'cq-name', text: label(repo) })];
        if (repo.branch) {
            nameCell.push(el('span', { 'class': 'cq-branch', text: repo.branch }));
        }
        return el('tr', null, [
            el('td', null, nameCell),
            el('td', { 'class': 'cq-url', title: repo.url, text: repo.url })
        ]);
    };

    App.prototype.renderRow = function (repo) {
        var self = this;
        var running = repo.status === 'RUNNING' || repo.status === 'QUEUED';

        var statusCell = [el('span', {
            'class': 'cq-badge cq-badge-' + (repo.status || 'NEW'),
            text: text('ui.status.' + (repo.status || 'NEW'))
        })];
        if (running && repo.progress) {
            statusCell.push(el('span', { 'class': 'cq-progress', text: repo.progress }));
        }
        if (repo.status === 'FAILED' && repo.statusMessage) {
            statusCell.push(el('span', {
                'class': 'cq-fail-message', title: repo.statusMessage,
                text: repo.statusMessage
            }));
        }

        // On a page: analyse, report, delete. Registering and editing belong to the
        // administration screen - a page is content, and having two places to configure the
        // same thing is two places for the permissions to drift apart.
        var onAdminScreen = this.context === 'admin';
        var actions = [];
        if (CAN_MANAGE) {
            actions.push(iconButton('analyze', text('ui.analyze'), function () {
                self.analyze(repo);
            }, { disabled: running }));
        }
        actions.push(iconButton('report', repo.hasReport ? text('ui.report')
            : text('ui.report') + ' - ' + text('ui.noReport'), function () {
            window.open(contextPath() + '/plugins/servlet/code-quality/report?repo='
                + encodeURIComponent(repo.id), '_blank', 'noopener');
        }, { disabled: !repo.hasReport }));
        if (CAN_MANAGE && onAdminScreen) {
            actions.push(iconButton('edit', text('ui.edit'), function () {
                self.editing = {
                    id: repo.id, name: repo.name, url: repo.url, branch: repo.branch,
                    authUser: repo.authUser, hasToken: repo.hasToken,
                    excludes: repo.excludes
                };
                self.render();
            }));
        }
        if (CAN_MANAGE) {
            actions.push(iconButton('delete', text('ui.delete'), function () {
                if (window.confirm(text('ui.confirmDelete', [label(repo)]))) {
                    self.remove(repo);
                }
            }, { danger: true }));
        }

        var nameCell = [el('span', { 'class': 'cq-name', text: label(repo) })];
        if (repo.branch) {
            nameCell.push(el('span', { 'class': 'cq-branch', text: repo.branch }));
        }
        // Visibility is a property worth seeing at a glance - on the screen where somebody
        // can act on it. On a page it is just noise beside the repository's name.
        var spaces = onAdminScreen
            ? (repo.spaceKeys || '').split(',').filter(function (k) {
                return k !== '';
            })
            : [];
        if (spaces.length === 0 && onAdminScreen) {
            nameCell.push(el('span', {
                'class': 'cq-scope cq-scope-private', text: text('ui.spacesAdminOnly')
            }));
        } else {
            spaces.forEach(function (key) {
                nameCell.push(el('span', { 'class': 'cq-scope', text: key }));
            });
        }

        return el('tr', null, [
            el('td', null, nameCell),
            el('td', { 'class': 'cq-url', title: repo.url }, [
                linkable(repo.url)
                    ? el('a', { href: repo.url, target: '_blank', rel: 'noopener noreferrer',
                        text: repo.url })
                    : el('span', { text: repo.url })
            ]),
            el('td', { 'class': 'cq-sync', text: relativeTime(repo.lastSyncedAt) }),
            el('td', null, statusCell),
            el('td', null, [el('div', { 'class': 'cq-row-actions' }, actions)])
        ]);
    };

    App.prototype.renderForm = function () {
        var self = this;
        var draft = this.editing;
        var fields = {};
        var errors = {};

        function field(key, name, type, value, hint, required) {
            var input = el(type === 'textarea' ? 'textarea' : 'input',
                type === 'textarea' ? null : { type: type });
            input.value = value || '';
            if (required) {
                // aria-required carries it for assistive tech; the asterisk is hidden from it
                // so the label is not read as "Repository URL star".
                input.setAttribute('aria-required', 'true');
            }
            fields[name] = input;

            var label = el('label', null, [
                document.createTextNode(text('ui.form.' + key)),
                required
                    ? el('span', { 'class': 'cq-req', 'aria-hidden': 'true', text: ' *' })
                    : null
            ]);
            var error = el('p', { 'class': 'cq-error', role: 'alert', hidden: true });
            errors[name] = error;

            return el('div', { 'class': 'cq-field' }, [
                label,
                input,
                error,
                hint ? el('p', { 'class': 'cq-hint', text: hint }) : null
            ]);
        }

        function clearError(name) {
            errors[name].hidden = true;
            fields[name].classList.remove('cq-invalid');
        }

        function showError(name, message) {
            errors[name].textContent = message;
            errors[name].hidden = false;
            fields[name].classList.add('cq-invalid');
            fields[name].focus();
        }

        var probeNote = el('p', { 'class': 'cq-probe' });

        var selected = (draft.spaceKeys || '').split(',').filter(function (k) {
            return k !== '';
        });
        var spacePicker = el('select', { multiple: true, size: 6, 'class': 'cq-spaces' });
        fields.spaceKeys = spacePicker;
        var spaceNote = el('p', { 'class': 'cq-hint', text: text('ui.form.spacesLoading') });
        request('GET', API + '/spaces').then(function (spaces) {
            spacePicker.textContent = '';
            (spaces || []).forEach(function (space) {
                var option = el('option', {
                    value: space.key,
                    text: space.name + '  (' + space.key + ')'
                });
                option.selected = selected.indexOf(space.key) >= 0;
                spacePicker.appendChild(option);
            });
            spaceNote.textContent = (spaces || []).length === 0
                ? text('ui.form.spacesNone') : text('ui.form.spacesHint');
        }).catch(function () {
            spaceNote.textContent = text('ui.form.spacesHint');
        });

        // Only what the common case needs: the URL, which branch, and a token for a private
        // repository. Name is derived from the URL and User name is ignored by GitHub, so both
        // sit under Advanced - offered up front they read as required and invite a wrong guess.
        var form = el('div', { 'class': 'cq-form' }, [
            el('h3', { 'class': 'cq-panel-title',
                text: draft.id ? text('ui.form.title.edit') : text('ui.form.title.new') }),
            el('p', { 'class': 'cq-required-note', text: text('ui.form.required') }),
            field('url', 'url', 'text', draft.url, text('ui.form.urlHint'), true),
            el('div', { 'class': 'cq-field-row' }, [
                field('branch', 'branch', 'text', draft.branch, text('ui.form.branchHint')),
                field('token', 'token', 'password', '',
                    draft.hasToken ? text('ui.form.tokenKeep') : text('ui.form.tokenHint'))
            ]),
            probeNote,
            el('div', { 'class': 'cq-field' }, [
                el('label', null, [
                    document.createTextNode(text('ui.form.spaces')),
                    el('span', { 'class': 'cq-req', 'aria-hidden': 'true', text: ' *' })
                ]),
                spacePicker,
                errors.spaceKeys = el('p', { 'class': 'cq-error', role: 'alert', hidden: true }),
                spaceNote
            ]),
            el('details', { 'class': 'cq-details', open: draft.id ? true : null }, [
                el('summary', { text: text('ui.form.advanced') }),
                el('div', { 'class': 'cq-field-row' }, [
                    field('name', 'name', 'text', draft.name, text('ui.form.nameHint')),
                    field('authUser', 'authUser', 'text', draft.authUser,
                        text('ui.form.authUserHint'))
                ]),
                field('excludes', 'excludes', 'textarea', draft.excludes,
                    text('ui.form.excludesHint'))
            ]),
            el('div', { 'class': 'cq-bar' }, [
                el('button', {
                    'class': 'cq-btn cq-btn-primary', type: 'button', text: text('ui.save'),
                    onclick: function () {
                        clearError('url');
                        clearError('spaceKeys');
                        if (fields.url.value.trim() === '') {
                            showError('url', text('ui.form.urlRequired'));
                            return;
                        }
                        // Fail-closed is only defensible if the person is told, so an empty
                        // selection is confirmed rather than silently accepted.
                        var anySpace = false;
                        for (var i = 0; i < fields.spaceKeys.options.length; i++) {
                            anySpace = anySpace || fields.spaceKeys.options[i].selected;
                        }
                        if (!anySpace && !window.confirm(text('ui.form.spacesEmptyConfirm'))) {
                            return;
                        }
                        self.save(draft, fields, function (message, field) {
                            showError(field || 'url', message);
                        });
                    }
                }),
                el('button', {
                    'class': 'cq-btn', type: 'button', text: text('ui.test'),
                    onclick: function () {
                        clearError('url');
                        if (fields.url.value.trim() === '') {
                            showError('url', text('ui.form.urlRequired'));
                            return;
                        }
                        setProbe(probeNote, 'note', text('ui.probing'));
                        request('POST', API + '/probe', {
                            id: draft.id,
                            url: fields.url.value.trim(),
                            authUser: fields.authUser.value.trim(),
                            token: fields.token.value
                        }).then(function (result) {
                            describeProbe(probeNote, result);
                        }).catch(function (error) {
                            if (error.reason) {
                                clearError('url');
                                showError('url', text('ui.urlError.' + error.reason));
                                probeNote.className = 'cq-probe';
                                probeNote.textContent = '';
                                return;
                            }
                            setProbe(probeNote, 'fail', text('ui.probeNetwork'));
                        });
                    }
                }),
                el('button', {
                    'class': 'cq-btn cq-btn-link', type: 'button', text: text('ui.cancel'),
                    onclick: function () {
                        self.editing = null;
                        self.render();
                    }
                })
            ])
        ]);
        return form;
    };

    // ---------------------------------------------------------------- actions

    App.prototype.save = function (draft, fields, onUrlRejected) {
        var self = this;
        var chosen = [];
        var options = fields.spaceKeys.options;
        for (var i = 0; i < options.length; i++) {
            if (options[i].selected) {
                chosen.push(options[i].value);
            }
        }
        var body = {
            name: fields.name.value.trim(),
            url: fields.url.value.trim(),
            branch: fields.branch.value.trim(),
            authUser: fields.authUser.value.trim(),
            spaceKeys: chosen.join(','),
            excludes: fields.excludes.value
        };
        // An untouched password box on an existing repository must not clear the stored token.
        var typed = fields.token.value;
        if (typed !== '' || !draft.hasToken) {
            body.token = typed;
        }

        var call = draft.id
            ? request('PUT', API + '/' + draft.id, body)
            : request('POST', API, body);

        call.then(function () {
            self.editing = null;
            return self.load();
        }).catch(function (error) {
            if (error.reason === 'unknownSpaces') {
                onUrlRejected(text('ui.urlError.unknownSpaces'), 'spaceKeys');
                return;
            }
            if (error.reason) {
                onUrlRejected(text('ui.urlError.' + error.reason));
                return;
            }
            window.alert(text('ui.error', [error.message]));
        });
    };

    App.prototype.analyze = function (repo) {
        var self = this;
        repo.status = 'QUEUED';
        this.render();
        request('POST', API + '/' + repo.id + '/analyze').then(function () {
            self.schedulePoll();
        }).catch(function (error) {
            window.alert(text('ui.error', [error.message]));
            self.load();
        });
    };

    App.prototype.remove = function (repo) {
        var self = this;
        request('DELETE', API + '/' + repo.id).then(function () {
            return self.load();
        }).catch(function (error) {
            window.alert(text('ui.error', [error.message]));
        });
    };

    App.prototype.schedulePoll = function () {
        var self = this;
        if (this.pollTimer) {
            window.clearTimeout(this.pollTimer);
            this.pollTimer = null;
        }
        var pending = this.repos.filter(function (repo) {
            return repo.status === 'QUEUED' || repo.status === 'RUNNING';
        });
        if (pending.length === 0 || !this.root.isConnected) {
            return;
        }
        this.pollTimer = window.setTimeout(function () {
            Promise.all(pending.map(function (repo) {
                return request('GET', API + '/' + repo.id + '/progress')
                    .then(function (state) {
                        return { repo: repo, state: state };
                    })
                    .catch(function () {
                        return null;
                    });
            })).then(function (results) {
                var changed = false;
                results.forEach(function (result) {
                    if (!result) {
                        return;
                    }
                    var repo = result.repo;
                    var state = result.state;
                    var progress = describeProgress(state);
                    if (repo.status !== state.status || repo.progress !== progress) {
                        changed = true;
                    }
                    repo.status = state.status;
                    repo.progress = progress;
                    repo.statusMessage = state.message;
                    repo.lastSyncedAt = state.lastSyncedAt;
                    repo.hasReport = state.hasReport;
                });
                if (changed) {
                    self.render();
                }
                self.schedulePoll();
            });
        }, POLL_MS);
    };

    /**
     * A reachable public repository is not a verified token. GitHub serves a public
     * repository's refs and ignores the credentials, so saying "reachable" after someone typed
     * a wrong token reads as "the token is fine" when nothing was checked.
     */
    function describeProbe(node, result) {
        var tone = 'ok';
        var message;
        if (!result || !result.ok) {
            tone = 'fail';
            message = text('ui.probeError.' + ((result && result.error) || 'unreachable'));
        } else if (result.tokenVerified) {
            message = text('ui.probeAuthed', [result.branches]);
        } else if (result.tokenOffered) {
            tone = 'note';
            message = text('ui.probePublicToken', [result.branches]);
        } else {
            message = text('ui.probePublic', [result.branches]);
        }
        if (result && result.urlHadCredentials) {
            message += ' ' + text('ui.urlCredentialsMoved');
        }
        setProbe(node, tone, message);
    }

    /* Glyph as well as colour: the three outcomes must be distinguishable without it. */
    var PROBE_GLYPH = { ok: '\u2713', note: '\u26a0', fail: '\u2715' };

    function setProbe(node, tone, message) {
        node.className = 'cq-probe cq-probe-' + tone;
        node.textContent = '';
        node.appendChild(el('span', {
            'class': 'cq-probe-glyph', 'aria-hidden': 'true', text: PROBE_GLYPH[tone]
        }));
        node.appendChild(el('span', { text: message }));
    }

    function describeProgress(state) {
        if (!state || !state.phase) {
            return '';
        }
        var label = text('ui.phase.' + state.phase);
        if (state.total > 0) {
            return label + ' ' + state.current + '/' + state.total;
        }
        return label;
    }

    function boot() {
        var roots = document.querySelectorAll('.cq-app');
        for (var i = 0; i < roots.length; i++) {
            if (!roots[i].getAttribute('data-cq-mounted')) {
                roots[i].setAttribute('data-cq-mounted', '1');
                new App(roots[i]).start();
            }
        }
    }

    /**
     * Also mount containers that appear after the page has loaded.
     *
     * The macro browser's preview pane replaces its HTML every time a parameter changes, so a
     * one-shot boot at DOMContentLoaded mounts the container that existed when the dialog
     * opened and nothing after it. Watching for new ones costs nothing and does not depend on
     * knowing when Confluence chooses to re-render.
     */
    function watchForLateArrivals() {
        if (typeof window.MutationObserver !== 'function') {
            return;
        }
        var pending = null;
        var observer = new MutationObserver(function () {
            // Coalesced: a preview refresh is many mutations for one new container.
            if (pending === null) {
                pending = window.setTimeout(function () {
                    pending = null;
                    boot();
                }, 50);
            }
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }

    // Exported so RepoMatchTest can pin the matcher against this file rather than against a
    // copy of it - a copy is what drifts. Nothing on the page reads it.
    global.CqRepoMatch = {
        identifies: identifies,
        label: label,
        matchesSelection: matchesSelection,
        normaliseRef: normaliseRef,
        ownerAndRepo: ownerAndRepo
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            boot();
            watchForLateArrivals();
        });
    } else {
        boot();
        watchForLateArrivals();
    }
}(typeof window !== 'undefined' ? window : this));
