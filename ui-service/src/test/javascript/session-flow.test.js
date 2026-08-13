import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The create → subscribe → simulate flow, and specifically what happens to the
 * SSE stream on the paths where something goes wrong.
 *
 * <p>The audit found the stream was opened before {@code POST /simulate} — which
 * is deliberate, so a fast game is not missed — but never closed if that call
 * failed. The button was re-enabled, so a second click opened a second stream on
 * top of the first, and each abandoned one held a server-side emitter until its
 * two-minute timeout.
 */

const PAGE = `
    <button id="start" type="button"></button>
    <p id="error" hidden></p>
    <p id="status"></p>
    <p id="connection" hidden></p>
    <div id="board"></div>
    <ol id="history"></ol>
    <p id="session"></p>
`;

/** Records every EventSource the page opens, and whether it was closed. */
class FakeEventSource {
    static opened = [];

    constructor(url) {
        this.url = url;
        this.listeners = {};
        this.closed = false;
        FakeEventSource.opened.push(this);
    }

    addEventListener(name, handler) {
        this.listeners[name] = handler;
    }

    close() {
        this.closed = true;
    }

    emit(name, data) {
        this.listeners[name]?.({ data });
    }
}

async function loadPage() {
    document.body.innerHTML = PAGE;
    FakeEventSource.opened = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    // Fresh module instance per test: app.js wires listeners at import time.
    vi.resetModules();
    return import('../../main/resources/static/app.js');
}

function jsonResponse(status, body) {
    return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.resolve(body),
    });
}

describe('starting a simulation', () => {
    beforeEach(() => {
        vi.unstubAllGlobals();
    });

    it('opens the stream before starting the simulation, so a fast game is not missed', async () => {
        const calls = [];
        vi.stubGlobal('fetch', vi.fn((url) => {
            calls.push(url);
            if (url.endsWith('/sessions')) {
                return jsonResponse(201, { sessionId: 's1', status: 'CREATED', gameState: null, moveHistory: [] });
            }
            return jsonResponse(202, {});
        }));

        await loadPage();
        document.getElementById('start').click();
        await vi.waitFor(() => expect(calls).toHaveLength(2));

        expect(FakeEventSource.opened).toHaveLength(1);
        expect(calls[1]).toContain('/simulate');
    });

    it('closes the stream when the simulate call fails, instead of leaving it open', async () => {
        vi.stubGlobal('fetch', vi.fn((url) => {
            if (url.endsWith('/sessions')) {
                return jsonResponse(201, { sessionId: 's1', status: 'CREATED', gameState: null, moveHistory: [] });
            }
            return jsonResponse(503, { message: 'Session capacity reached; try again later' });
        }));

        await loadPage();
        document.getElementById('start').click();

        await vi.waitFor(() => {
            expect(FakeEventSource.opened).toHaveLength(1);
            expect(FakeEventSource.opened[0].closed).toBe(true);
        });
        expect(document.getElementById('error').hidden).toBe(false);
        expect(document.getElementById('error').textContent)
            .toContain('try again later');
    });

    it('never leaves two streams open, however many times start is pressed', async () => {
        vi.stubGlobal('fetch', vi.fn((url) => {
            if (url.endsWith('/sessions')) {
                return jsonResponse(201, { sessionId: 's1', status: 'CREATED', gameState: null, moveHistory: [] });
            }
            return jsonResponse(503, { message: 'nope' });
        }));

        await loadPage();
        const start = document.getElementById('start');
        start.click();
        await vi.waitFor(() => expect(FakeEventSource.opened).toHaveLength(1));
        start.click();
        await vi.waitFor(() => expect(FakeEventSource.opened).toHaveLength(2));

        const stillOpen = FakeEventSource.opened.filter((source) => !source.closed);
        expect(stillOpen).toHaveLength(0);
    });

    it('closes the stream on the done event so EventSource does not reconnect for ever', async () => {
        vi.stubGlobal('fetch', vi.fn((url) => {
            if (url.endsWith('/sessions')) {
                return jsonResponse(201, { sessionId: 's1', status: 'CREATED', gameState: null, moveHistory: [] });
            }
            return jsonResponse(202, {});
        }));

        await loadPage();
        document.getElementById('start').click();
        await vi.waitFor(() => expect(FakeEventSource.opened).toHaveLength(1));

        FakeEventSource.opened[0].emit('done', '');

        expect(FakeEventSource.opened[0].closed).toBe(true);
        expect(document.getElementById('start').disabled).toBe(false);
    });

    it('renders every state it is pushed, from the full payload', async () => {
        vi.stubGlobal('fetch', vi.fn((url) => {
            if (url.endsWith('/sessions')) {
                return jsonResponse(201, { sessionId: 's1', status: 'CREATED', gameState: null, moveHistory: [] });
            }
            return jsonResponse(202, {});
        }));

        await loadPage();
        document.getElementById('start').click();
        await vi.waitFor(() => expect(FakeEventSource.opened).toHaveLength(1));

        FakeEventSource.opened[0].emit('message', JSON.stringify({
            sessionId: 's1',
            status: 'RUNNING',
            gameState: {
                status: 'IN_PROGRESS',
                winner: null,
                board: [['X', 'EMPTY', 'EMPTY'], ['EMPTY', 'EMPTY', 'EMPTY'], ['EMPTY', 'EMPTY', 'EMPTY']],
            },
            moveHistory: [{ player: 'X', row: 0, col: 0 }],
        }));

        expect(document.getElementById('status').textContent).toBe('In progress');
        expect(document.querySelectorAll('#board .cell')).toHaveLength(9);
        expect(document.querySelector('#board .cell').textContent).toBe('X');
        expect(document.querySelectorAll('#history li')).toHaveLength(1);
    });

    it('shows the backend message when the session cannot be created at all', async () => {
        vi.stubGlobal('fetch', vi.fn(() => jsonResponse(503, { message: 'Session capacity reached; try again later' })));

        await loadPage();
        document.getElementById('start').click();

        await vi.waitFor(() => {
            expect(document.getElementById('error').hidden).toBe(false);
        });
        expect(document.getElementById('error').textContent).toContain('try again later');
        expect(FakeEventSource.opened).toHaveLength(0);
        expect(document.getElementById('start').disabled).toBe(false);
    });
});
