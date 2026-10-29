package com.injectra.browser

/**
 * A private window. Runs in the ":incognito" process (see AndroidManifest and
 * InjectraApp), so it never shares cookies or site data with normal windows,
 * and everything is erased when it closes.
 */
class PrivateActivity : MainActivity() {
    override val isPrivate: Boolean = true
}
