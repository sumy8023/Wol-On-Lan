package com.example.wolquicktile

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wolquicktile.ui.screen.WolApp
import com.example.wolquicktile.ui.screen.WolViewModel
import com.example.wolquicktile.ui.screen.WolViewModelFactory
import com.example.wolquicktile.ui.theme.WolQuickTileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 14251)
        }
        val app = application as WolQuickTileApp
        setContent {
            WolQuickTileTheme {
                val viewModel: WolViewModel = viewModel(
                    factory = WolViewModelFactory(
                        app.repository,
                        app.proxySettingsRepository,
                        applicationContext
                    )
                )
                WolApp(viewModel = viewModel)
            }
        }
    }
}
