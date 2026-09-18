package io.github.nlinker.rutubedl

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptSharedUrl(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MaterialTheme {
                // Two screens, one activity: a flag in the ViewModel is enough,
                // and it survives rotation for free.
                if (state.showSettings) SettingsScreen(viewModel) else MainScreen(viewModel)
            }
        }
    }

    // singleTop: a share while the app is already open lands here, not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptSharedUrl(intent)
    }

    private fun acceptSharedUrl(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::setUrl)
    }
}
