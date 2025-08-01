package com.taskfree.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher") // required for sqlcipher-android
    }
}