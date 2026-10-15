package dev.m4s4t0.prism

import android.app.Application
import dev.m4s4t0.prism.data.LibraryRepository
import dev.m4s4t0.prism.data.SettingsRepository
import dev.m4s4t0.prism.data.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons. */
class PrismApp : Application() {
    lateinit var storage: Storage
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Scope for work that must outlive a screen (e.g. writing playtime on exit). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        storage = Storage(this)
        library = LibraryRepository(this, storage)
        settings = SettingsRepository(this)
    }

    companion object {
        lateinit var instance: PrismApp
            private set
    }
}
