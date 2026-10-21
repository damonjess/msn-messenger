package com.limpe.tengerium.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A small single-player Tic-Tac-Toe (you are X, the computer is O),
 * styled to match the rest of the classic MSN look.
 */
class TicTacToeDialog(private val context: Context) {

    private val board = CharArray(9) { ' ' }
    private val cells = ArrayList<Button>(9)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var score: TextView

    private var playerTurn = true
    private var over = false
    private var wins = 0
    private var losses = 0
    private var draws = 0

    private val blue = Color.parseColor("#1F59B1")
    private val red = Color.parseColor("#D32F2F")
    private val ink = Color.parseColor("#1F3A60")

    fun show() {
        val density = context.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DCE6F7"))
            setPadding(px(16), px(12), px(16), px(8))
        }

        status = TextView(context).apply {
            textSize = 15f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(8))
        }
        root.addView(status)

        val grid = GridLayout(context).apply {
            rowCount = 3
            columnCount = 3
        }
        val cellSize = px(76)
        for (i in 0 until 9) {
            val button = Button(context).apply {
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                isAllCaps = false
                setBackgroundColor(Color.WHITE)
                setOnClickListener { onPlayerMove(i) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = cellSize
                height = cellSize
                setMargins(px(3), px(3), px(3), px(3))
            }
            grid.addView(button, params)
            cells.add(button)
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        score = TextView(context).apply {
            textSize = 12f
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, 0)
        }
        root.addView(score)

        resetBoard()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("MSN Messenger - Tic-Tac-Toe")
            .setView(root)
            .setPositiveButton("New Game", null)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            // Override the default so "New Game" doesn't close the dialog.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { resetBoard() }
        }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.show()
    }

    private fun resetBoard() {
        handler.removeCallbacksAndMessages(null)
        for (i in board.indices) board[i] = ' '
        for (cell in cells) {
            cell.text = ""
            cell.setBackgroundColor(Color.WHITE)
        }
        over = false
        playerTurn = true
        status.text = "Your turn (X)"
        updateScore()
    }

    private fun updateScore() {
        score.text = "You $wins  -  Computer $losses  -  Draws $draws"
    }

    private fun place(index: Int, mark: Char) {
        board[index] = mark
        cells[index].text = mark.toString()
        cells[index].setTextColor(if (mark == 'X') blue else red)
    }

    private fun onPlayerMove(index: Int) {
        if (over || !playerTurn || board[index] != ' ') return
        place(index, 'X')
        if (checkFinished()) return

        playerTurn = false
        status.text = "Computer is thinking..."
        handler.postDelayed({
            place(computerMove(), 'O')
            if (!checkFinished()) {
                playerTurn = true
                status.text = "Your turn (X)"
            }
        }, 450)
    }

    /** Win if possible, otherwise block, otherwise centre, then a corner, then anything free. */
    private fun computerMove(): Int {
        findLineMove('O')?.let { return it }
        findLineMove('X')?.let { return it }
        if (board[4] == ' ') return 4
        val corners = listOf(0, 2, 6, 8).filter { board[it] == ' ' }
        if (corners.isNotEmpty()) return corners.random()
        return board.indices.filter { board[it] == ' ' }.random()
    }

    private fun findLineMove(mark: Char): Int? {
        for (line in LINES) {
            val marks = line.count { board[it] == mark }
            val empty = line.firstOrNull { board[it] == ' ' }
            if (marks == 2 && empty != null) return empty
        }
        return null
    }

    private fun winningLine(): IntArray? {
        for (line in LINES) {
            val first = board[line[0]]
            if (first != ' ' && first == board[line[1]] && first == board[line[2]]) return line
        }
        return null
    }

    /** Returns true if the game just ended. */
    private fun checkFinished(): Boolean {
        val line = winningLine()
        if (line != null) {
            val playerWon = board[line[0]] == 'X'
            if (playerWon) wins++ else losses++
            status.text = if (playerWon) "You win!" else "The computer wins!"
            for (i in line) cells[i].setBackgroundColor(Color.parseColor("#FFF3B0"))
            over = true
            updateScore()
            return true
        }
        if (board.none { it == ' ' }) {
            draws++
            status.text = "It's a draw!"
            over = true
            updateScore()
            return true
        }
        return false
    }

    private companion object {
        val LINES = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )
    }
}
