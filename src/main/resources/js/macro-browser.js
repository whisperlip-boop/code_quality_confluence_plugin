/*
 * Replaces the macro browser's Repository text field with a list of checkboxes.
 *
 * A free-text field could not say what it wanted. The stored names come in two shapes -
 * entered by hand, from when the registration form had a Name field, and owner/repo derived
 * from the URL ever since - so one table showed `captureV` beside
 * `whisperlip-boop/dept_calendar` with nothing to indicate which form the parameter took, and
 * the obvious guess of pasting the clone URL matched nothing. Choosing from the actual list
 * removes the question rather than answering it.
 *
 * Registered through setMacroJsOverride, the same hook Confluence's own inline-tasks macro
 * uses, and required through module-exporter's safeRequire: if any of this fails - a future
 * Confluence changing these internals, the REST call not answering - the override is simply
 * not installed and the plain text field remains. That field still works, because the
 * parameter accepts a name, an owner/repo, an id or a clone URL.
 */
define('code-quality/macro-browser-overrides',
    ['jquery', 'ajs', 'confluence/templates', 'confluence-macro-browser/macro-browser'],
    function ($, AJS, Templates, MacroBrowser) {
        'use strict';

        var API = AJS.contextPath() + '/rest/code-quality/1.0/repos';

        /** Comma-separated, because that is what the parameter holds. */
        function split(value) {
            return String(value || '').split(',').map(function (part) {
                return part.trim();
            }).filter(function (part) {
                return part !== '';
            });
        }

        function repositoryField(param) {
            var $container = $(Templates.MacroBrowser.macroParameter());
            // The template's input stays in the DOM and keeps the value: it is what the dialog
            // reads on save, and what Confluence's own required-field check looks at to decide
            // whether the Save button may be enabled.
            var $input = $container.find("input[type='text']");
            $input.hide();

            var $list = $('<div class="cq-macro-picker"></div>');
            var $status = $('<p class="cq-macro-picker-status"></p>');
            $list.append($status);
            $input.after($list);

            function readSelection() {
                return split($input.val());
            }

            function writeSelection(names) {
                $input.val(names.join(','));
                // Tell the dialog the field changed, so Save and the preview refresh follow
                // the checkboxes rather than the hidden input nobody typed into.
                $input.trigger('change');
                if (param.required && MacroBrowser.processRequiredParameters) {
                    MacroBrowser.processRequiredParameters();
                }
            }

            $.ajax({ url: API, dataType: 'json' }).done(function (payload) {
                var repos = (payload && payload.repos) || [];
                var strings = (payload && payload.strings) || {};
                $status.remove();
                if (repos.length === 0) {
                    $list.append($('<p class="cq-macro-picker-status"></p>')
                        .text(strings['ui.empty'] || 'No repositories registered yet.'));
                    return;
                }

                var chosen = readSelection();
                repos.forEach(function (repo) {
                    var $row = $('<label class="cq-macro-picker-row"></label>');
                    var $box = $('<input type="checkbox">').val(repo.name);
                    // A macro saved before this picker existed may hold an id or a URL, so a
                    // box is ticked when the stored value identifies this repository by any of
                    // the forms the parameter accepts.
                    if (chosen.some(function (ref) {
                        return window.CqRepoMatch
                            ? window.CqRepoMatch.identifies(repo, ref)
                            : ref === repo.name;
                    })) {
                        $box.prop('checked', true);
                    }
                    $box.on('change', function () {
                        var names = [];
                        $list.find("input[type='checkbox']:checked").each(function () {
                            names.push($(this).val());
                        });
                        writeSelection(names);
                    });
                    $row.append($box);
                    $row.append($('<span class="cq-macro-picker-name"></span>')
                        .text(repo.name));
                    $row.append($('<span class="cq-macro-picker-url"></span>').text(repo.url));
                    $list.append($row);
                });

                // Normalised on load, so a stored id or URL becomes the names the boxes carry
                // and the value saved back matches what is on screen.
                var normalised = [];
                $list.find("input[type='checkbox']:checked").each(function () {
                    normalised.push($(this).val());
                });
                writeSelection(normalised);
            }).fail(function () {
                $status.text(AJS.I18n
                    ? AJS.I18n.getText('code.quality.picker.failed')
                    : 'Could not load the repository list. Type a name, owner/repo or clone'
                        + ' URL instead.');
                $input.show();
            });

            return MacroBrowser.Field($container, $input, {
                setValue: function (value) {
                    $input.val(value);
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
