import { describe, expect, it } from 'vitest';
import { boardCells, describeStatus, historyLines } from '../../main/resources/static/render.js';

/**
 * The formatting rules the page is judged on: what the status line says, how the
 * board is laid out, and how a move reads to a person. Pure functions, so they
 * can be asserted directly — the DOM wiring that uses them lives in app.js.
 */
describe('describeStatus', () => {
    it('says "Not started" when there is no game yet', () => {
        expect(describeStatus({ status: 'CREATED', gameState: null })).toBe('Not started');
    });

    it('leads with the status word, then the detail', () => {
        // task.md asks for the game status to be shown; "X wins" alone reports the
        // outcome but hides which of the three states the game is in.
        expect(describeStatus({ gameState: { status: 'WIN', winner: 'X' } }))
            .toBe('Win — X won the game');
    });

    it('names the winner it was given, not a fixed one', () => {
        expect(describeStatus({ gameState: { status: 'WIN', winner: 'O' } }))
            .toBe('Win — O won the game');
    });

    it('reports a draw as a draw', () => {
        expect(describeStatus({ gameState: { status: 'DRAW', winner: null } }))
            .toBe('Draw — nobody won the game');
    });

    it('reports a game still being played', () => {
        expect(describeStatus({ gameState: { status: 'IN_PROGRESS', winner: null } }))
            .toBe('In progress');
    });

    it('shows an unrecognised status verbatim rather than blank or guessed', () => {
        // Ugly but honest: a fourth status invented by the backend stays visible
        // instead of rendering as nothing.
        expect(describeStatus({ gameState: { status: 'ABANDONED' } })).toBe('ABANDONED');
    });
});

describe('historyLines', () => {
    it('renders coordinates 1-based for humans while the wire stays 0-based', () => {
        expect(historyLines([{ player: 'X', row: 2, col: 1 }]))
            .toEqual(['X → row 3, column 2']);
    });

    it('keeps the moves in the order they were played', () => {
        expect(historyLines([
            { player: 'X', row: 0, col: 0 },
            { player: 'O', row: 1, col: 1 },
        ])).toEqual(['X → row 1, column 1', 'O → row 2, column 2']);
    });

    it('renders an empty history as no lines at all', () => {
        expect(historyLines([])).toEqual([]);
    });
});

describe('boardCells', () => {
    it('renders a null board as nine blanks', () => {
        // A CREATED session has no game yet: the norm, not an edge case.
        expect(boardCells(null)).toEqual(Array(9).fill(''));
    });

    it('renders EMPTY as blank and marks as themselves', () => {
        expect(boardCells([
            ['X', 'EMPTY', 'O'],
            ['EMPTY', 'X', 'EMPTY'],
            ['O', 'EMPTY', 'X'],
        ])).toEqual(['X', '', 'O', '', 'X', '', 'O', '', 'X']);
    });

    it('is row-major, so a mark in row 0 column 2 is the third cell', () => {
        const cells = boardCells([
            ['EMPTY', 'EMPTY', 'X'],
            ['EMPTY', 'EMPTY', 'EMPTY'],
            ['EMPTY', 'EMPTY', 'EMPTY'],
        ]);
        expect(cells[2]).toBe('X');
        expect(cells.filter((cell) => cell !== '')).toHaveLength(1);
    });
});
