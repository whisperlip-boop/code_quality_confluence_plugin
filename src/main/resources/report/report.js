/*
 * Report page renderer.
 *
 * Hand-rolled SVG rather than a charting library: a Confluence instance behind a firewall
 * cannot reach a CDN, and bundling a library to draw three charts is not a trade worth making.
 *
 * Chart rules this file follows, because breaking them is how a dashboard starts lying:
 *  - No dual axis. Duplicated lines and duplication ratio are two stacked panels sharing an
 *    x-axis, never two y-scales on one plot. That pairing is the whole point of the section -
 *    the ratio sits flat while the absolute count climbs - and a shared plot would invent a
 *    relationship between the two scales.
 *  - Colour follows the entity. "Copy-paste" is always orange and "moved" always blue,
 *    whatever the chart.
 *  - Every chart has a hover readout and a table view underneath it, so no value is reachable
 *    only by pointing at a 2px line.
 */
(function () {
    'use strict';

    var R = window.__CQ_REPORT;
    var mount = document.getElementById('cq-report');

    /*
     * Every language is already in the page, so switching is a re-render rather than a fetch.
     * The choice is remembered per viewer in localStorage; a browser that blocks storage just
     * gets the default back on the next visit, which is why every access is guarded.
     */
    var LANGS = window.__CQ_LANGS && window.__CQ_LANGS.length ? window.__CQ_LANGS : ['ko'];
    var LANG_STORAGE_KEY = 'cq.report.lang';
    var LANG = restoreLang();

    function restoreLang() {
        try {
            var stored = window.localStorage.getItem(LANG_STORAGE_KEY);
            if (stored && LANGS.indexOf(stored) >= 0) {
                return stored;
            }
        } catch (e) {
            // Private window, or site data blocked - fall through to the default.
        }
        return LANGS[0];
    }

    function persistLang(lang) {
        try {
            window.localStorage.setItem(LANG_STORAGE_KEY, lang);
        } catch (e) {
            // Not worth telling the reader about; the page still works.
        }
    }

    function localeTag() {
        return LANG === 'ko' ? 'ko-KR' : 'en-US';
    }

    function bundle(lang) {
        return R && R.i18n && R.i18n[lang] ? R.i18n[lang] : null;
    }

    var SURFACE = readVar('--surface', '#fcfcfb');
    var SERIES_1 = readVar('--series-1', '#2a78d6');
    var SERIES_2 = readVar('--series-2', '#eb6834');
    var DEEMPH = readVar('--deemph', '#c3c2b7');
    var GRID = readVar('--grid', '#e1e0d9');
    var AXIS = readVar('--axis', '#c3c2b7');
    var MUTED = readVar('--muted', '#898781');

    var SVG_NS = 'http://www.w3.org/2000/svg';
    var MAX_BUCKETS = 56;
    var PENDING_CHARTS = [];
    var BAR_MAX = 24;
    var GAP = 2;

    function readVar(name, fallback) {
        var value = getComputedStyle(document.documentElement).getPropertyValue(name);
        return value && value.trim() ? value.trim() : fallback;
    }

    // ---------------------------------------------------------------- DOM helpers

    function h(tag, attrs, children) {
        var node = document.createElement(tag);
        apply(node, attrs);
        append(node, children);
        return node;
    }

    function s(tag, attrs, children) {
        var node = document.createElementNS(SVG_NS, tag);
        apply(node, attrs, true);
        append(node, children);
        return node;
    }

    function apply(node, attrs, isSvg) {
        if (!attrs) {
            return;
        }
        Object.keys(attrs).forEach(function (name) {
            var value = attrs[name];
            if (value === null || value === undefined || value === false) {
                return;
            }
            if (name === 'text') {
                node.textContent = value;
            } else if (name === 'class' && !isSvg) {
                node.className = value;
            } else if (name.indexOf('on') === 0) {
                node.addEventListener(name.substring(2), value);
            } else {
                node.setAttribute(name === 'class' ? 'class' : name, value);
            }
        });
    }

    function append(node, children) {
        (children || []).forEach(function (child) {
            if (child === null || child === undefined || child === false) {
                return;
            }
            node.appendChild(typeof child === 'string'
                ? document.createTextNode(child) : child);
        });
    }

    function t(key, args) {
        var active = bundle(LANG);
        var value = active && active.strings ? active.strings[key] : null;
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

    function fmt(value, decimals) {
        if (value === null || value === undefined) {
            return '-';
        }
        return Number(value).toLocaleString(localeTag(), {
            minimumFractionDigits: decimals || 0,
            maximumFractionDigits: decimals === undefined ? 1 : decimals
        });
    }

    function fmtDate(millis) {
        if (!millis) {
            return '-';
        }
        return new Date(millis).toLocaleString(localeTag(), {
            year: 'numeric', month: 'short', day: 'numeric',
            hour: '2-digit', minute: '2-digit'
        });
    }

    function fmtDay(millis) {
        return new Date(millis).toLocaleDateString(localeTag(),
            { month: 'short', day: 'numeric' });
    }

    // ---------------------------------------------------------------- status badge

    var STATUS_GLYPH = {
        good: 'M6.5 11.3 3 7.8l1.2-1.2 2.3 2.3 5-5L12.7 5z',
        warn: 'M8 1.5 15 14H1zM7.2 6h1.6l-.2 4.2H7.4zm.8 5.1a.9.9 0 1 1 0 1.8.9.9 0 0 1 0-1.8z',
        crit: 'M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1zm-.8 3.2.2 5.2h1.2l.2-5.2zm.8 6.4a.95.95 0 1 0 0 1.9.95.95 0 0 0 0-1.9z',
        unknown: 'M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1zm-.9 10.3h1.8v1.5H7.1zm.9-6.6c1.5 0 2.6.9 2.6 2.2 0 .9-.5 1.4-1.3 2-.5.4-.6.6-.6 1.1v.3H7.2v-.5c0-.9.3-1.3 1-1.8.6-.4.8-.6.8-1 0-.5-.4-.9-1-.9s-1.1.4-1.1 1H5.3c0-1.4 1.1-2.4 2.7-2.4z'
    };

    function statusBadge(state) {
        var key = STATUS_GLYPH[state] ? state : 'unknown';
        var svg = s('svg', { viewBox: '0 0 16 16', 'aria-hidden': 'true' }, [
            s('path', { d: STATUS_GLYPH[key], fill: 'currentColor' })
        ]);
        return h('span', { 'class': 'status status-' + key }, [svg, t('state.' + key)]);
    }

    function stateColor(state) {
        if (state === 'good') {
            return readVar('--good', '#0ca30c');
        }
        if (state === 'warn') {
            return readVar('--warn', '#fab219');
        }
        if (state === 'crit') {
            return readVar('--crit', '#d03b3b');
        }
        return MUTED;
    }

    var DIR_GLYPH = {
        improving: '\u2193', worsening: '\u2191', flat: '\u2192', unknown: '?'
    };
    var DIR_TONE = {
        improving: 'good', worsening: 'warn', flat: 'unknown', unknown: 'unknown'
    };

    /** Direction is its own verdict; it never borrows the level's badge. */
    function directionChip(direction, deltaPct, deltaLines) {
        var key = DIR_GLYPH[direction] ? direction : 'unknown';
        var label = t('dir.' + key);
        if (key === 'improving' || key === 'worsening') {
            label += '  ' + (deltaLines > 0 ? '+' : '') + fmt(deltaLines, 0) + ' '
                + t('unit.lines');
        }
        return h('span', { 'class': 'chip chip-' + DIR_TONE[key] }, [
            h('span', { 'class': 'chip-glyph', 'aria-hidden': 'true', text: DIR_GLYPH[key] }),
            document.createTextNode(label)
        ]);
    }

    // ---------------------------------------------------------------- chart frame

    /**
     * Measures the container and redraws on resize. Drawing at real pixel sizes rather than
     * scaling a fixed viewBox keeps the axis text at its intended size at every width.
     */
    function chartFrame(host, height, draw) {
        var box = h('div', { 'class': 'chart' });
        var tip = h('div', { 'class': 'chart-tip', role: 'status', 'aria-live': 'polite' });
        box.appendChild(tip);
        host.appendChild(box);

        var pending = null;

        function render() {
            var width = Math.max(320, box.clientWidth || 640);
            Array.prototype.slice.call(box.querySelectorAll('svg')).forEach(function (old) {
                old.remove();
            });
            var svg = s('svg', {
                width: width, height: height,
                viewBox: '0 0 ' + width + ' ' + height, role: 'img'
            });
            box.appendChild(svg);
            draw(svg, width, height, {
                show: function (x, y, head, rows) {
                    tip.textContent = '';
                    tip.appendChild(h('div', { 'class': 'tip-head', text: head }));
                    rows.forEach(function (row) {
                        tip.appendChild(h('div', { 'class': 'tip-row' }, [
                            row.color
                                ? h('i', { 'class': 'tip-key',
                                    style: 'background:' + row.color })
                                : null,
                            h('span', { text: row.label }),
                            h('span', { 'class': 'tip-value', text: row.value })
                        ]));
                    });
                    tip.classList.add('on');
                    var width2 = tip.offsetWidth;
                    var left = Math.min(Math.max(4, x - width2 / 2), box.clientWidth - width2 - 4);
                    tip.style.left = left + 'px';
                    tip.style.top = Math.max(0, y - tip.offsetHeight - 10) + 'px';
                },
                hide: function () {
                    tip.classList.remove('on');
                }
            });
        }

        // Deferred: the card is still detached here, so clientWidth would be 0 and every
        // plot would be drawn at the fallback width. flushCharts() runs after mount.
        PENDING_CHARTS.push(render);
        if (window.ResizeObserver) {
            new ResizeObserver(function () {
                window.clearTimeout(pending);
                pending = window.setTimeout(render, 80);
            }).observe(box);
        } else {
            window.addEventListener('resize', function () {
                window.clearTimeout(pending);
                pending = window.setTimeout(render, 120);
            });
        }
        return box;
    }

    /**
     * Round tick values whose top tick is always at or above the data maximum. The first cut
     * stopped the ladder below the max, which drew the duplication line outside its own panel -
     * a chart that silently clips its own data is worse than no chart.
     */
    function niceTicks(max, count) {
        if (!(max > 0)) {
            return [0, 1];
        }
        var raw = max / (count || 3);
        var magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        var normalized = raw / magnitude;
        var step = magnitude * (normalized > 5 ? 10 : normalized > 2 ? 5 : normalized > 1 ? 2 : 1);
        var ticks = [];
        var value = 0;
        while (value < max - step * 0.0001) {
            ticks.push(Math.round(value * 1000) / 1000);
            value += step;
        }
        ticks.push(Math.round(value * 1000) / 1000);
        if (ticks.length < 2) {
            ticks.push(Math.round((value + step) * 1000) / 1000);
        }
        return ticks;
    }

    function gridAndAxis(svg, plot, ticks, maxValue, format) {
        ticks.forEach(function (tick) {
            var y = plot.y + plot.h - (maxValue === 0 ? 0 : tick / maxValue * plot.h);
            svg.appendChild(s('line', {
                x1: plot.x, x2: plot.x + plot.w, y1: y, y2: y,
                stroke: tick === 0 ? AXIS : GRID, 'stroke-width': 1
            }));
            svg.appendChild(s('text', {
                x: plot.x - 8, y: y + 4, 'text-anchor': 'end',
                fill: MUTED, 'font-size': 10.5, text: format(tick)
            }));
        });
    }

    /** Column with a 4px rounded data-end and a square foot on the baseline. */
    function barPath(x, y, w, height, radius) {
        var r = Math.max(0, Math.min(radius, w / 2, height));
        if (height <= 0) {
            return '';
        }
        if (r === 0) {
            return 'M' + x + ',' + y + 'h' + w + 'v' + height + 'h' + (-w) + 'Z';
        }
        return 'M' + x + ',' + (y + height)
            + 'V' + (y + r)
            + 'a' + r + ',' + r + ' 0 0 1 ' + r + ',' + (-r)
            + 'h' + (w - 2 * r)
            + 'a' + r + ',' + r + ' 0 0 1 ' + r + ',' + r
            + 'V' + (y + height) + 'Z';
    }

    function xAxisLabels(svg, plot, buckets, label) {
        if (buckets.length === 0) {
            return;
        }
        svg.appendChild(s('text', {
            x: plot.x, y: plot.y + plot.h + 18, fill: MUTED, 'font-size': 10.5,
            text: fmtDay(buckets[0].at)
        }));
        svg.appendChild(s('text', {
            x: plot.x + plot.w, y: plot.y + plot.h + 18, 'text-anchor': 'end',
            fill: MUTED, 'font-size': 10.5,
            text: fmtDay(buckets[buckets.length - 1].at)
        }));
        svg.appendChild(s('text', {
            x: plot.x + plot.w / 2, y: plot.y + plot.h + 18, 'text-anchor': 'middle',
            fill: MUTED, 'font-size': 10.5, text: label
        }));
    }

    // ---------------------------------------------------------------- bucketing

    /**
     * Commits collapse into at most MAX_BUCKETS columns. A per-commit column on a repository
     * with a thousand commits is a 1px sliver nobody can hover, and the shape of the trend is
     * the readable part anyway.
     */
    function bucketCommits(commits) {
        var counted = commits.filter(function (commit) {
            return !commit.imported;
        });
        if (counted.length === 0) {
            return [];
        }
        var size = Math.ceil(counted.length / MAX_BUCKETS);
        var buckets = [];
        for (var i = 0; i < counted.length; i += size) {
            var slice = counted.slice(i, i + size);
            var bucket = {
                from: slice[0], to: slice[slice.length - 1], count: slice.length,
                at: slice[slice.length - 1].at,
                novel: 0, copied: 0, moved: 0, added: 0,
                churn: 0, churnAdded: 0, censored: true
            };
            slice.forEach(function (commit) {
                bucket.novel += commit.novel;
                bucket.copied += commit.copied;
                bucket.moved += commit.moved;
                bucket.added += commit.added;
                if (!commit.censored) {
                    bucket.censored = false;
                    bucket.churn += commit.churn;
                    bucket.churnAdded += commit.added;
                }
            });
            bucket.churnPct = bucket.churnAdded > 0
                ? bucket.churn / bucket.churnAdded * 100 : 0;
            buckets.push(bucket);
        }
        return buckets;
    }

    function bucketLabel(bucket) {
        if (bucket.count === 1) {
            return bucket.from.sha + '  ' + fmtDay(bucket.at);
        }
        return bucket.count + ' commits  ' + fmtDay(bucket.from.at) + ' - '
            + fmtDay(bucket.to.at);
    }

    // ---------------------------------------------------------------- sparkline

    function sparkline(values, accent) {
        var width = 240;
        var height = 34;
        var svg = s('svg', {
            viewBox: '0 0 ' + width + ' ' + height,
            preserveAspectRatio: 'none', 'aria-hidden': 'true'
        });
        if (!values || values.length < 2) {
            return svg;
        }
        var min = Math.min.apply(null, values);
        var max = Math.max.apply(null, values);
        var span = max - min || 1;
        var step = width / (values.length - 1);
        var points = values.map(function (value, index) {
            return [index * step, height - 3 - (value - min) / span * (height - 8)];
        });
        var d = points.map(function (point, index) {
            return (index === 0 ? 'M' : 'L') + point[0].toFixed(1) + ',' + point[1].toFixed(1);
        }).join(' ');
        // De-emphasis line, accent end-dot: the tile's value already carries the accent.
        svg.appendChild(s('path', {
            d: d, fill: 'none', stroke: DEEMPH, 'stroke-width': 2,
            'stroke-linejoin': 'round', 'stroke-linecap': 'round',
            'vector-effect': 'non-scaling-stroke'
        }));
        var last = points[points.length - 1];
        svg.appendChild(s('circle', {
            cx: last[0], cy: last[1], r: 4.5, fill: accent,
            stroke: SURFACE, 'stroke-width': 2, 'vector-effect': 'non-scaling-stroke'
        }));
        return svg;
    }

    // ---------------------------------------------------------------- KPI tiles

    var KPI_DECIMALS = {
        copyPaste: 1, refactor: 1, churn: 1, duplication: 0,
        errorSwallow: 2, connectivity: 1
    };

    function kpiTile(kpi) {
        var decimals = KPI_DECIMALS[kpi.key];
        var deltaNode = null;
        if (kpi.delta !== null && kpi.delta !== undefined) {
            var sign = kpi.delta > 0 ? '+' : '';
            deltaNode = h('div', { 'class': 'kpi-delta' }, [
                h('span', { text: sign + fmt(kpi.delta, 1) + '%' }),
                h('small', { text: t('label.delta',
                    [kpi.detail && kpi.detail.windowDays ? kpi.detail.windowDays : 90]) })
            ]);
        }

        var note = h('p', { 'class': 'kpi-note', text: t('kpi.' + kpi.key + '.note'),
            hidden: true });
        var why = h('button', {
            'class': 'kpi-why', type: 'button', text: t('label.why'),
            'aria-expanded': 'false',
            onclick: function () {
                var open = note.hidden;
                note.hidden = !open;
                why.textContent = open ? t('label.hideWhy') : t('label.why');
                why.setAttribute('aria-expanded', open ? 'true' : 'false');
            }
        });

        var foot;
        if (kpi.key === 'duplication') {
            var detail = kpi.detail || {};
            var levelNode = detail.levelApplicable
                ? statusBadge(detail.level)
                : h('span', { 'class': 'chip chip-unknown', text: t('label.noBasis') });
            foot = h('div', { 'class': 'kpi-foot kpi-foot-split' }, [
                h('div', { 'class': 'verdicts' }, [
                    h('span', { 'class': 'verdict-key', text: t('label.level') }),
                    levelNode,
                    h('span', { 'class': 'verdict-key', text: t('label.direction') }),
                    directionChip(detail.direction, kpi.delta, detail.deltaLines)
                ]),
                why
            ]);
            note.textContent = (detail.levelApplicable
                ? t('label.levelBasis', [detail.levelWarn, detail.levelCrit,
                    detail.cohortSize, languageName(detail.language)])
                : t('label.noBasisNote', [languageName(detail.language)]))
                + ' ' + t('label.floorNote', [detail.floorLines])
                + ' ' + t('kpi.duplication.note');
        } else {
            foot = h('div', { 'class': 'kpi-foot' }, [statusBadge(kpi.state), why]);
        }

        return h('div', { 'class': 'kpi' }, [
            h('div', { 'class': 'kpi-label' }, [
                document.createTextNode(t('kpi.' + kpi.key)),
                kpi.detail && kpi.detail.approximate
                    ? h('span', { 'class': 'tag-approx', text: ' ' + t('label.approximate'),
                        style: 'margin-left:6px' })
                    : null
            ]),
            h('div', { 'class': 'kpi-figure' }, [
                h('span', { 'class': 'kpi-value', text: fmt(kpi.value, decimals) }),
                h('span', { 'class': 'kpi-unit', text: t('unit.' + kpi.unit) }),
                deltaNode
            ]),
            h('div', { 'class': 'kpi-spark' }, [sparkline(kpi.spark, stateColor(kpi.state))]),
            foot,
            note
        ]);
    }

    function languageName(language) {
        if (!language) {
            return '-';
        }
        return language.charAt(0) + language.slice(1).toLowerCase();
    }

    // ---------------------------------------------------------------- legacy row

    /**
     * The metrics being replaced, ungraded and de-emphasised. They sit above the new ones so a
     * reader meets the comparison before the verdicts.
     */
    function legacyRow() {
        var metrics = R.legacy || [];
        if (metrics.length === 0) {
            return null;
        }
        return h('section', { 'class': 'legacy' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.legacy') }),
                h('span', { 'class': 'eyebrow', text: t('label.noBasis') })
            ]),
            h('p', { 'class': 'card-note', text: t('legacy.note') }),
            h('div', { 'class': 'legacy-grid' }, metrics.map(function (metric) {
                var delta = metric.delta === null || metric.delta === undefined
                    ? null
                    : h('span', { 'class': 'legacy-delta',
                        text: (metric.delta > 0 ? '+' : '') + fmt(metric.delta, 1) + '%' });
                return h('div', { 'class': 'legacy-box' }, [
                    h('div', { 'class': 'legacy-label',
                        text: t('legacy.' + metric.key) }),
                    h('div', { 'class': 'legacy-figure' }, [
                        h('span', { 'class': 'legacy-value',
                            text: fmt(metric.value,
                                metric.key === 'complexity' || metric.key === 'duplicates'
                                    ? 2 : 1) }),
                        h('span', { 'class': 'legacy-unit', text: t('unit.' + metric.unit) }),
                        delta
                    ]),
                    h('p', { 'class': 'legacy-miss',
                        text: t('legacy.' + metric.key + '.miss') })
                ]);
            }))
        ]);
    }

    // ---------------------------------------------------------------- duplication panels

    function duplicationSection(commits) {
        var sampled = commits.filter(function (commit) {
            return commit.dupLines !== undefined && commit.dupLines !== null;
        });

        var card = h('div', { 'class': 'card' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.duplication') }),
                h('span', { 'class': 'eyebrow',
                    text: t('label.clones',
                        [fmt(kpiByKey('duplication').detail.clones, 0)]) })
            ]),
            h('p', { 'class': 'card-note', text: t('kpi.duplication.note') })
        ]);

        if (sampled.length < 2) {
            card.appendChild(h('p', { 'class': 'card-note', text: t('label.noData') }));
            return card;
        }

        var maxLines = Math.max.apply(null, sampled.map(function (c) {
            return c.dupLines;
        }));
        var maxPct = Math.max.apply(null, sampled.map(function (c) {
            return c.dupPct;
        }));

        chartFrame(card, 300, function (svg, width, height, tip) {
            var left = 58;
            var right = 18;
            var titleH = 18;
            var gapY = 14;
            var axisH = 26;
            var panelH = (height - axisH - titleH * 2 - gapY) / 2;
            var plotW = width - left - right;
            var top = { x: left, y: titleH, w: plotW, h: panelH };
            var bottom = {
                x: left, y: titleH + panelH + gapY + titleH, w: plotW, h: panelH
            };

            panelTitle(svg, top, t('chart.dupAbsolute'));
            panelTitle(svg, bottom, t('chart.dupRatio'));

            var topTicks = niceTicks(maxLines, 2);
            var bottomTicks = niceTicks(maxPct, 2);
            var topMax = topTicks[topTicks.length - 1];
            var bottomMax = bottomTicks[bottomTicks.length - 1];
            gridAndAxis(svg, top, topTicks, topMax, function (v) {
                return fmt(v, 0);
            });
            gridAndAxis(svg, bottom, bottomTicks, bottomMax, function (v) {
                return fmt(v, 1) + '%';
            });

            var lastIndex = commits.length - 1;
            function px(commit) {
                return top.x + (lastIndex === 0 ? 0 : commit.i / lastIndex) * top.w;
            }

            line(svg, sampled, px, function (c) {
                return top.y + top.h - c.dupLines / topMax * top.h;
            }, SERIES_1, true, top);
            line(svg, sampled, px, function (c) {
                return bottom.y + bottom.h - c.dupPct / bottomMax * bottom.h;
            }, DEEMPH, false, bottom);

            // The one direct label that matters: where the absolute count ends up.
            var last = sampled[sampled.length - 1];
            svg.appendChild(s('text', {
                x: px(last) - 8,
                y: Math.max(top.y + 11,
                    top.y + top.h - last.dupLines / topMax * top.h - 9),
                'text-anchor': 'end', fill: readVar('--ink', '#0b0b0b'),
                'font-size': 11.5, 'font-weight': 650, text: fmt(last.dupLines, 0)
            }));

            xAxisLabels(svg, bottom, sampled.map(function (c) {
                return { at: c.at };
            }), t('chart.commitAxis'));

            crosshair(svg, width, height, top.x, top.w, sampled, px, tip,
                function (commit) {
                    return {
                        head: commit.sha + '  ' + fmtDay(commit.at),
                        rows: [
                            { color: SERIES_1, label: t('chart.dupAbsolute'),
                                value: fmt(commit.dupLines, 0) },
                            { color: DEEMPH, label: t('chart.dupRatio'),
                                value: fmt(commit.dupPct, 2) + '%' }
                        ]
                    };
                });
        });

        card.appendChild(h('div', { 'class': 'legend' }, [
            h('span', null, [h('i', { 'class': 'line',
                style: 'background:' + SERIES_1 }), t('chart.dupAbsolute')]),
            h('span', null, [h('i', { 'class': 'line',
                style: 'background:' + DEEMPH }), t('chart.dupRatio')])
        ]));

        card.appendChild(tableToggle(
            [t('report.head'), t('chart.dupAbsolute'), t('chart.dupRatio')],
            sampled.map(function (commit) {
                return [commit.sha, fmt(commit.dupLines, 0), fmt(commit.dupPct, 2) + '%'];
            })));
        return card;
    }

    function kpiByKey(key) {
        var found = (R.kpis || []).filter(function (kpi) {
            return kpi.key === key;
        })[0];
        return found || { detail: {}, spark: [] };
    }

    function panelTitle(svg, plot, label) {
        svg.appendChild(s('text', {
            x: plot.x, y: plot.y - 6, fill: MUTED, 'font-size': 10.5,
            'font-weight': 600, text: label
        }));
    }

    function line(svg, points, px, py, color, withArea, plot) {
        var d = points.map(function (point, index) {
            return (index === 0 ? 'M' : 'L') + px(point).toFixed(1) + ','
                + py(point).toFixed(1);
        }).join(' ');
        if (withArea) {
            svg.appendChild(s('path', {
                d: d + 'L' + px(points[points.length - 1]).toFixed(1) + ','
                    + (plot.y + plot.h) + 'L' + px(points[0]).toFixed(1) + ','
                    + (plot.y + plot.h) + 'Z',
                fill: color, 'fill-opacity': 0.1, stroke: 'none'
            }));
        }
        svg.appendChild(s('path', {
            d: d, fill: 'none', stroke: color, 'stroke-width': 2,
            'stroke-linejoin': 'round', 'stroke-linecap': 'round'
        }));
        var last = points[points.length - 1];
        svg.appendChild(s('circle', {
            cx: px(last), cy: py(last), r: 4.5, fill: color,
            stroke: SURFACE, 'stroke-width': 2
        }));
    }

    /** Vertical hairline that snaps to the nearest point, so the reader aims at a date. */
    function crosshair(svg, width, height, plotX, plotW, points, px, tip, describe) {
        var hair = s('line', {
            y1: 0, y2: height - 22, stroke: AXIS, 'stroke-width': 1, opacity: 0
        });
        svg.appendChild(hair);
        var overlay = s('rect', {
            x: plotX, y: 0, width: plotW, height: height - 22,
            fill: 'transparent', tabindex: 0
        });
        svg.appendChild(overlay);

        function locate(clientX) {
            var box = svg.getBoundingClientRect();
            var x = clientX - box.left;
            var nearest = points[0];
            var best = Infinity;
            points.forEach(function (point) {
                var distance = Math.abs(px(point) - x);
                if (distance < best) {
                    best = distance;
                    nearest = point;
                }
            });
            return nearest;
        }

        function show(point) {
            var x = px(point);
            hair.setAttribute('x1', x);
            hair.setAttribute('x2', x);
            hair.setAttribute('opacity', 1);
            var described = describe(point);
            tip.show(x, height / 2, described.head, described.rows);
        }

        overlay.addEventListener('pointermove', function (event) {
            show(locate(event.clientX));
        });
        overlay.addEventListener('pointerleave', function () {
            hair.setAttribute('opacity', 0);
            tip.hide();
        });
        overlay.addEventListener('focus', function () {
            show(points[points.length - 1]);
        });
        overlay.addEventListener('blur', function () {
            hair.setAttribute('opacity', 0);
            tip.hide();
        });
    }

    // ---------------------------------------------------------------- composition

    function mixSection(buckets) {
        var card = h('div', { 'class': 'card' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.mix') }),
                h('span', { 'class': 'eyebrow', text: t('label.importExcluded') })
            ]),
            h('p', { 'class': 'card-note', text: t('kpi.copyPaste.note') })
        ]);

        if (buckets.length === 0) {
            card.appendChild(h('p', { 'class': 'card-note', text: t('label.noData') }));
            return card;
        }

        var maxAdded = Math.max.apply(null, buckets.map(function (bucket) {
            return bucket.added;
        }));

        chartFrame(card, 220, function (svg, width, height, tip) {
            var plot = { x: 56, y: 8, w: width - 72, h: height - 34 };
            var ticks = niceTicks(maxAdded, 3);
            var max = ticks[ticks.length - 1];
            gridAndAxis(svg, plot, ticks, max, function (v) {
                return fmt(v, 0);
            });

            var band = plot.w / buckets.length;
            var barWidth = Math.min(BAR_MAX, Math.max(2, band - GAP));

            buckets.forEach(function (bucket, index) {
                var x = plot.x + index * band + (band - barWidth) / 2;
                var cursor = plot.y + plot.h;
                var segments = [
                    { value: bucket.novel, color: DEEMPH, label: t('chart.novel') },
                    { value: bucket.copied, color: SERIES_2, label: t('chart.copied') },
                    { value: bucket.moved, color: SERIES_1, label: t('chart.moved') }
                ].filter(function (segment) {
                    return segment.value > 0;
                });

                segments.forEach(function (segment, order) {
                    var full = segment.value / max * plot.h;
                    // 2px of surface separates touching segments; the top segment keeps the
                    // rounded data-end.
                    var drawn = Math.max(1, full - (order === segments.length - 1 ? 0 : GAP));
                    var y = cursor - full;
                    svg.appendChild(s('path', {
                        d: barPath(x, y + (full - drawn), barWidth, drawn,
                            order === segments.length - 1 ? 4 : 0),
                        fill: segment.color
                    }));
                    cursor -= full;
                });

                var hit = s('rect', {
                    x: plot.x + index * band, y: plot.y, width: band, height: plot.h,
                    fill: 'transparent'
                });
                hit.addEventListener('pointerenter', function () {
                    tip.show(plot.x + index * band + band / 2, plot.y + plot.h / 2,
                        bucketLabel(bucket), [
                            { color: SERIES_1, label: t('chart.moved'),
                                value: fmt(bucket.moved, 0) },
                            { color: SERIES_2, label: t('chart.copied'),
                                value: fmt(bucket.copied, 0) },
                            { color: DEEMPH, label: t('chart.novel'),
                                value: fmt(bucket.novel, 0) }
                        ]);
                });
                hit.addEventListener('pointerleave', tip.hide);
                svg.appendChild(hit);
            });

            xAxisLabels(svg, plot, buckets, t('chart.commitAxis'));
        });

        card.appendChild(h('div', { 'class': 'legend' }, [
            h('span', null, [h('i', { style: 'background:' + DEEMPH }), t('chart.novel')]),
            h('span', null, [h('i', { style: 'background:' + SERIES_2 }), t('chart.copied')]),
            h('span', null, [h('i', { style: 'background:' + SERIES_1 }), t('chart.moved')])
        ]));

        card.appendChild(tableToggle(
            [t('chart.commitAxis'), t('chart.novel'), t('chart.copied'), t('chart.moved')],
            buckets.map(function (bucket) {
                return [bucketLabel(bucket), fmt(bucket.novel, 0), fmt(bucket.copied, 0),
                    fmt(bucket.moved, 0)];
            })));
        return card;
    }

    // ---------------------------------------------------------------- churn

    function churnSection(buckets) {
        var card = h('div', { 'class': 'card' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.churn') }),
                h('span', { 'class': 'eyebrow', text: t('label.window', [14]) })
            ]),
            h('p', { 'class': 'card-note', text: t('kpi.churn.note') })
        ]);

        if (buckets.length === 0) {
            card.appendChild(h('p', { 'class': 'card-note', text: t('label.noData') }));
            return card;
        }

        var maxPct = Math.max.apply(null, buckets.map(function (bucket) {
            return bucket.churnPct;
        }));

        chartFrame(card, 200, function (svg, width, height, tip) {
            var plot = { x: 56, y: 8, w: width - 72, h: height - 34 };
            var ticks = niceTicks(Math.max(maxPct, 5), 3);
            var max = ticks[ticks.length - 1];
            gridAndAxis(svg, plot, ticks, max, function (v) {
                return fmt(v, 0) + '%';
            });

            // Texture for the censored bars: the grey alone would read as "low", and this is
            // the one place where a wrong reading flatters the newest work.
            var pattern = s('pattern', {
                id: 'cq-censored', width: 6, height: 6,
                patternTransform: 'rotate(45)', patternUnits: 'userSpaceOnUse'
            }, [
                s('rect', { width: 6, height: 6, fill: 'transparent' }),
                s('line', { x1: 0, y1: 0, x2: 0, y2: 6, stroke: DEEMPH, 'stroke-width': 3 })
            ]);
            svg.appendChild(s('defs', null, [pattern]));

            var band = plot.w / buckets.length;
            var barWidth = Math.min(BAR_MAX, Math.max(2, band - GAP));

            buckets.forEach(function (bucket, index) {
                var x = plot.x + index * band + (band - barWidth) / 2;
                if (bucket.censored) {
                    // A shaded region across the whole panel height, not a bar: a full-height
                    // bar would read as 100% churn, and a short one as 0%. Neither is true -
                    // the window simply has not closed.
                    svg.appendChild(s('rect', {
                        x: plot.x + index * band, y: plot.y, width: band, height: plot.h,
                        fill: 'url(#cq-censored)', 'fill-opacity': 0.22
                    }));
                } else {
                    var barHeight = Math.max(1, bucket.churnPct / max * plot.h);
                    svg.appendChild(s('path', {
                        d: barPath(x, plot.y + plot.h - barHeight, barWidth, barHeight, 4),
                        fill: SERIES_1
                    }));
                }

                var hit = s('rect', {
                    x: plot.x + index * band, y: plot.y, width: band, height: plot.h,
                    fill: 'transparent'
                });
                hit.addEventListener('pointerenter', function () {
                    tip.show(plot.x + index * band + band / 2, plot.y + plot.h / 2,
                        bucketLabel(bucket), bucket.censored
                            ? [{ color: DEEMPH, label: t('chart.censored'), value: '-' }]
                            : [
                                { color: SERIES_1, label: t('chart.churnPct'),
                                    value: fmt(bucket.churnPct, 1) + '%' },
                                { label: t('authors.header.added'),
                                    value: fmt(bucket.added, 0) }
                            ]);
                });
                hit.addEventListener('pointerleave', tip.hide);
                svg.appendChild(hit);
            });

            xAxisLabels(svg, plot, buckets, t('chart.commitAxis'));
        });

        card.appendChild(h('div', { 'class': 'legend' }, [
            h('span', null, [h('i', { style: 'background:' + SERIES_1 }),
                t('chart.churnPct')]),
            h('span', null, [h('i', { style: 'background:' + DEEMPH + ';opacity:.5' }),
                t('chart.censored')])
        ]));
        card.appendChild(h('p', { 'class': 'card-note',
            style: 'margin-top:10px', text: t('label.censoredNote') }));

        card.appendChild(tableToggle(
            [t('chart.commitAxis'), t('chart.churnPct'), t('chart.censored')],
            buckets.map(function (bucket) {
                return [bucketLabel(bucket),
                    bucket.censored ? '-' : fmt(bucket.churnPct, 1) + '%',
                    bucket.censored ? t('chart.censored') : ''];
            })));
        return card;
    }

    // ---------------------------------------------------------------- table view

    /** Every chart gets one: no value is reachable only by hovering a 2px line. */
    function tableToggle(headers, rows) {
        var table = h('table', null, [
            h('thead', null, [h('tr', null, headers.map(function (header, index) {
                return h('th', { 'class': index === 0 ? '' : 'num', text: header });
            }))]),
            h('tbody', null, rows.map(function (row) {
                return h('tr', null, row.map(function (cell, index) {
                    return h('td', { 'class': index === 0 ? 'path' : 'num', text: cell });
                }));
            }))
        ]);
        return h('details', { 'class': 'chart-toggle' }, [
            h('summary', { text: t('label.tableView') }),
            h('div', { 'class': 'tablewrap' }, [table])
        ]);
    }

    // ---------------------------------------------------------------- findings

    function blobUrl(path, line) {
        var base = R.repo.browseBase;
        if (!base || base.indexOf('http') !== 0 || !path) {
            return null;
        }
        return base + '/blob/' + encodeURIComponent(R.repo.branch) + '/'
            + path.split('/').map(encodeURIComponent).join('/')
            + (line ? '#L' + line : '');
    }

    function findingsSection() {
        var card = h('div', { 'class': 'card' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.findings') }),
                h('span', { 'class': 'eyebrow eyebrow-plain', text: R.findings.length + '' })
            ])
        ]);
        var worded = (bundle(LANG) || {}).findings || [];
        var list = h('div', { 'class': 'finds' }, R.findings.map(function (finding, index) {
            var text = worded[index] || { title: finding.code, body: '' };
            var evidence = null;
            if (finding.code === 'crossFileClone') {
                var url = blobUrl(finding.params.fileA, finding.params.lineA);
                evidence = url
                    ? h('a', { 'class': 'ev', href: url, target: '_blank',
                        rel: 'noopener noreferrer', text: finding.evidence })
                    : h('span', { 'class': 'ev', text: finding.evidence });
            } else if (finding.evidence) {
                evidence = h('span', { 'class': 'ev', text: finding.evidence });
            }
            return h('div', { 'class': 'find' }, [
                h('span', { 'class': 'rank',
                    text: (index + 1 < 10 ? '0' : '') + (index + 1) }),
                h('div', null, [
                    h('h3', null, [
                        h('span', { 'class': 'sev',
                            style: 'background:' + stateColor(finding.severity) }),
                        document.createTextNode(text.title)
                    ]),
                    h('p', { text: text.body }),
                    evidence
                ])
            ]);
        }));
        card.appendChild(list);
        return card;
    }

    // ---------------------------------------------------------------- clones & authors

    function clonesSection() {
        var rows = R.clones || [];
        var card = h('div', { 'class': 'card' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.clones') }),
                h('span', { 'class': 'eyebrow eyebrow-plain',
                    text: t('label.clones', [rows.length]) })
            ])
        ]);
        if (rows.length === 0) {
            card.appendChild(h('p', { 'class': 'card-note', text: t('clones.empty') }));
            return card;
        }

        function cell(path, lineNo) {
            var url = blobUrl(path, lineNo);
            var label = path + ':' + lineNo;
            return h('td', { 'class': 'path' }, [
                url ? h('a', { href: url, target: '_blank', rel: 'noopener noreferrer',
                    text: label }) : document.createTextNode(label)
            ]);
        }

        card.appendChild(h('div', { 'class': 'tablewrap' }, [
            h('table', null, [
                h('thead', null, [h('tr', null, [
                    h('th', { text: t('clones.header.a') }),
                    h('th', { text: t('clones.header.b') }),
                    h('th', { 'class': 'num', text: t('clones.header.lines') })
                ])]),
                h('tbody', null, rows.map(function (clone) {
                    return h('tr', null, [
                        cell(clone.fileA, clone.lineA),
                        cell(clone.fileB, clone.lineB),
                        h('td', { 'class': 'num', text: fmt(clone.lines, 0) })
                    ]);
                }))
            ])
        ]));
        return card;
    }

    function authorsSection() {
        var rows = R.authors || [];
        var card = h('div', { 'class': 'card' }, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.authors') }),
                h('span', { 'class': 'eyebrow eyebrow-plain',
                    text: t('fact.identities', [R.repo.identityCount]) })
            ])
        ]);
        card.appendChild(h('div', { 'class': 'tablewrap' }, [
            h('table', null, [
                h('thead', null, [h('tr', null, [
                    h('th', { text: t('authors.header.name') }),
                    h('th', { 'class': 'num', text: t('authors.header.commits') }),
                    h('th', { 'class': 'num', text: t('authors.header.added') }),
                    h('th', { text: t('authors.header.identities') })
                ])]),
                h('tbody', null, rows.map(function (author) {
                    return h('tr', null, [
                        h('td', { text: author.name }),
                        h('td', { 'class': 'num', text: fmt(author.commits, 0) }),
                        h('td', { 'class': 'num', text: fmt(author.added, 0) }),
                        h('td', { 'class': 'ident',
                            text: author.identities.join(', ') })
                    ]);
                }))
            ])
        ]));
        return card;
    }

    // ---------------------------------------------------------------- page

    /** Each language names itself, so the control is readable whichever one is active. */
    function langLabel(lang) {
        var own = bundle(lang);
        if (own && own.strings && own.strings['lang.' + lang]) {
            return own.strings['lang.' + lang];
        }
        var empty = window.__CQ_EMPTY;
        return empty && empty[lang] && empty[lang].label ? empty[lang].label : lang;
    }

    function languageSwitcher() {
        var group = h('div', {
            'class': 'langs', role: 'group',
            'aria-label': t('label.language')
        }, LANGS.map(function (lang) {
            return h('button', {
                'class': 'lang' + (lang === LANG ? ' on' : ''),
                type: 'button',
                lang: lang,
                'aria-pressed': lang === LANG ? 'true' : 'false',
                text: langLabel(lang),
                onclick: function () {
                    switchLang(lang);
                }
            });
        }));
        return h('div', { 'class': 'topbar' }, [group]);
    }

    function switchLang(lang) {
        if (lang === LANG) {
            return;
        }
        var offset = window.scrollY || window.pageYOffset || 0;
        LANG = lang;
        persistLang(lang);
        document.documentElement.lang = lang;
        mount.textContent = '';
        PENDING_CHARTS.length = 0;
        draw();
        // The page is rebuilt, so put the reader back where they were reading.
        window.scrollTo(0, offset);
    }

    function renderEmpty() {
        var messages = window.__CQ_EMPTY || {};
        var message = messages[LANG] || { title: 'No report yet', body: '' };
        mount.appendChild(h('div', { 'class': 'wrap' }, [
            LANGS.length > 1 ? languageSwitcher() : null,
            h('div', { 'class': 'empty-state' }, [
                h('h1', { text: message.title }),
                h('p', { 'class': 'sub', text: message.body })
            ])
        ]));
    }

    function render() {
        var repo = R.repo;
        var wrap = h('div', { 'class': 'wrap' });
        if (LANGS.length > 1) {
            wrap.appendChild(languageSwitcher());
        }

        var identities = repo.identityCount > repo.authorCount
            ? h('small', { text: ' (' + t('fact.identities', [repo.identityCount]) + ')' })
            : null;

        wrap.appendChild(h('header', null, [
            h('span', { 'class': 'repo-chip' }, [
                h('span', { 'class': 'dot' }),
                document.createTextNode(repo.name + ' - ' + repo.branch)
            ]),
            h('h1', { text: repo.name }),
            h('p', { 'class': 'sub', text: t('report.subtitle') }),
            legacyRow(),
            h('dl', { 'class': 'facts' }, [
                fact(t('fact.commits'), fmt(repo.commits, 0)),
                fact(t('fact.span'), t('fact.days', [fmt(repo.spanDays, 0)])),
                fact(t('fact.loc'), fmt(repo.loc, 0)),
                fact(t('fact.files'), fmt(repo.files, 0)),
                fact(t('fact.authors'), fmt(repo.authorCount, 0), identities)
            ])
        ]));

        wrap.appendChild(h('div', { 'class': 'grades' }, R.grades.map(function (grade) {
            // Naming the axis is what makes a combined badge honest: "act" on its own leaves
            // the reader guessing whether the level or the trend caused it.
            var axis = grade.axis && grade.state !== 'good' && grade.state !== 'unknown'
                ? h('span', { 'class': 'grade-axis', text: t('axis.' + grade.axis) })
                : null;
            return h('div', { 'class': 'grade' }, [
                h('span', { 'class': 'grade-label', text: t('grade.' + grade.key) }),
                h('span', { 'class': 'grade-verdict' }, [statusBadge(grade.state), axis])
            ]);
        })));

        wrap.appendChild(h('section', null, [
            h('div', { 'class': 'card-head' }, [
                h('h2', { text: t('section.kpi') }),
                h('span', { 'class': 'eyebrow eyebrow-plain',
                    text: t('report.head') + ' ' + repo.headShort })
            ]),
            h('div', { 'class': 'kpis' }, R.kpis.map(kpiTile))
        ]));

        var commits = R.series.commits || [];
        var buckets = bucketCommits(commits);

        wrap.appendChild(h('section', null, [duplicationSection(commits)]));
        wrap.appendChild(h('section', null, [mixSection(buckets)]));
        wrap.appendChild(h('section', null, [churnSection(buckets)]));
        wrap.appendChild(h('section', null, [findingsSection()]));
        wrap.appendChild(h('section', null, [clonesSection()]));
        wrap.appendChild(h('section', null, [authorsSection()]));

        wrap.appendChild(h('section', { 'class': 'caveats' }, [
            h('span', { 'class': 'eyebrow', text: t('section.caveats') }),
            h('ul', null, (R.caveats || []).map(function (caveat, index) {
                var text = ((bundle(LANG) || {}).caveats || [])[index]
                    || { title: caveat.code, body: '' };
                return h('li', null, [
                    h('h3', { text: text.title }),
                    document.createTextNode(text.body)
                ]);
            }))
        ]));

        wrap.appendChild(h('footer', null, [
            h('span', { text: t('report.analysedAt') + ' ' + fmtDate(repo.analysedAt)
                + '  ·  ' + t('report.head') + ' ' + repo.headShort
                + ' (' + fmtDate(repo.headCommittedAt) + ')' }),
            h('span', { 'class': 'mono',
                text: t('report.algo', [repo.algoVersion]) + '  ·  '
                    + t('report.cached', [repo.cachedCommits]) }),
            h('span', { text: t('report.deterministic') })
        ]));

        mount.appendChild(wrap);
        flushCharts();
    }

    function flushCharts() {
        var draws = PENDING_CHARTS.slice();
        PENDING_CHARTS.length = 0;
        draws.forEach(function (draw) {
            draw();
        });
    }

    function fact(label, value, extra) {
        return h('div', { 'class': 'fact' }, [
            h('dt', { text: label }),
            h('dd', null, [document.createTextNode(value), extra])
        ]);
    }

    function draw() {
        if (!R || !R.repo) {
            renderEmpty();
        } else {
            render();
        }
    }

    document.documentElement.lang = LANG;
    draw();
}());
