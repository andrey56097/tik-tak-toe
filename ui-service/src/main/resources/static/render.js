'use strict';

export const BOARD_SIZE = 3;

const GAME_STATUS_LABELS = {
    IN_PROGRESS: 'In progress',
    DRAW: 'Draw — nobody won the game',
};

export function describeStatus(session) {
    if (!session.gameState) {
        return 'Not started';
    }
    const { status, winner } = session.gameState;
    if (status === 'WIN') {
        return `Win — ${winner} won the game`;
    }
    return GAME_STATUS_LABELS[status] || status;
}

export function historyLines(moves) {
    return moves.map((move) => `${move.player} → row ${move.row + 1}, column ${move.col + 1}`);
}

export function boardCells(board) {
    const cells = [];
    for (let row = 0; row < BOARD_SIZE; row++) {
        for (let col = 0; col < BOARD_SIZE; col++) {
            const value = board ? board[row][col] : 'EMPTY';
            cells.push(value === 'EMPTY' ? '' : value);
        }
    }
    return cells;
}
