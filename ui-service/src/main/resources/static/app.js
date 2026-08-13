'use strict';

import { boardCells, describeStatus, historyLines } from './render.js';

const API_BASE = '';

const startButton = document.getElementById('start');
const statusLine = document.getElementById('status');
const errorLine = document.getElementById('error');
const connectionLine = document.getElementById('connection');
const boardEl = document.getElementById('board');
const historyEl = document.getElementById('history');
const sessionLine = document.getElementById('session');

let eventSource = null;

function closeStream() {
    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }
}

function render(session) {
    drawBoard(session.gameState ? session.gameState.board : null);
    statusLine.textContent = describeStatus(session);
    drawHistory(session.moveHistory || []);

    if (session.status === 'FAILED') {
        showError('The simulation failed. The board below is the last known state.');
    }
}

function drawBoard(board) {
    boardEl.replaceChildren();
    for (const label of boardCells(board)) {
        const cell = document.createElement('div');
        cell.className = 'cell';
        cell.textContent = label;
        boardEl.appendChild(cell);
    }
}

function drawHistory(moves) {
    historyEl.replaceChildren();
    for (const line of historyLines(moves)) {
        const item = document.createElement('li');
        item.textContent = line;
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

function showConnecting() {
    connectionLine.textContent = 'Reconnecting…';
    connectionLine.hidden = false;
}

function clearConnecting() {
    connectionLine.hidden = true;
    connectionLine.textContent = '';
}

async function errorMessageFrom(response) {
    try {
        const body = await response.json();
        if (body && body.message) {
            return body.message;
        }
    } catch (ignored) {
    }
    return `Request failed with status ${response.status}`;
}

function finish() {
    closeStream();
    startButton.disabled = false;
}

function watchSession(sessionId) {
    closeStream();
    eventSource = new EventSource(`${API_BASE}/sessions/${sessionId}/stream`);

    eventSource.addEventListener('message', (event) => {
        clearError();
        clearConnecting();
        render(JSON.parse(event.data));
    });

    eventSource.addEventListener('done', () => {
        finish();
    });

    eventSource.onerror = () => {
        showConnecting();
    };
}

async function startSimulation() {
    startButton.disabled = true;
    clearError();
    clearConnecting();

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

    watchSession(session.sessionId);

    try {
        const started = await fetch(`${API_BASE}/sessions/${session.sessionId}/simulate`, { method: 'POST' });
        if (!started.ok) {
            showError(await errorMessageFrom(started));
            finish();
        }
    } catch (networkFailure) {
        showError('Cannot reach the session service.');
        finish();
    }
}

startButton.addEventListener('click', startSimulation);

render({ status: 'CREATED', gameState: null, moveHistory: [] });
