// ui/task/components/TaskListContent.kt
package com.taskfree.app.ui.task.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.ui.category.CategoryViewModel
import com.taskfree.app.ui.category.CategoryVmFactory
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.SortMode
import com.taskfree.app.ui.components.thinVerticalScrollbar
import com.taskfree.app.ui.task.TaskViewModel
import com.taskfree.app.ui.task.TaskVmFactory
import com.taskfree.app.util.db
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TaskListContent(
    noTaskMessage: String,
    orderProperty: OrderProperty,
    config: TaskScreenConfig,
    onDueChange: (DueChoice) -> Unit,
    modifier: Modifier = Modifier,
    onClickTask: (TaskWithCategoryInfo) -> Unit,
) {
    val context = LocalContext.current.applicationContext as android.app.Application
    val categoriesVm: CategoryViewModel = viewModel(factory = CategoryVmFactory(context))
    val taskVm: TaskViewModel = viewModel(
        factory = TaskVmFactory(
            appContext = context,
            repo = TaskRepository(context.db)
        )
    )

    val taskUi by taskVm.uiState.collectAsState()
    val allTasksUnfiltered by taskVm.allTasksUnfiltered.collectAsState()
    val uiState by categoriesVm.uiState.collectAsState()

    val allTasks = taskUi.tasks
    val visibleStatuses = taskUi.visibleStatuses.toList()
    val allCategories = uiState.categories

    // Unified state management
    var listState by rememberTaskListState(
        TaskListState(
            selectedCategoryId = config.categoryId,
            dueChoice = config.dueChoice        // Int? : null → no filter, 0 → today
        )
    )

    val uiTasks = remember { mutableStateListOf<TaskWithCategoryInfo>() }

    // Derived filtering and sorting
    val baseFiltered by remember(allTasks, listState, visibleStatuses) {
        derivedStateOf {
            TaskFilterUtils.filterTasks(allTasks, listState, visibleStatuses)
        }
    }

    val sortedFiltered by remember(baseFiltered, listState.sortMode, orderProperty) {
        derivedStateOf {
            TaskFilterUtils.sortTasks(baseFiltered, listState.sortMode, orderProperty)
        }
    }

    val orderFingerprint by remember(allTasks) {
        derivedStateOf {
            allTasks.joinToString(";") {
                when (orderProperty) {
                    OrderProperty.TODO_PAGE -> it.task.allCategoryPageOrder
                    OrderProperty.TASK_PAGE -> it.task.singleCategoryPageOrder
                }.toString()
            }
        }
    }

    // Reorder handler
    val handleReorder = rememberTaskReorderHandler(
        taskVm = taskVm, orderProperty = orderProperty, initialCategoryId = config.categoryId
    )

    // Effects for list management
    LaunchedEffect(orderFingerprint, listState.sortMode) {
        if (listState.sortMode != SortMode.USER) {
            uiTasks.clear()
            uiTasks += sortedFiltered
        }
    }

    LaunchedEffect(listState.sortMode) {
        if (listState.sortMode == SortMode.USER) {
            uiTasks.clear()
            uiTasks += sortedFiltered
        }
    }

    LaunchedEffect(listState.targetDate) {
        // null when “All”, else the chosen cut-off date
        taskVm.setDate(listState.targetDate)
    }


    LaunchedEffect(baseFiltered, listState.sortMode) {
        if (listState.sortMode != SortMode.USER) return@LaunchedEffect

        val sorted = when (orderProperty) {
            OrderProperty.TODO_PAGE -> baseFiltered.sortedBy { it.task.allCategoryPageOrder }
            OrderProperty.TASK_PAGE -> baseFiltered.sortedBy { it.task.singleCategoryPageOrder }
        }

        uiTasks.clear()
        uiTasks += sorted
    }

    val lazyListState = rememberLazyListState()
    val tag = "ReorderDebug"
    val coroutineScope = rememberCoroutineScope()
    val draggingEnabled = listState.sortMode == SortMode.USER
    val debounceMs = 150L                     // tweak if needed
    var persistJob by remember { mutableStateOf<Job?>(null) }
    val getOrd: (Task) -> Int
    val setOrd: (Task, Int) -> Task
    when (orderProperty) {
        OrderProperty.TODO_PAGE -> {
            getOrd = { it.allCategoryPageOrder }
            setOrd = { t, ord -> t.copy(allCategoryPageOrder = ord) }
        }

        OrderProperty.TASK_PAGE -> {
            getOrd = { it.singleCategoryPageOrder }
            setOrd = { t, ord -> t.copy(singleCategoryPageOrder = ord) }
        }
    }
    fun persistMove(
        fromVis: Int,
        toVis: Int
    ) {/* ───────────────────────── HEADER ───────────────────────── */
        if (fromVis == toVis) {
            Log.d(tag, "↪ no-op"); return
        }

        /* 1️⃣  SNAPSHOTS ------------------------------------------------ */
        val full = allTasksUnfiltered.map { it.task }.sortedBy(getOrd)
        val vis = uiTasks.map { it.task }   // already reordered on screen

        /* 2️⃣  FIND MOVED TASK + INSERT IDX --------------------------- */
        val movedId = vis[toVis].id
        val movedTask = full.first { it.id == movedId }
        val fullWithout = full.filter { it.id != movedId }.toMutableList()

        val insertIdx = when {
            toVis == 0 -> fullWithout.indexOfFirst { it.id == vis.first().id }.takeIf { it >= 0 }
                ?: 0

            else -> fullWithout.indexOfFirst { it.id == vis[toVis - 1].id }.takeIf { it >= 0 }
                ?.plus(1) ?: fullWithout.size
        }

        /* 3️⃣  INSERT & SHOW ORDER ------------------------------------ */
        fullWithout.add(insertIdx, movedTask)

        /* 4️⃣  REINDEX ONLY WHEN NEEDED --------------------------------*/
        val updates = mutableListOf<Task>()
        fullWithout.forEachIndexed { idx, t ->
            if (getOrd(t) != idx) {
                updates += setOrd(t, idx)
                Log.d(tag, "Δ task ${t.id}: ${getOrd(t)} → $idx")
            }
        }

        /* 5️⃣  WRITE --------------------------------------------------- */
        if (updates.isNotEmpty()) taskVm.updateTaskOrder(updates)
    }

    val reorderState =
        rememberReorderableLazyListState(lazyListState = lazyListState, onMove = { from, to ->
            if (!draggingEnabled) return@rememberReorderableLazyListState

            /* ---- 1. live UI reorder for smooth drag ---- */
            uiTasks.apply {
                add(to.index, removeAt(from.index))
            }

            /* ---- 2. debounce persistence ---- */
            persistJob?.cancel()              // reset timer
            persistJob = coroutineScope.launch {
                delay(debounceMs)
                persistMove(from.index, to.index)   // see part 2
            }
        })

    Column(modifier.fillMaxSize()) {
        // Filters header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(R.color.top_bar_colour))
                .statusBarsPadding()
                .height(30.dp)
                .padding(horizontal = 16.dp, vertical = 0.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier.offset(y = (-4).dp)
            ) {
                TaskSearchFilters(categories = allCategories,
                    selectedCatId = listState.selectedCategoryId,
                    onSelectCategory = {
                        listState = listState.copy(selectedCategoryId = it)
                    },
                    selectedDueChoice = listState.dueChoice,
                    onDueSelected = { due ->
                        listState = listState.copy(dueChoice = due)
                        onDueChange(due)
                    })
            }
        }

        // Search and filter controls
        TaskSearchAndFilter(
            state = listState,
            visibleStatuses = visibleStatuses,
            onUpdateState = { listState = it },
            onToggleStatusVisibility = taskVm::toggleStatusVisibility
        )

        // Task list or empty state
        if (uiTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(colorResource(R.color.list_background_colour))
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val emptyMessage = if (listState.debouncedSearch.isNotBlank()) {
                    stringResource(R.string.no_tasks_match_your_filters)
                } else {
                    noTaskMessage
                }
                Text(
                    emptyMessage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.surface_colour)
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorResource(R.color.list_background_colour))
                    .thinVerticalScrollbar(
                        listState = lazyListState, thickness = 3.dp, color = Color.Gray
                    )
            ) {
                items(uiTasks, key = { it.task.id }) { twci ->
                    ReorderableItem(reorderState, key = twci.task.id) { isDragging ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onClickTask(twci) }
                                .padding(horizontal = 8.dp, vertical = 2.dp)) {
                            TaskRow(
                                task = twci.task,
                                isDragging = isDragging,
                                showCategory = true,
                                category = twci.category,
                                onClick = { onClickTask(twci) },
                                showHandle = draggingEnabled
                            )
                        }
                    }
                }
            }
        }
    }
}