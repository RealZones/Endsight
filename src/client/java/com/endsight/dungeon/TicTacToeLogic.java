package com.endsight.dungeon;

/** DragSim's machine places blue first; the player places red and only has to
 * avoid losing. No world state, clicks, or guessed tile contents enter this search. */
public final class TicTacToeLogic {
    public static final int EMPTY = 0, BLUE = 1, RED = 2;
    private static final int[] ORDER = {4, 0, 2, 6, 8, 1, 3, 5, 7};
    private static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private TicTacToeLogic() { }

    /** Row-major cells: 0 is top-left, 8 is bottom-right as viewed from the front.
     * Returns -1 for a finished, invalid, or out-of-turn board. Input is never changed. */
    public static int bestMove(int[] cells) {
        if (cells == null || cells.length != 9) return -1;
        int blue = 0, red = 0;
        for (int cell : cells) {
            if (cell == BLUE) blue++;
            else if (cell == RED) red++;
            else if (cell != EMPTY) return -1;
        }
        if (blue != red + 1 || winner(cells) != EMPTY || blue + red == 9) return -1;
        int[] board = cells.clone();
        int best = Integer.MIN_VALUE, move = -1;
        for (int cell : ORDER) {
            if (board[cell] != EMPTY) continue;
            board[cell] = RED;
            int value = search(board, BLUE, 0);
            board[cell] = EMPTY;
            if (value > best) { best = value; move = cell; }
        }
        return move;
    }

    private static int search(int[] board, int turn, int depth) {
        int winner = winner(board);
        if (winner == RED) return 10 - depth;
        if (winner == BLUE) return depth - 10;
        int best = turn == RED ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        boolean moved = false;
        for (int cell : ORDER) {
            if (board[cell] != EMPTY) continue;
            moved = true;
            board[cell] = turn;
            int value = search(board, turn == RED ? BLUE : RED, depth + 1);
            board[cell] = EMPTY;
            best = turn == RED ? Math.max(best, value) : Math.min(best, value);
        }
        return moved ? best : 0;
    }

    /** Returns 3 for an impossible board with both colors already winning. */
    static int winner(int[] board) {
        int winner = EMPTY;
        for (int[] line : LINES) {
            int color = board[line[0]];
            if (color == EMPTY || board[line[1]] != color || board[line[2]] != color) continue;
            if (winner != EMPTY && winner != color) return 3;
            winner = color;
        }
        return winner;
    }
}
