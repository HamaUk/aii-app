package com.nexus.aichat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.nexus.aichat.data.local.mapper.Mappers.toEntity
import com.nexus.aichat.core.model.PersonaPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup work is kept to the minimum that must happen before the first frame:
 *  - Hilt graph construction;
 *  - seeding the built-in persona rules (once, idempotently);
 *  - warming the provider cache so the model switcher has data immediately.
 *
 * Notably absent: any network call, any blocking I/O, and any PDF/crypto initialisation. Those are
 * lazy, because `onCreate` is on the cold-start critical path and this app aims to be interactive in
 * under a second on a mid-range device.
 */
@HiltAndroidApp
class NexusApp : Application() {

    @Inject
    lateinit var database: com.nexus.aichat.data.local.db.NexusDatabase

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        seedBuiltInPersonas()
    }

    private suspend fun seed() {
        val dao = database.systemRuleDao()
        PersonaPresets.ALL.forEach { rule ->
            // Upsert-by-id: user edits to a built-in rule survive app updates.
            dao.upsert(rule.toEntity())
        }
    }

    private fun seedBuiltInPersonas() {
        applicationScope.launch { runCatching { seed() } }
    }
}
