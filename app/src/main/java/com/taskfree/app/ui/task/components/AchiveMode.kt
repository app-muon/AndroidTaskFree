package com.taskfree.app.ui.task.components

sealed class ArchiveMode {
    data object Single : ArchiveMode()
    data object Series : ArchiveMode()
}