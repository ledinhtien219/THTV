package com.carhud.aaproxy

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

/** Compatibility entry point for assistants that deliver a play-from-search Intent. */
class SystemVoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(command: Intent?) {
        if (command?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val result = SystemVoiceModule.submit(this, command.getStringExtra(SearchManager.QUERY), command.extras, "Mic hệ thống (Intent)")
            if (result != SystemVoiceModule.Result.ACCEPTED) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
