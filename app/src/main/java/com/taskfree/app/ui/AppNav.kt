package com.taskfree.app.ui

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.category.CategoryListScreen
import com.taskfree.app.ui.category.CategoryViewModel
import com.taskfree.app.ui.category.CategoryVmFactory
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.enc.MnemonicManager
import com.taskfree.app.ui.task.TaskSearchScreen
import java.time.LocalDate

@Composable
fun AppNav() {
    val nav = rememberNavController()
    var navigationError by remember { mutableStateOf<String?>(null) }
    val app = LocalContext.current.applicationContext as Application

    Log.d("AppNav", "Starting AppNav")

    var needsKey by remember {
        mutableStateOf(Prefs.isEncrypted(app) && !MnemonicManager.hasKey(app))
    }

    // ── DB locked? Show recovery flow and stop here ───────────────
    if (needsKey) {
        KeyRecoveryFlow(onFinished = { needsKey = false })
        return
    } else if (Prefs.isEncrypted(app)) {
        Log.d("AppNav", "DB encrypted - initializing key")

        // Initialize cached key if we have stored key but no cached key
        LaunchedEffect(Unit) {
            if (Prefs.isEncrypted(app) && DatabaseKeyManager.getCachedKey() == null) {
                Log.d("AppNav", "Caching stored key")
                val storedKey = DatabaseKeyManager.loadDerivedKey(app)
                if (storedKey != null) {
                    DatabaseKeyManager.cacheKey(storedKey)
                    Log.d("AppNav", "Key cached successfully")
                } else {
                    Log.e("AppNav", "No stored key found!")
                }
            }
        }
    }
    Log.d("AppNav", "DB ready – launching main UI")

    fun safeNavigate(route: String) {
        try {
            nav.navigate(route) { popUpTo("splash") { inclusive = true } }
        } catch (e: Exception) {
            Log.e("Navigation", "Failed to navigate to $route", e)
            navigationError = "Navigation failed. Please try again."
        }
    }

    /* observe categories directly; null until Room replies */
    Log.d("AppNav", "Creating CategoryViewModel")
    val categoryVm: CategoryViewModel = viewModel(factory = CategoryVmFactory(app))
    Log.d("AppNav", "CategoryViewModel created successfully")

    val catUi by categoryVm.uiState.collectAsState()
    val isLoading = catUi.isInitialLoadPending
    val categories = catUi.categories

    /* ── once we know the list size, navigate away from Splash ── */
    LaunchedEffect(categories, isLoading) {
        if (isLoading) return@LaunchedEffect

        val target = if (categories.isEmpty()) "categories" else "search?dateOffset=0"
        safeNavigate(target)
    }

    navigationError?.let { error ->
        AlertDialog(onDismissRequest = { navigationError = null },
            title = { Text("Navigation Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = {
                    navigationError = null
                    safeNavigate("categories")     // safe fallback
                }) { Text("Retry") }
            })
    }

    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") {
            SplashScreen(isLoading = isLoading, error = navigationError)
        }

        /* 1. today / search screen */
        composable(
            "search?categoryId={categoryId}&dateOffset={dateOffset}",
            arguments = listOf(navArgument("categoryId") {
                type = NavType.IntType; defaultValue = -1
            }, navArgument("dateOffset") {
                type = NavType.IntType; defaultValue = Int.MIN_VALUE
            })
        ) { back ->
            val catId = runCatching {
                back.arguments?.getInt("categoryId")?.takeIf { it != -1 }
            }.getOrNull()

            val dateOffset = runCatching {
                back.arguments?.getInt("dateOffset") ?: Int.MIN_VALUE
            }.getOrDefault(Int.MIN_VALUE)

            val initialDueChoice =
                if (dateOffset == Int.MIN_VALUE) DueChoice.fromSpecial(DueChoice.Special.ALL)
                else DueChoice.from(LocalDate.now().plusDays(dateOffset.toLong()))
            TaskSearchScreen(
                navController = nav, initialCategoryId = catId, initialDueChoice = initialDueChoice
            )
        }

        /* 2. category management screen */
        composable("categories") { CategoryListScreen(nav) }
    }
}


@Composable
private fun SplashScreen(isLoading: Boolean, error: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.todo_colour)),
        contentAlignment = Alignment.Center
    ) {
        when {
            error != null -> Text(error, color = Color.White, textAlign = TextAlign.Center)
            isLoading -> CircularProgressIndicator(color = Color.White)
        }
    }
}