/*
 * Replaces the macro browser's Repository text field with a dropdown of checkboxes.
 *
 * A free-text field could not say what it wanted. The stored names come in two shapes -
 * entered by hand, from when the registration form had a Name field, and owner/repo derived
 * from the URL ever since - so one table showed `captureV` beside
 * `whisperlip-boop/dept_calendar` with nothing to indicate which form the parameter took, and
 * the obvious guess of pasting the clone URL matched nothing. Choosing from the actual list
 * removes the question rather than answering it.
 *
 * A dropdown rather than a list laid out in the form: the field has to stay one line tall
 * however many repositories are registered, and the panel carries a filter box because an
 * instance with fifty of them is the case that matters. Built here rather than on
 * aui-dropdown2 because that component's checkbox styling is not among the CSS the macro
 * browser context loads, and one more dependency on a Confluence internal is one more thing
 * that can quietly stop looking right.
 *
 * Registered through setMacroJsOverride, the hook Confluence's own inline-tasks macro uses,
 * and installed through module-exporter's safeRequire: if any of this fails - a future
 * Confluence changing those internals, the REST call not answering - the override is not
 * installed and the plain text field remains. That field still works, because the parameter
 * accepts a name, an owner/repo, an id or a clone URL.
 */
define('code-quality/macro-browser-overrides',
    ['jquery', 'ajs', 'confluence/templates', 'confluence-macro-browser/macro-browser'],
    function ($, AJS, Templates, MacroBrowser) {
        'use strict';

        var API = AJS.contextPath() + '/rest/code-quality/1.0/repos';
        /** Above this many repositories the panel offers a filter box. */
        var FILTER_FROM = 8;

        function split(value) {
            return String(value || '').split(',').map(function (part) {
                return part.trim();
            }).filter(function (part) {
                return part !== '';
            });
        }

        /** What the row shows. Derived by the server so every screen agrees - see the DTO. */
        function label(repo) {
            return repo.label || repo.name;
        }

        /** Does a stored reference - a name, an id, a URL - point at this repository? */
        function identifies(repo, ref) {
            return window.CqRepoMatch
                ? window.CqRepoMatch.identifies(repo, ref)
                : String(ref) === String(repo.name);
        }

        /** The live value of our parameter, or null when our macro's form is not loaded. */
        function currentSelection() {
            var $input = $('#macro-param-repository');
            return $input.length ? $input.val() : null;
        }

        /**
         * Shows the current selection in the preview without a server render.
         *
         * <p>The preview frame is same-origin and holds our own script, so the dialog can tell
         * it which repositories to draw directly - see {@code App.applySelection} in
         * code-quality.js for why that is better than asking Confluence to render again.
         * Returns false when there is nothing to paint into, which is the signal to fall back
         * to the Refresh link: an empty required parameter makes Confluence skip the render
         * altogether and remove the frame, so the first selection of an insert has no frame to
         * paint.</p>
         */
        function paintPreview() {
            var selection = currentSelection();
            if (selection === null) {
                return false;
            }
            try {
                var frame = document.getElementById('macro-preview-iframe');
                var view = frame && frame.contentWindow;
                if (view && view.CqApp && typeof view.CqApp.repaint === 'function') {
                    return view.CqApp.repaint(selection) > 0;
                }
            } catch (ignored) {
                // A frame mid-navigation refuses to be read. The Refresh link still works.
            }
            return false;
        }

        /*
         * Correct every render after it lands, whoever started it.
         *
         * Confluence renders the preview when the dialog opens and on every click of its
         * Refresh link, and each render writes the whole frame. Two can be in flight at once,
         * the later to finish wins, and neither carries a promise about which parameter values
         * it captured - which is how the preview came to show two repositories and then one.
         * This fires on Confluence's own preview-ready event, so whatever HTML lands last is
         * immediately brought in line with what is ticked. Racing is then harmless rather than
         * merely unlikely.
         */
        AJS.bind('macro-browser.preview-ready', function () {
            paintPreview();
        });

        function repositoryField(param) {
            var $container = $(Templates.MacroBrowser.macroParameter());
            // The template's input stays in the DOM and holds the value: it is what the dialog
            // reads on save, and what Confluence's own required-field check looks at when
            // deciding whether Save may be enabled.
            var $input = $container.find("input[type='text']").hide();

            var $picker = $('<div class="cq-picker"></div>');
            var $trigger = $('<button type="button" class="cq-picker-trigger"'
                + ' aria-haspopup="true" aria-expanded="false"></button>');
            var $panel = $('<div class="cq-picker-panel" hidden></div>');
            $picker.append($trigger).append($panel);
            $input.after($picker);

            var repos = [];

            function chosenNames() {
                var names = [];
                $panel.find("input[type='checkbox']:checked").each(function () {
                    names.push($(this).val());
                });
                return names;
            }

            function describe(names) {
                if (names.length === 0) {
                    return strings('ui.pickNone', 'Choose repositories\u2026');
                }
                if (names.length === 1) {
                    var only = repos.filter(function (repo) {
                        return repo.name === names[0];
                    })[0];
                    return only ? label(only) : names[0];
                }
                return names.length + ' ' + strings('ui.selectedSuffix', 'selected');
            }

            var loaded = {};
            function strings(key, fallback) {
                return loaded[key] || fallback;
            }

            var refreshedValue = null;

            /**
             * Asks Confluence for a render, once, when the choosing is finished.
             *
             * <p>The fallback for the one case a repaint cannot cover: while the required
             * Repository parameter is empty Confluence skips the render and removes the preview
             * frame, so an insert has no frame to paint into until one render has happened.
             * Once it has, {@code paintPreview} takes over and this stays quiet.</p>
             *
             * <p>Guarded on the value because a render is a round trip whose result overwrites
             * the frame - and it runs on close, not per tick, because the choosing is finished
             * then. The old per-tick version is what taught us that: it raced the render
             * Confluence starts itself and the preview showed two repositories and then one.
             * That race is now reconciled rather than avoided - see the preview-ready binding -
             * but there is still no reason to spend renders.</p>
             */
            function refreshPreview() {
                var value = $input.val();
                if (value === refreshedValue) {
                    return;
                }
                var $link = $('#macro-browser-preview-link');
                if ($link.length && !$link.prop('disabled')) {
                    refreshedValue = value;
                    $link.click();
                }
            }

            function publish() {
                var names = chosenNames();
                $input.val(names.join(','));
                $trigger.text(describe(names));
                $trigger.toggleClass('cq-picker-empty', names.length === 0);
                $input.trigger('change');
                // Order matters: the required check enables the Refresh link, and the refresh
                // is a no-op while it is disabled.
                if (param.required && MacroBrowser.processRequiredParameters) {
                    MacroBrowser.processRequiredParameters();
                }
                // Every tick, because a repaint is a local redraw: no request, nothing to
                // race, and the preview stops lagging a step behind the boxes.
                if (paintPreview()) {
                    refreshedValue = $input.val();
                }
            }

            function open() {
                $panel.prop('hidden', false);
                $trigger.attr('aria-expanded', 'true');
                $panel.find('.cq-picker-filter').focus();
            }

            function close() {
                $panel.prop('hidden', true);
                $trigger.attr('aria-expanded', 'false');
                // Ticking has already painted; this covers the frame that was never rendered.
                if (!paintPreview()) {
                    refreshPreview();
                }
            }

            $trigger.on('click', function (event) {
                event.preventDefault();
                if ($panel.prop('hidden')) {
                    open();
                } else {
                    close();
                }
            });
            // Closing on an outside click is scoped to this document, so several macro
            // dialogs opened in a session do not fight over one handler.
            $(document).on('click', function (event) {
                if (!$picker[0].contains(event.target)) {
                    close();
                }
            });
            $picker.on('keydown', function (event) {
                if (event.key === 'Escape' || event.keyCode === 27) {
                    close();
                    $trigger.focus();
                }
            });

            function buildPanel(payload) {
                repos = (payload && payload.repos) || [];
                loaded = (payload && payload.strings) || {};
                $panel.empty();

                if (repos.length === 0) {
                    $panel.append($('<p class="cq-picker-status"></p>')
                        .text(strings('ui.empty', 'No repositories registered yet.')));
                    $trigger.text(strings('ui.empty', 'No repositories registered yet.'))
                        .prop('disabled', true);
                    return;
                }

                var $rows = $('<div class="cq-picker-rows"></div>');
                if (repos.length >= FILTER_FROM) {
                    var $filter = $('<input type="text" class="cq-picker-filter">')
                        .attr('placeholder', strings('ui.filterPlaceholder', 'Filter'));
                    $filter.on('input', function () {
                        var needle = $(this).val().toLowerCase();
                        $rows.children().each(function () {
                            var haystack = $(this).attr('data-search') || '';
                            $(this).toggle(haystack.indexOf(needle) >= 0);
                        });
                    });
                    $panel.append($filter);
                }

                var chosen = split($input.val());
                repos.forEach(function (repo) {
                    var $row = $('<label class="cq-picker-row"></label>')
                        .attr('data-search',
                            (label(repo) + ' ' + repo.name + ' ' + repo.url).toLowerCase());
                    var $box = $('<input type="checkbox">').val(repo.name);
                    // A macro saved before this picker existed may hold an id or a URL, so a
                    // box is ticked when the stored value identifies this repository by any
                    // form the parameter accepts.
                    if (chosen.some(function (ref) {
                        return identifies(repo, ref);
                    })) {
                        $box.prop('checked', true);
                    }
                    $box.on('change', publish);
                    $row.append($box);
                    $row.append($('<span class="cq-picker-name"></span>').text(label(repo)));
                    $row.append($('<span class="cq-picker-url"></span>').text(repo.url));
                    $rows.append($row);
                });
                $panel.append($rows);

                // Normalised on load, so a stored id or URL becomes the names the boxes
                // carry and what is saved matches what is on screen. No refresh: the dialog
                // is rendering its own first preview at this moment, and this records the
                // value it will render so closing the panel without a change does nothing.
                var names = chosenNames();
                $input.val(names.join(','));
                $trigger.text(describe(names));
                $trigger.toggleClass('cq-picker-empty', names.length === 0);
                if (param.required && MacroBrowser.processRequiredParameters) {
                    MacroBrowser.processRequiredParameters();
                }
                refreshedValue = $input.val();
                // The dialog rendered its first preview from the raw stored value while this
                // list was still loading; paint so the frame agrees with the normalised one.
                paintPreview();
            }

            $trigger.text(strings('ui.loading', 'Loading...'));
            $.ajax({ url: API, dataType: 'json' })
                .done(buildPanel)
                .fail(function () {
                    // The plain field is a working fallback: the parameter takes a name, an
                    // owner/repo, an id or a clone URL.
                    $picker.remove();
                    $input.show();
                });

            return MacroBrowser.Field($container, $input, {
                setValue: function (value) {
                    $input.val(value);
                    if (repos.length) {
                        var wanted = split(value);
                        $panel.find("input[type='checkbox']").each(function () {
                            var name = $(this).val();
                            var repo = repos.filter(function (candidate) {
                                return candidate.name === name;
                            })[0];
                            $(this).prop('checked', !!repo && wanted.some(function (ref) {
                                return identifies(repo, ref);
                            }));
                        });
                        publish();
                    }
                }
            });
        }

        return { fields: { string: { repository: repositoryField } } };
    });

require('confluence/module-exporter').safeRequire('code-quality/macro-browser-overrides',
    function (overrides) {
        require('confluence-macro-browser/macro-browser')
            .setMacroJsOverride('code-quality', overrides);
    });
