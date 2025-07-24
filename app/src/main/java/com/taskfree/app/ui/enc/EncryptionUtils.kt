package com.taskfree.app.ui.enc

/** Formats the 8-word list into neat rows (default 2-per-row). */
fun formatMnemonic(words: List<String>, perRow: Int = 2): String =
    words.withIndex()
        .chunked(perRow)
        .joinToString("\n") { row ->
            row.joinToString("   ") { (idx, w) ->
                "${(idx + 1).toString().padStart(2, ' ')}. $w"
            }
        }