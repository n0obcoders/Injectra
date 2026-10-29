package com.injectra.browser

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * The launcher screen. Shows the "about the developer" card for 3 seconds, then
 * opens the browser. Tapping it skips straight through.
 */
class SplashActivity : AppCompatActivity() {

    private var moved = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        findViewById<android.view.View>(R.id.splashRoot).setOnClickListener { go() }
        handler.postDelayed({ go() }, 3000)
    }

    private fun go() {
        if (moved) return
        moved = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
