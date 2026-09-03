/*
 * Repository table for the code-quality macro and the standalone admin page.
 *
 * Plain DOM on purpose: this same file is loaded as a Confluence web-resource and inlined into
 * a page that has no AUI, so it cannot depend on AJS being there. Labels arrive with the list
 * response rather than from a client-side bundle, for the same reason.
 */
(function () {
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
                    reject(new Error(parsed && parsed.error ? parsed.error
                        : 'HTTP ' + xhr.status));
                }
            };
            xhr.onerror = function () {
                reject(new Error('Network error'));
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

    /* Inline so the icons work with no image requests and inherit currentColor. */
    var ICONS = {
        analyze: 'M13.65 2.35A8 8 0 1 0 16 8h-2a6 6 0 1 1-1.76-4.24L10 6h6V0z',
        report: 'M4 1h6l4 4v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1zm5 1.5V6h3.5zM5.5 9h5v1.4h-5zm0 3h5v1.4h-5zm0-6h2.5v1.4H5.5z',
        edit: 'M12.3 1.7a1 1 0 0 1 1.4 0l1.6 1.6a1 1 0 0 1 0 1.4L6.4 13.6 2 15l1.4-4.4zM11 4.4 12.6 6l1.1-1.1L12.1 3.3z',
        remove: 'M6 2h4l.5 1H14v1.5H2V3h3.5zM3.5 5.5h11L13.6 15a1 1 0 0 1-1 .9H4.4a1 1 0 0 1-1-.9zm3 2 .3 6h1.4l-.3-6zm4.3 0h-1.4l-.3 6h1.4z'
    };

    function icon(name) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 16 16');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', ICONS[name]);
        path.setAttribute('fill', 'currentColor');
        svg.appendChild(path);
        return svg;
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
        this.only = (root.getAttribute('data-only') || '').trim();
        this.heading = (root.getAttribute('data-title') || '').trim();
        this.repos = [];
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
            if (self.only) {
                self.repos = self.repos.filter(function (repo) {
                    return String(repo.id) === self.only || repo.name === self.only;
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
        var cols = el('colgroup', null, ['name', 'url', 'sync', 'status', 'actions']
            .map(function (name) {
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

        var children = [
            el('h3', { 'class': 'cq-panel-title',
                text: this.heading || text('ui.repositories') })
        ];

        if (this.repos.length === 0) {
            children.push(el('p', {
                'class': 'cq-empty',
                text: CAN_MANAGE ? text('ui.emptyAdmin') : text('ui.empty')
            }));
        } else {
            children.push(el('table', { 'class': 'cq-table' }, [cols, head, body]));
        }

        var bar = [];
        if (CAN_MANAGE) {
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
            text: CAN_MANAGE ? text('ui.deterministic')
                : text('ui.deterministic') + ' ' + text('ui.adminOnly')
        }));

        return el('div', { 'class': 'cq-panel' }, children);
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
        if (CAN_MANAGE) {
            actions.push(iconButton('edit', text('ui.edit'), function () {
                self.editing = {
                    id: repo.id, name: repo.name, url: repo.url, branch: repo.branch,
                    authUser: repo.authUser, hasToken: repo.hasToken,
                    excludes: repo.excludes
                };
                self.render();
            }));
            actions.push(iconButton('remove', text('ui.delete'), function () {
                if (window.confirm(text('ui.confirmDelete', [repo.name]))) {
                    self.remove(repo);
                }
            }, { danger: true }));
        }

        var nameCell = [el('span', { 'class': 'cq-name', text: repo.name })];
        if (repo.branch) {
            nameCell.push(el('span', { 'class': 'cq-branch', text: repo.branch }));
        }

        return el('tr', null, [
            el('td', null, nameCell),
            el('td', { 'class': 'cq-url', title: repo.url }, [
                el('a', { href: repo.url, target: '_blank', rel: 'noopener noreferrer',
                    text: repo.url })
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
                        if (fields.url.value.trim() === '') {
                            showError('url', text('ui.form.urlRequired'));
                            return;
                        }
                        self.save(draft, fields);
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
                            setProbe(probeNote, 'fail', text('ui.probeFail', [error.message]));
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

    App.prototype.save = function (draft, fields) {
        var self = this;
        var body = {
            name: fields.name.value.trim(),
            url: fields.url.value.trim(),
            branch: fields.branch.value.trim(),
            authUser: fields.authUser.value.trim(),
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
            message = text('ui.probeFail', [result ? result.error : '']);
        } else if (result.tokenVerified) {
            message = text('ui.probeAuthed', [result.branches]);
        } else if (result.tokenOffered) {
            tone = 'note';
            message = text('ui.probePublicToken', [result.branches]);
        } else {
            message = text('ui.probePublic', [result.branches]);
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

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
}());
