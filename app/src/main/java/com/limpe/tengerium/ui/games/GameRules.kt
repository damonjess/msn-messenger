package com.limpe.tengerium.ui.games

/** The shared activities two contacts can play over a normal conversation. */
enum class GameKind(val code: String) {
    TIC_TAC_TOE("TTT"),
    ROCK_PAPER_SCISSORS("RPS"),
    CONNECT_FOUR("C4"),
    POOL("POOL");

    companion object {
        fun fromCode(code: String): GameKind? = values().firstOrNull { it.code == code }
    }
}

/**
 * The nine cells of a tic-tac-toe board as one string: `-` for empty, `X` and `O` for the
 * players. Keeping it a string makes it trivial to send over the wire and to compare.
 */
object TicTacToe {

    const val EMPTY = '-'
    const val DRAW = ' '

    private val LINES = listOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
    )

    fun emptyBoard(): String = EMPTY.toString().repeat(9)

    fun isEmpty(board: String): Boolean = board.all { it == EMPTY }

    fun isFull(board: String): Boolean = board.none { it == EMPTY }

    /** The mark that has three in a row, or null when nobody has won yet. */
    fun winner(board: String): Char? {
        if (board.length != 9) return null
        for (line in LINES) {
            val mark = board[line[0]]
            if (mark == EMPTY) continue
            if (mark == board[line[1]] && mark == board[line[2]]) return mark
        }
        return null
    }

    fun isDraw(board: String): Boolean = winner(board) == null && isFull(board)

    /** True when this cell can be played right now. */
    fun isLegal(board: String, index: Int, mark: Char): Boolean =
        index in 0..8 && board.length == 9 && board[index] == EMPTY && (mark == 'X' || mark == 'O')

    /** Returns the board with [mark] placed, or null when the move breaks the rules. */
    fun move(board: String, index: Int, mark: Char): String? {
        if (!isLegal(board, index, mark)) return null
        val cells = board.toCharArray()
        cells[index] = mark
        return String(cells)
    }
}

/**
 * Connect Four: seven columns, six rows, first to line up four discs wins.
 * The board is one string of 42 cells, `-` empty, `R` and `Y` for the players,
 * starting at the top left and running row by row.
 */
object ConnectFour {

    const val COLUMNS = 7
    const val ROWS = 6
    const val EMPTY = '-'

    fun emptyBoard(): String = EMPTY.toString().repeat(COLUMNS * ROWS)

    fun cell(board: String, row: Int, column: Int): Char {
        if (row !in 0 until ROWS || column !in 0 until COLUMNS) return EMPTY
        return board.getOrElse(row * COLUMNS + column) { EMPTY }
    }

    /** Rows from the bottom up: the first empty cell a disc falls into. */
    fun landingRow(board: String, column: Int): Int {
        if (column !in 0 until COLUMNS) return -1
        for (row in ROWS - 1 downTo 0) {
            if (cell(board, row, column) == EMPTY) return row
        }
        return -1
    }

    fun isFull(board: String): Boolean = board.none { it == EMPTY }

    /** Drops [mark] into [column], or returns null when the column is full or invalid. */
    fun drop(board: String, column: Int, mark: Char): String? {
        if (mark != 'R' && mark != 'Y') return null
        val row = landingRow(board, column)
        if (row < 0) return null
        val cells = board.toCharArray()
        cells[row * COLUMNS + column] = mark
        return String(cells)
    }

    /** The mark with four in a row, or null. */
    fun winner(board: String): Char? {
        if (board.length != COLUMNS * ROWS) return null
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                val mark = cell(board, row, column)
                if (mark == EMPTY) continue
                for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
                    var count = 1
                    while (count < 4) {
                        val r = row + dr * count
                        val c = column + dc * count
                        if (r !in 0 until ROWS || c !in 0 until COLUMNS) break
                        if (cell(board, r, c) != mark) break
                        count++
                    }
                    if (count == 4) return mark
                }
            }
        }
        return null
    }

    fun isDraw(board: String): Boolean = winner(board) == null && isFull(board)
}

/** Rock, paper, scissors with the usual rules. */
enum class Rps(val code: String) {
    ROCK("R"),
    PAPER("P"),
    SCISSORS("S");

    /** 1 when this choice wins, -1 when it loses, 0 when it is the same choice. */
    fun outcomeAgainst(other: Rps): Int = when {
        this == other -> 0
        this == ROCK && other == SCISSORS -> 1
        this == SCISSORS && other == PAPER -> 1
        this == PAPER && other == ROCK -> 1
        else -> -1
    }

    companion object {
        fun fromCode(code: String): Rps? = values().firstOrNull { it.code == code }
    }
}
