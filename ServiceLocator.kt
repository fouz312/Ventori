package com.vantora.app.core

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.vantora.app.apk.ApkManager
import com.vantora.app.compat.CompatEngine
import com.vantora.app.library.LibraryRepository
import com.vantora.app.vm.VmManager
import java.io.File

/** Minimal dependency container (keeps the modules decoupled without a DI framework). */
object ServiceLocator {
    lateinit var app: Application
        private set

    fun init(application: Application) { app = application }

    val prefs by lazy { app.getSharedPreferences("vantora", Context.MODE_PRIVATE) }
    val db: AppDb by lazy { Room.databaseBuilder(app, AppDb::class.java, "vantora.db").build() }
    val library by lazy { LibraryRepository(app, db) }
    val vmManager by lazy { VmManager(app, db) }
    val apk by lazy { ApkManager(app) }
    val compat by lazy { CompatEngine(File(app.filesDir, "runtimes")) { prefsHasVm } }

    @Volatile var prefsHasVm: Boolean = false
}
