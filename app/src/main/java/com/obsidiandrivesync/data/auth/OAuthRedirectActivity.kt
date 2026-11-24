package com.obsidiandrivesync.data.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.obsidiandrivesync.MainActivity
import com.obsidiandrivesync.VaultSyncApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val app = application as VaultSyncApplication
        CoroutineScope(Dispatchers.Main).launch {
            if (uri != null) {
                app.container.oauthManager.handleRedirectUri(uri)
            }
            val mainIntent = Intent(this@OAuthRedirectActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(mainIntent)
            finish()
        }
    }
}
