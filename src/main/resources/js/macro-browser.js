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

        /** Does a stored reference - a name, an id, a URL - point at this repository? */
        function identifies(repo, ref) {
            return window.CqRepoMatch
                ? window.CqRepoMatch.identifies(repo, ref)
                : String(ref) === String(repo.name);
        }

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
                    return names[0];
                }
                return names.length + ' ' + strings('ui.selectedSuffix', 'selected');
            }

            var loaded = {};
            function strings(key, fallback) {
                return loaded[key] || fallback;
            }

            function publish() {
                var names = chosenNames();
                $input.val(names.join(','));
                $trigger.text(describe(names));
                $trigger.toggleClass('cq-picker-empty', names.length === 0);
                // Tell the dialog the field changed, so Save and the preview refresh follow the
                // checkboxes rather than a hidden input nobody typed into.
                $input.trigger('change');
                if (param.required && MacroBrowser.processRequiredParameters) {
                    MacroBrowser.processRequiredParameters();
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
                            (repo.name + ' ' + repo.url).toLowerCase());
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
                    $row.append($('<span class="cq-picker-name"></span>').text(repo.name));
                    $row.append($('<span class="cq-picker-url"></span>').text(repo.url));
                    $rows.append($row);
                });
                $panel.append($rows);

                // Normalised on load, so a stored id or URL becomes the names the boxes carry
                // and what is saved matches what is on screen.
                publish();
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
