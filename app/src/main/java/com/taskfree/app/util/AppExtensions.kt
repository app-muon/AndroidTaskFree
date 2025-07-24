// AppExtensions.kt
package com.taskfree.app.util

import android.content.Context
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.database.AppDatabase

val Context.db: AppDatabase
    get() = AppDatabaseFactory.getDatabase(this)