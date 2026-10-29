package com.injectra.browser

import android.app.Application
import android.webkit.WebView
import java.io.File

/**
 * Private windows run in their own process (":incognito") with their own WebView
 * data directory, so they get a completely separate cookie jar, cache, local
 * storage and IndexedDB from normal windows. Whatever a previous private
 * session left behind is deleted here, before WebView can load it.
 */
class InjectraApp : Application() {

    companion object {
        const val PRIVATE_SUFFIX = "incognito"
    }

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(":$PRIVATE_SUFFIX")) {
            wipePrivateData()
            // Must run before any WebView is created in this process.
            WebView.setDataDirectorySuffix(PRIVATE_SUFFIX)
        }
    }

    private fun wipePrivateData() {
        val dirs = listOfNotNull(dataDir.listFiles(), cacheDir.listFiles()).flatMap { it.asList() }
        for (f in dirs) {
            if (f.name.contains(PRIVATE_SUFFIX)) f.deleteRecursively()
        }
        File(cacheDir, "WebView").listFiles()?.forEach {
            if (it.name.contains(PRIVATE_SUFFIX)) it.deleteRecursively()
        }
    }
}
