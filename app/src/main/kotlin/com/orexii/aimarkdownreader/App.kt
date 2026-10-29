package com.orexii.aimarkdownreader

import android.app.Application
import com.orexii.aimarkdownreader.data.Library
import com.orexii.aimarkdownreader.data.SettingsStore
import com.orexii.aimarkdownreader.reader.ReaderController
import com.orexii.aimarkdownreader.speech.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide objects: reading continues while the app is in the background. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore
        private set
    lateinit var library: Library
        private set
    lateinit var models: ModelStore
        private set
    lateinit var reader: ReaderController
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, scope)
        library = Library(this, scope)
        models = ModelStore(this, scope)
        reader = ReaderController(this, scope, settings, library, models)
    }
}
