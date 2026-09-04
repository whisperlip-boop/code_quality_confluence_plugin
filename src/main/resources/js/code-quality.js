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

    /*
     * The matcher lives in its own web resource - see js/repo-match.js. Two documents need it
     * and only one of them loads this file: the editor loads the macro browser override alone,
     * where a missing matcher was silently degrading to an exact name comparison.
     */
    function label(repo) {
        return global.CqRepoMatch.label(repo);
    }

    function identifies(repo, wanted) {
        return global.CqRepoMatch.identifies(repo, wanted);
    }

    function matchesSelection(repo, selection) {
        return global.CqRepoMatch.matchesSelection(repo, selection);
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
        /** Every repository the server offered, before this macro's selection narrowed it. */
        this.all = [];
        this.loaded = false;
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
            self.all = payload.repos || [];
            self.loaded = true;
            self.narrow();
            self.render();
            self.schedulePoll();
        });
    };

    /**
     * Applies this surface's selection to the list the server sent.
     *
     * <p>Split out from {@code load} so the selection can change without another round trip:
     * which rows to show is a decision about a list already in hand.</p>
     */
    App.prototype.narrow = function () {
        var self = this;
        if (this.context === 'admin') {
            this.repos = this.all;
            return;
        }
        // Kept so the empty case can say what it filtered and out of how many, which is the
        // difference between "you asked for something that is not here" and "nothing is
        // registered". Those used to look identical.
        this.available = this.all.map(function (repo) {
            return label(repo);
        });
        this.repos = this.all.filter(function (repo) {
            return matchesSelection(repo, self.only);
        });
    };

    /**
     * Shows a different selection immediately, without asking the server for anything.
     *
     * <p>The macro browser's preview is the caller. Its pane is filled by a server render that
     * Confluence starts when the dialog opens and again on every click of its Refresh link, and
     * every one of those writes into the same iframe with no ordering between them - so a field
     * that asks for a render can only hope its own request is the last to land. Asking on every
     * tick lost that race often enough to show two repositories and then one; asking once, when
     * the dropdown closes, narrowed the window without closing it.</p>
     *
     * <p>Nothing about a selection change needs the server: the rows are already here and the
     * choice only decides which of them to draw. Doing it locally is ordered by construction,
     * costs one repaint, and is the same code path the first render took - so the preview cannot
     * disagree with the saved page.</p>
     */
    App.prototype.applySelection = function (selection) {
        this.only = String(selection === undefined || selection === null ? '' : selection)
            .trim();
        // Before the list arrives there is nothing to redraw, and the pending load will filter
        // with the value set here.
        if (this.loaded) {
            this.narrow();
            this.render();
        }
        return true;
    };

    App.prototype.render = function () {
        this.root.textContent = '';
        this.root.appendChild(this.editing ? this.renderForm() : this.renderTable());
    };

    /**
     * The rows, as a table on a page and as a plain list in the macro preview.
     *
     * <p>The preview deliberately builds no {@code <table>}. Confluence enhances tables in
     * rendered content into sortable ones, and in the preview frame that enhancement runs after
     * this script has drawn its rows - so it found a finished table, decided the first data row
     * was a second header row, and moved it into a {@code <thead>}. One repository selected
     * showed none; two showed one. Reproduced with {@code tools/macro-dialog-probe.js}, which
     * caught the table carrying two {@code <thead>} elements while this app believed it had
     * rendered both rows.</p>
     *
     * <p>Opting out of that enhancement would mean naming whatever selector Confluence happens
     * to use. Not being a table is not something it can change its mind about - and the preview
     * loses nothing, because its two columns render stacked anyway: the frame is narrow enough
     * to trigger the layout that hides the header and makes every cell a block.</p>
     */
    App.prototype.renderRows = function () {
        var self = this;
        if (IN_MACRO_PREVIEW) {
            return el('div', { 'class': 'cq-preview-list' }, this.repos.map(function (repo) {
                return self.renderPreviewRow(repo);
            }));
        }
        var columns = ['name', 'url', 'sync', 'status', 'actions'];
        var cols = el('colgroup', null, columns.map(function (name) {
            return el('col', { 'class': 'cq-col-' + name });
        }));
        var head = el('thead', null, [el('tr', null, [
            el('th', { text: text('ui.header.name') }),
            el('th', { text: text('ui.header.url') }),
            el('th', { text: text('ui.header.lastSync') }),
            el('th', { text: text('ui.header.status') }),
            el('th', { text: text('ui.header.actions'), style: 'text-align:right' })
        ])]);
        var body = el('tbody', null, this.repos.map(function (repo) {
            return self.renderRow(repo);
        }));
        return el('table', { 'class': 'cq-table' }, [cols, head, body]);
    };

    App.prototype.renderTable = function () {
        var self = this;
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
            children.push(this.renderRows());
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
        var name = [el('span', { 'class': 'cq-name', text: label(repo) })];
        if (repo.branch) {
            name.push(el('span', { 'class': 'cq-branch', text: repo.branch }));
        }
        return el('div', { 'class': 'cq-preview-row' }, [
            el('div', { 'class': 'cq-preview-name' }, name),
            el('div', { 'class': 'cq-url', title: repo.url, text: repo.url })
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
        // Deleting is the administration screen's. Beside a filtered list on a page the icon
        // reads as "take this row out of my macro", and it removes the registration, the clone
        // and every cached metric - which is what happened, and is not recoverable by ticking
        // the box again. Removing a repository from a macro is done by unticking it.
        if (CAN_MANAGE && onAdminScreen) {
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
        if (IN_MACRO_PREVIEW) {
            // A preview shows which repositories a macro will list, not what they are doing
            // right now. Polling there only gives it another chance to redraw.
            return;
        }
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
                // Kept on the element so a repaint can reach the instance that owns it.
                roots[i].cqApp = new App(roots[i]);
                roots[i].cqApp.start();
            }
        }
    }

    /**
     * Redraws every macro on this page for a new selection. Returns how many were redrawn.
     *
     * <p>Called from the macro browser dialog, which is the parent of the preview frame - see
     * {@code App.applySelection} for why the dialog repaints rather than asking for a render.
     * The administration screen is skipped: its list is the whole registry by definition, and a
     * macro parameter has no business narrowing it.</p>
     */
    function repaint(selection) {
        var roots = document.querySelectorAll('.cq-app');
        var painted = 0;
        for (var i = 0; i < roots.length; i++) {
            var app = roots[i].cqApp;
            if (!app || app.context === 'admin') {
                continue;
            }
            roots[i].setAttribute('data-only', selection === null
                || selection === undefined ? '' : selection);
            app.applySelection(selection);
            painted++;
        }
        return painted;
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

    // The dialog's own script reaches the preview through this - a same-origin frame, so the
    // parent can call in. Absent means an older copy of this file, and the caller falls back to
    // asking Confluence for a render.
    global.CqApp = {
        repaint: repaint
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
