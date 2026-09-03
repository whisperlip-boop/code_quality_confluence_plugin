/*
 * Drives the real macro browser dialog in Chrome and reports what the preview does.
 *
 * The preview pane cannot be checked by a unit test: its content arrives from a server render
 * that Confluence starts from several places and writes into one shared iframe, and the bug
 * this exists for - the correct list appearing and then being replaced by a shorter one - is
 * an ordering fault between two of those writes. Three rounds of it were "fixed" against
 * reasoning alone and none of them held, so this drives the actual dialog, ticks the actual
 * boxes, and prints what the frame holds and every render request that went out.
 *
 * Needs nothing installed: Chrome speaks CDP over a WebSocket and Node has had one built in
 * since 22. Run Chrome yourself first so the profile is reusable and the run is repeatable:
 *
 *   chrome.exe --headless=new --remote-debugging-port=9222 \
 *       --user-data-dir=<some temp dir> about:blank
 *   node tools/macro-dialog-probe.js <pageId> <JSESSIONID> [storedRepository]
 *
 * On WSL both must be the Windows binaries - the debugging port listens on Windows' own
 * loopback - and the page id must be a page you may edit and do not mind holding a draft.
 */
const PAGE_ID = process.argv[2];
const SESSION = process.argv[3];
const STORED = process.argv[4] || '';
const BASE = process.env.CONFLUENCE_BASE_URL || 'http://localhost:18090';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const version = await (await fetch('http://127.0.0.1:9222/json/version')).json();
    const ws = new WebSocket(version.webSocketDebuggerUrl);
    await new Promise((r) => ws.addEventListener('open', r));

    let nextId = 1;
    const pending = new Map();
    const renders = [];
    ws.addEventListener('message', (m) => {
        const msg = JSON.parse(m.data);
        if (msg.id && pending.has(msg.id)) {
            const { resolve, reject } = pending.get(msg.id);
            pending.delete(msg.id);
            msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
        } else if (msg.method === 'Network.requestWillBeSent'
                && msg.params.request.url.indexOf('/macro/preview') >= 0) {
            renders.push(JSON.parse(msg.params.request.postData).macro.params.repository);
        }
    });
    const send = (method, params, sessionId) => new Promise((resolve, reject) => {
        const id = nextId++;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params: params || {}, sessionId }));
    });

    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    const S = (m, p) => send(m, p, sessionId);

    await S('Page.enable');
    await S('Runtime.enable');
    await S('Network.enable', { maxPostDataSize: 200000 });
    // Never test a cached copy by accident. Confluence keys its web-resource URLs on the plugin
    // version and serves them as immutable, so re-installing the same version leaves the
    // browser holding the previous build at an unchanged URL - and a reload cannot dislodge the
    // copy inside the preview frame, because that frame is built by script after load and its
    // fetch is an ordinary cacheable one. Three rounds of preview fixes looked undeployed
    // because of it. Set CQ_PROBE_CACHE=1 to leave the cache on, which is how to check that a
    // deploy really did change the URL.
    await S('Network.setCacheDisabled',
        { cacheDisabled: process.env.CQ_PROBE_CACHE !== '1' });
    await S('Network.setCookie',
        { name: 'JSESSIONID', value: SESSION, domain: 'localhost', path: '/' });

    const evaluate = async (expression) => {
        const r = await S('Runtime.evaluate',
            { expression, returnByValue: true, awaitPromise: true });
        if (r.exceptionDetails) {
            throw new Error(JSON.stringify(r.exceptionDetails.exception));
        }
        return r.result.value;
    };

    /** What the preview frame holds: the selection it was told, and the rows it drew. */
    const preview = () => evaluate(`
        (function () {
            var f = document.getElementById('macro-preview-iframe');
            if (!f) { return 'no frame'; }
            var d = f.contentDocument;
            if (!d || !d.body) { return 'no document'; }
            var app = d.querySelector('.cq-app');
            var names = [];
            var cells = d.querySelectorAll('.cq-table tbody tr .cq-name');
            for (var i = 0; i < cells.length; i++) { names.push(cells[i].textContent); }
            return 'only=' + (app ? JSON.stringify(app.getAttribute('data-only')) : 'none')
                + ' rows=[' + names.join(' | ') + ']';
        })()`);

    await S('Page.navigate', { url: BASE + '/pages/editpage.action?pageId=' + PAGE_ID });
    for (let i = 0; i < 120; i++) {
        if (await evaluate('!!(window.AJS && AJS.MacroBrowser && AJS.MacroBrowser.open)')) {
            break;
        }
        await sleep(1000);
    }

    const settings = STORED
        ? `{selectedMacro: {name: 'code-quality', params: {repository: '${STORED}'}, body: ''}}`
        : "{presetMacroName: 'code-quality'}";
    console.log('opening the dialog with ' + (STORED ? 'stored: ' + STORED : 'nothing stored'));
    for (let i = 0; i < 30; i++) {
        await evaluate('AJS.MacroBrowser.open(' + settings + ')');
        await sleep(1000);
        if (await evaluate("!!document.querySelector('.cq-picker-row input')")) { break; }
    }
    await sleep(3000);
    console.log('  after open        ' + await preview());

    const tick = (which) => evaluate(`
        (function () {
            var boxes = document.querySelectorAll('.cq-picker-row input[type=checkbox]');
            boxes[${which}].click();
            return boxes[${which}].checked;
        })()`);

    await evaluate("document.querySelector('.cq-picker-trigger').click()");
    await sleep(200);

    // Each step reports straight away: a repaint is local, so there is nothing to wait for.
    // Anything appearing in "renders" here is a server round trip that could still race.
    const boxes = await evaluate(
        "document.querySelectorAll('.cq-picker-row input[type=checkbox]').length");
    for (let i = 0; i < boxes; i++) {
        const on = await tick(i);
        await sleep(250);
        console.log('  box ' + i + (on ? ' ticked   ' : ' unticked ') + await preview());
    }
    // And back off again, which is the case that showed nothing at all.
    for (let i = 0; i < boxes; i++) {
        const on = await tick(i);
        await sleep(250);
        console.log('  box ' + i + (on ? ' ticked   ' : ' unticked ') + await preview());
    }

    // Finish with something ticked: an insert whose required parameter has been empty has no
    // frame to paint into, so this is the one path that must still fall back to a render.
    await tick(0);
    await sleep(250);
    console.log('  box 0 ticked   ' + await preview());
    await evaluate("document.querySelector('.cq-picker-trigger').click()");
    await sleep(4000);
    console.log('  dropdown closed   ' + await preview());
    console.log('  value saved would be: '
        + JSON.stringify(await evaluate("$('#macro-param-repository').val()")));
    console.log('  server renders: ' + (renders.length
        ? renders.map((r) => JSON.stringify(r)).join(', ') : 'none after the first'));

    await S('Target.closeTarget', { targetId });
    ws.close();
}

main().catch((e) => { console.error('FAILED: ' + e.message); process.exit(1); });
