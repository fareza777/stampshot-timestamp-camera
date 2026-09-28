package com.stampshot.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stampshot.app.data.AppSettings
import com.stampshot.app.data.SettingsRepository
import com.stampshot.app.stamp.PrivacyLevel
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { CAMERA, GALLERY }

class MainViewModel(val repo: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _screen = MutableStateFlow(Screen.CAMERA)
    val screen: StateFlow<Screen> = _screen

    fun navigate(to: Screen) { _screen.value = to }

    fun setStyle(style: StampStyle) = viewModelScope.launch { repo.setStyle(style) }
    fun setShowAddress(v: Boolean) = viewModelScope.launch { repo.setShowAddress(v) }
    fun setShowGps(v: Boolean) = viewModelScope.launch { repo.setShowGps(v) }
    fun setNote(v: String) = viewModelScope.launch { repo.setNote(v) }
    fun setSession(name: String) = viewModelScope.launch { repo.setSession(name) }
    fun setFlashMode(v: Int) = viewModelScope.launch { repo.setFlashMode(v) }
    fun setLensFacingBack(v: Boolean) = viewModelScope.launch { repo.setLensFacingBack(v) }
    fun setShareLevel(v: PrivacyLevel) = viewModelScope.launch { repo.setShareLevel(v) }
    fun setKeepOriginals(v: Boolean) = viewModelScope.launch { repo.setKeepOriginals(v) }
}
