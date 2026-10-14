package com.endsight.dungeon;

import java.util.Arrays;

import static com.endsight.dungeon.TicTacToeLogic.BLUE;
import static com.endsight.dungeon.TicTacToeLogic.EMPTY;
import static com.endsight.dungeon.TicTacToeLogic.RED;

public final class TicTacToeChecks {
    private static int checks, terminalGames;
    // Independent win detector used by the exhaustive simulated games.
    private static final int[][] WINS = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}
    };

    public static void main(String[] args) {
        // The entry line activates the whole solver, so a reworded hint used to disable
        // it silently. Match the puzzle name, not the sentence.
        check(TicTacToe.entry("PUZZLE! Tic Tac Toe - Don't lose to the machine. Right-click a white tile to play."),
                "original entry wording still activates");
        check(TicTacToe.entry("PUZZLE! Tic Tac Toe - Beat the machine, or else."),
                "reworded hint still activates");
        check(TicTacToe.entry("PUZZLE! Tic Tac Toe"), "bare puzzle name activates");
        check(!TicTacToe.entry("PUZZLE! Three Weirdos - One of the three always lies."),
                "a different puzzle does not activate tic tac toe");
        check(!TicTacToe.entry("PUZZLE SOLVED! Tic Tac Toe"), "the solved line is not an entry");
        check(TicTacToeLogic.bestMove(new int[]{EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, BLUE, EMPTY, EMPTY}) == 4,
                "first observed blue bottom-left opening gets red center response");
        check(TicTacToeLogic.bestMove(new int[]{EMPTY, EMPTY, EMPTY, BLUE, RED, EMPTY, BLUE, EMPTY, EMPTY}) == 0,
                "observed blue left column requires top-left block");
        check(TicTacToeLogic.bestMove(new int[]{EMPTY, EMPTY, EMPTY, EMPTY, BLUE, EMPTY, EMPTY, EMPTY, EMPTY}) == 0,
                "blue center opening gets deterministic first corner");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, EMPTY, EMPTY, EMPTY, RED, EMPTY, EMPTY, EMPTY, BLUE}) == 1,
                "opposite blue corners require an edge to prevent a fork");
        check(TicTacToeLogic.bestMove(new int[]{RED, RED, EMPTY, BLUE, BLUE, EMPTY, EMPTY, EMPTY, BLUE}) == 2,
                "immediate red win takes precedence over blocking blue");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, BLUE, EMPTY, RED, EMPTY, EMPTY, BLUE, EMPTY, RED}) == 2,
                "immediate blue row threat blocked");
        int[] source = {EMPTY, EMPTY, EMPTY, BLUE, RED, EMPTY, BLUE, EMPTY, EMPTY};
        int[] copy = source.clone();
        TicTacToeLogic.bestMove(source);
        check(Arrays.equals(source, copy), "move search never changes detected board");
        check(TicTacToeLogic.bestMove(null) == -1, "no board gives no hint");
        check(TicTacToeLogic.bestMove(new int[8]) == -1, "incomplete board gives no hint");
        check(TicTacToeLogic.bestMove(new int[10]) == -1, "oversized board gives no hint");
        check(TicTacToeLogic.bestMove(new int[9]) == -1, "red cannot move before blue opens");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, RED, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY}) == -1,
                "equal counts mean machine's turn");
        check(TicTacToeLogic.bestMove(new int[]{RED, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY}) == -1,
                "red cannot have gone first");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, BLUE, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY}) == -1,
                "two consecutive machine moves invalidate board");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, EMPTY, EMPTY, EMPTY, 4, EMPTY, EMPTY, EMPTY, EMPTY}) == -1,
                "unknown tile is not guessed empty");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, BLUE, BLUE, RED, RED, EMPTY, EMPTY, EMPTY, EMPTY}) == -1,
                "finished blue win has no next move");
        check(TicTacToeLogic.bestMove(new int[]{RED, RED, RED, BLUE, BLUE, EMPTY, EMPTY, EMPTY, BLUE}) == -1,
                "finished red win has no next move");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, BLUE, BLUE, RED, RED, RED, BLUE, EMPTY, EMPTY}) == -1,
                "both winning colors invalidate board even with red turn counts");
        check(TicTacToeLogic.bestMove(new int[]{BLUE, RED, BLUE, BLUE, RED, RED, RED, BLUE, BLUE}) == -1,
                "completed draw has no legal next move");

        // Explore every adversarial machine continuation, not just one scripted game.
        // Red uses the real helper; the simulation checks victory independently.
        explore(new int[9], BLUE);
        check(terminalGames > 100, "all machine openings and continuations were exercised");
        System.out.println(checks + " Tic Tac Toe checks passed across " + terminalGames + " completed machine continuations");
    }

    private static void explore(int[] board, int turn) {
        int winner = winner(board);
        if (winner != EMPTY || full(board)) {
            terminalGames++;
            check(winner != BLUE, "optimal red hints never lose against any machine continuation");
            return;
        }
        if (turn == RED) {
            int move = TicTacToeLogic.bestMove(board);
            check(move >= 0 && move < 9 && board[move] == EMPTY, "red hint selects a legal empty tile");
            board[move] = RED;
            explore(board, BLUE);
            board[move] = EMPTY;
        } else {
            for (int move = 0; move < 9; move++) {
                if (board[move] != EMPTY) continue;
                board[move] = BLUE;
                explore(board, RED);
                board[move] = EMPTY;
            }
        }
    }

    private static boolean full(int[] board) {
        for (int cell : board) if (cell == EMPTY) return false;
        return true;
    }
    private static int winner(int[] board) {
        for (int[] win : WINS) {
            int color = board[win[0]];
            if (color != EMPTY && board[win[1]] == color && board[win[2]] == color) return color;
        }
        return EMPTY;
    }
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
}
