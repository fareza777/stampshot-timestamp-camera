package com.stampshot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stampshot.app.data.SettingsRepository
import com.stampshot.app.ui.StampShotTheme
import com.stampshot.app.ui.camera.CameraScreen
import com.stampshot.app.ui.gallery.GalleryScreen

class MainActivity : ComponentActivity() {

    private var viewModelRef: MainViewModel? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            viewModelRef?.volumeKeyHandler?.invoke() == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            StampShotTheme {
                val vm: MainViewModel = viewModel(factory = viewModelFactory {
                    initializer { MainViewModel(SettingsRepository(applicationContext)) }
                })
                viewModelRef = vm
                val screen by vm.screen.collectAsState()
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.CAMERA -> CameraScreen(
                            viewModel = vm,
                            onOpenGallery = { vm.navigate(Screen.GALLERY) },
                        )
                        Screen.GALLERY -> GalleryScreen(
                            viewModel = vm,
                            onBack = { vm.navigate(Screen.CAMERA) },
                        )
                    }
                }
            }
        }
    }
}
