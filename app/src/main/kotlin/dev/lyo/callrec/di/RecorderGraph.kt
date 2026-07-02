package com.coolappstore.evercallrecorder.by.svhp.di

import android.content.Context

/**
 * Process-wide holder for the recorder's manual-DI graph.
 *
 * Most code reaches the container via `(application as App).container`, but
 * context-less singletons (the report queue, the boot receiver) can't. They
 * read [container] instead; [App] installs the single instance once in
 * onCreate via [install], so both paths share the same [AppContainer].
 */
object RecorderGraph {

    @Volatile
    private var instance: AppContainer? = null

    val container: AppContainer
        get() = instance
            ?: error("RecorderGraph not installed; call RecorderGraph.install() in Application.onCreate")

    fun install(context: Context): AppContainer =
        instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
}
