package com.taskfree.app.ui.category

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.taskfree.app.R
import com.taskfree.app.ui.admin.ToolsMenuDialog
import com.taskfree.app.ui.admin.ToolsViewModel
import com.taskfree.app.ui.admin.ToolsVmFactory
import com.taskfree.app.ui.category.components.CategoryDialogHost
import com.taskfree.app.ui.category.components.CategoryEmptyState
import com.taskfree.app.ui.category.components.CategoryList
import com.taskfree.app.ui.category.components.Dialogs
import com.taskfree.app.ui.components.AppBottomBar
import com.taskfree.app.ui.enc.EncryptWizard
import com.taskfree.app.ui.enc.ViewPhraseDialog
import com.taskfree.app.ui.onboarding.Anchor
import com.taskfree.app.ui.onboarding.LocalTipManager
import com.taskfree.app.ui.onboarding.OnboardingOverlay
import com.taskfree.app.ui.onboarding.OnboardingTip
import com.taskfree.app.ui.onboarding.TipId

@Composable
fun CategoryListScreen(navController: NavHostController) {

    val context = LocalContext.current.applicationContext as Application
    val viewModel: CategoryViewModel = viewModel(factory = CategoryVmFactory(context))
    val uiState by viewModel.uiState.collectAsState()
    val adminVm: ToolsViewModel = viewModel(factory = ToolsVmFactory(context))
    var showGlobalMenu by rememberSaveable { mutableStateOf(false) }
    var showEncryptWizard by rememberSaveable { mutableStateOf(false) }
    var showPhraseDialog by rememberSaveable { mutableStateOf(false) }
    var dialogs by remember { mutableStateOf<Dialogs>(Dialogs.None) }
    val tipManager = LocalTipManager.current
    val resetTick by tipManager.resetTick.collectAsState()
    /* ------------- INSTRUCTIONS------------- */
    LaunchedEffect(uiState.isInitialLoadPending, resetTick) {
        if (uiState.isInitialLoadPending) return@LaunchedEffect
        tipManager.request(
            OnboardingTip(
                TipId.ALL_INSTRUCTIONS,
                context.getString(R.string.tip_instructions_title),
                AnnotatedString(context.getString(R.string.tip_instructions_body)),
                Anchor.ScreenHeightPercent(0.2f)
            )
        )
    }
    /* ------------- TYPE-1  (C1) ------------- */
    LaunchedEffect(uiState.isInitialLoadPending, uiState.categories.size, resetTick) {
        if (uiState.isInitialLoadPending) return@LaunchedEffect
        val noCategories = uiState.categories.isEmpty()
        val unseen = !tipManager.hasSeen(TipId.C1_ADD_CATEGORY)   // new helper, see below

        if (noCategories || unseen) {     // ← OR logic here
            tipManager.request(
                OnboardingTip(
                    TipId.C1_ADD_CATEGORY,
                    context.getString(R.string.tip_create),
                    AnnotatedString(context.getString(R.string.tip_create_your_first_category_body)),
                    Anchor.AboveBottomBarOnRight
                ), overrideSeen = noCategories            // force-show only when list empty
            )
        }
    }

    /* ------------- TYPE-2  (C2 & C3) ---------- */
    LaunchedEffect(uiState.categories.size, resetTick) {
        val numCats = uiState.categories.size
        if (numCats > 0) {
            if (numCats > 1) {
                tipManager.request(
                    OnboardingTip(
                        TipId.ALL2_REORDER_ROW,
                        context.getString(R.string.tip_title_reorder_rows),
                        AnnotatedString(context.getString(R.string.tip_drag_the_handle_to_reorder_rows)),
                        Anchor.ScreenHeightPercent(0.2f)
                    )
                )
            }
            tipManager.request(
                OnboardingTip(
                    TipId.ALL3_CLICK_ROW,
                    context.getString(R.string.tip_more_actions_title),
                    AnnotatedString(context.getString(R.string.tip_click_to_edit_and_more)),
                    Anchor.ScreenHeightPercent(0.3f)
                )
            )
            tipManager.request(
                OnboardingTip(
                    TipId.ALL4_NAVIGATE,
                    context.getString(R.string.tip_navigate_title),
                    buildAnnotatedString {
                        append(context.getString(R.string.tip_view_prefix) + " ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(context.getString(R.string.tip_view_tasks_label))
                        }
                        append(" " + context.getString(R.string.tip_view_navigate_or) + " ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(context.getString(R.string.tip_view_category_label))
                        }
                        append(" " + context.getString(R.string.tip_view_suffix))
                    },
                    Anchor.AboveBottomBarInCentre
                )
            )
        }
    }

    Scaffold(topBar = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(R.color.top_bar_colour))
                .statusBarsPadding()
                .height(30.dp)
        )
    }, bottomBar = {
        AppBottomBar(navController = navController,
            isTodayView = false,
            addButtonLabel = stringResource(R.string.add_category_button_label),
            onAddTask = { dialogs = Dialogs.Add },
            hasCategories = uiState.categories.isNotEmpty(),
            onShowGlobalMenu = { showGlobalMenu = true })
    }) { innerPadding ->


        Column(
            modifier = Modifier
                .padding(innerPadding)
                .background(colorResource(R.color.list_background_colour))
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorResource(R.color.top_bar_colour))
                    .height(40.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.categories),
                    color = colorResource(R.color.surface_colour),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.height(2.dp))
            if (uiState.categories.isEmpty()) {
                CategoryEmptyState()
            } else {
                CategoryList(categories = uiState.categories,
                    counts = uiState.incompleteCounts,
                    onDragStart = viewModel::onDragStart,
                    onDragEnd = viewModel::onDragEnd,
                    onDragMove = viewModel::onDragMove,
                    onClick = { cat -> dialogs = Dialogs.Options(cat) })
            }
        }

        CategoryDialogHost(
            dialogs = dialogs,
            setDialogs = { dialogs = it },
            onAdd = viewModel::add,
            onNavigateToCategory = { id ->
                navController.navigate("search?categoryId=$id")
            },
            onDelete = viewModel::delete,
            onArchive = viewModel::archiveCompleted,
        )

        ToolsMenuDialog(
            startEncryptFlow = { showEncryptWizard = true },
            showPhraseFlow = { showPhraseDialog = true },
            vm = adminVm,
            show = showGlobalMenu,
            onDismiss = { showGlobalMenu = false },
            onResetTips = { tipManager.resetTips() })
    }
    if (showEncryptWizard) {
        EncryptWizard(onClose = { showEncryptWizard = false })
    }

    if (showPhraseDialog) {
        ViewPhraseDialog(onClose = { showPhraseDialog = false })
    }
    OnboardingOverlay(tipManager = tipManager)
}