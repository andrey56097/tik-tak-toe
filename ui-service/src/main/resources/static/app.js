'use strict';

// Base URL of the session service. Milestone 6 puts everything behind the
// Gateway on one port; setting this to '' makes every request relative, i.e.
// same-origin, and is the only change this file needs then.
const API_BASE = 'http://localhost:8082';

const POLL_INTERVAL_MS = 500;

// A game is at most 9 moves at ~1s each. The cap exists so a session wedged in
// RUNNING — or a backend that never recovers — cannot make this tab poll
// forever. It is also why polling errors need no separate retry budget: the
// next tick is the retry, and this bounds how many there can be.
const MAX_POLLS = 120;

const BOARD_SIZE = 3;

const startButton = document.getElementById('start');
const statusLine = document.getElementById('status');
const errorLine = document.getElementById('error');
const boardEl = document.getElementById('board');
const historyEl = document.getElementById('history');
const sessionLine = document.getElementById('session');

/**
 * Redraws everything from the full session state.
 *
 * This function must stay idempotent: rendering the same state twice changes
 * nothing. That is what lets the caller be a poll, an SSE event or a WebSocket
 * message without touching anything below this line. Never diff against the
 * previous state and append — a history log built by appending breaks the
 * moment an event arrives twice or a reconnect replays it.
 */
function render(session) {
    drawBoard(session.gameState ? session.gameState.board : null);
    statusLine.textContent = describeStatus(session);
    drawHistory(session.moveHistory || []);

    if (session.status === 'FAILED') {
        showError('The simulation failed. The board below is the last known state.');
    }
}

/** Null board is the norm, not an edge case: a CREATED session has no game yet. */
function drawBoard(board) {
    boardEl.replaceChildren();
    for (let row = 0; row < BOARD_SIZE; row++) {
        for (let col = 0; col < BOARD_SIZE; col++) {
            const value = board ? board[row][col] : 'EMPTY';
            const cell = document.createElement('div');
            cell.className = 'cell';
            cell.textContent = value === 'EMPTY' ? '' : value;
            boardEl.appendChild(cell);
        }
    }
}

/**
 * Wording for the wire values.
 *
 * The status itself always leads the line, because the assignment asks for the
 * current game status to be shown and it has to be findable at a glance. What
 * follows the dash is the detail a spectator actually wants — which player won —
 * and never a replacement for the status word: "X wins" alone reports the
 * outcome but hides which of the three states the game is in.
 */
const GAME_STATUS_LABELS = {
    IN_PROGRESS: 'In progress',
    DRAW: 'Draw — nobody won the game',
};

/**
 * The status shown is the *game's* (IN_PROGRESS / WIN / DRAW), which is what the
 * assignment asks for. The session's own status is a different subject — a
 * session can be FAILED while the board it left behind is a perfectly valid
 * IN_PROGRESS — so FAILED is surfaced by the error banner instead of replacing
 * this line.
 *
 * An unrecognised status falls through to the raw value rather than to a guess
 * or a blank: if the Engine ever gains a fourth one, showing it verbatim is ugly
 * but honest, and it is visible instead of silently rendering as nothing.
 */
function describeStatus(session) {
    if (!session.gameState) {
        return 'Not started';
    }
    const { status, winner } = session.gameState;
    if (status === 'WIN') {
        return `Win — ${winner} won the game`;
    }
    return GAME_STATUS_LABELS[status] || status;
}

/**
 * Coordinates are shown counting from 1, because that is how a person reads a
 * board: "row 3, column 2" is the bottom-left-but-one square, whereas the
 * `(2, 1)` the API sends reads like a different square entirely.
 *
 * The offset is presentation only — it is applied here, at the point of
 * display, and nowhere else. Every row/col that crosses the wire stays 0-based,
 * which is what the Engine validates and indexes the board with, so nothing
 * downstream has to know this convention exists.
 */
function drawHistory(moves) {
    historyEl.replaceChildren();
    for (const move of moves) {
        const item = document.createElement('li');
        item.textContent = `${move.player} → row ${move.row + 1}, column ${move.col + 1}`;
        historyEl.appendChild(item);
    }
}

function showError(message) {
    errorLine.textContent = message;
    errorLine.hidden = false;
}

function clearError() {
    errorLine.hidden = true;
    errorLine.textContent = '';
}

/**
 * Pulls the message the backend already phrased for us. Every non-2xx response
 * in this system carries the shared ErrorResponse body, so there is nothing to
 * invent; only a body that cannot be parsed falls back to the status line.
 */
async function errorMessageFrom(response) {
    try {
        const body = await response.json();
        if (body && body.message) {
            return body.message;
        }
    } catch (ignored) {
        // Fall through to the generic message below.
    }
    return `Request failed with status ${response.status}`;
}

function isTerminal(session) {
    return session.status === 'COMPLETED' || session.status === 'FAILED';
}

/**
 * Polls until the session reaches a terminal status or the cap is hit.
 *
 * Deliberately a setTimeout chain rather than setInterval: the next tick is
 * scheduled only after the current response has been handled, so there can
 * never be two requests in flight and a slow response cannot land after a
 * fresher one and paint stale state.
 */
function pollUntilDone(sessionId) {
    let polls = 0;

    async function tick() {
        polls++;
        try {
            const response = await fetch(`${API_BASE}/sessions/${sessionId}`);
            if (response.status === 404) {
                showError('The session no longer exists.');
                finish();
                return;
            }
            if (!response.ok) {
                showError(await errorMessageFrom(response));
            } else {
                const session = await response.json();
                clearError();
                render(session);
                if (isTerminal(session)) {
                    finish();
                    return;
                }
            }
        } catch (networkFailure) {
            // No response at all, so there is no ErrorResponse to read. Keep
            // going: a blip resolves itself on the next tick, and a lasting
            // outage is bounded by MAX_POLLS.
            showError('Cannot reach the session service.');
        }

        if (polls >= MAX_POLLS) {
            showError('Stopped waiting: the simulation did not finish in time.');
            finish();
            return;
        }
        setTimeout(tick, POLL_INTERVAL_MS);
    }

    tick();
}

function finish() {
    startButton.disabled = false;
}

/**
 * Create the session, show it, then ask for the simulation. Polling starts only
 * once the simulation is accepted — a 404 or 409 there means there is nothing
 * to watch, and polling anyway would just hammer the service.
 */
async function startSimulation() {
    startButton.disabled = true;
    clearError();

    let session;
    try {
        const created = await fetch(`${API_BASE}/sessions`, { method: 'POST' });
        if (!created.ok) {
            showError(await errorMessageFrom(created));
            finish();
            return;
        }
        session = await created.json();
    } catch (networkFailure) {
        showError('Cannot reach the session service.');
        finish();
        return;
    }

    sessionLine.textContent = `Session ${session.sessionId}`;
    render(session);

    try {
        const started = await fetch(`${API_BASE}/sessions/${session.sessionId}/simulate`, { method: 'POST' });
        if (!started.ok) {
            showError(await errorMessageFrom(started));
            finish();
            return;
        }
    } catch (networkFailure) {
        showError('Cannot reach the session service.');
        finish();
        return;
    }

    pollUntilDone(session.sessionId);
}

startButton.addEventListener('click', startSimulation);

// Draw the empty board immediately so the page is never blank before the first
// click. Same code path as every later frame — no special "initial" rendering.
render({ status: 'CREATED', gameState: null, moveHistory: [] });
