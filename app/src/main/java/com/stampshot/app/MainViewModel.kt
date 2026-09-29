package com.stampshot.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stampshot.app.data.AppSettings
import com.stampshot.app.data.SettingsRepository
import com.stampshot.app.stamp.DateFormatOption
import com.stampshot.app.stamp.GpsFormat
import com.stampshot.app.stamp.PrivacyLevel
import com.stampshot.app.stamp.StampAlign
import com.stampshot.app.stamp.StampFont
import com.stampshot.app.stamp.StampPosition
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

    /** Set by CameraScreen while it owns the volume keys; returns true when the press was consumed. */
    var volumeKeyHandler: (() -> Boolean)? = null

    fun setStyle(style: StampStyle) = viewModelScope.launch { repo.setStyle(style) }
    fun setShowAddress(v: Boolean) = viewModelScope.launch { repo.setShowAddress(v) }
    fun setShowGps(v: Boolean) = viewModelScope.launch { repo.setShowGps(v) }
    fun setNote(v: String) = viewModelScope.launch { repo.setNote(v) }
    fun setSession(name: String) = viewModelScope.launch { repo.setSession(name) }
    fun setFlashMode(v: Int) = viewModelScope.launch { repo.setFlashMode(v) }
    fun setLensFacingBack(v: Boolean) = viewModelScope.launch { repo.setLensFacingBack(v) }
    fun setShareLevel(v: PrivacyLevel) = viewModelScope.launch { repo.setShareLevel(v) }
    fun setKeepOriginals(v: Boolean) = viewModelScope.launch { repo.setKeepOriginals(v) }
    fun setFontScale(v: Float) = viewModelScope.launch { repo.setFontScale(v) }
    fun setFontColorArgb(v: Int) = viewModelScope.launch { repo.setFontColorArgb(v) }
    fun setBgColorArgb(v: Int) = viewModelScope.launch { repo.setBgColorArgb(v) }
    fun setTextOpacity(v: Float) = viewModelScope.launch { repo.setTextOpacity(v) }
    fun setBgOpacity(v: Float) = viewModelScope.launch { repo.setBgOpacity(v) }
    fun setStampPosition(v: StampPosition) = viewModelScope.launch { repo.setStampPosition(v) }
    fun setStampFont(v: StampFont) = viewModelScope.launch { repo.setStampFont(v) }
    fun setStampAlign(v: StampAlign) = viewModelScope.launch { repo.setStampAlign(v) }
    fun setActivity(v: String) = viewModelScope.launch { repo.setActivity(v) }
    fun setPersonName(v: String) = viewModelScope.launch { repo.setPersonName(v) }
    fun setDateFormat(v: DateFormatOption) = viewModelScope.launch { repo.setDateFormat(v) }
    fun setGpsFormat(v: GpsFormat) = viewModelScope.launch { repo.setGpsFormat(v) }
    fun setShowNumber(v: Boolean) = viewModelScope.launch { repo.setShowNumber(v) }
    fun setShowGrid(v: Boolean) = viewModelScope.launch { repo.setShowGrid(v) }
    fun setTimerSecs(v: Int) = viewModelScope.launch { repo.setTimerSecs(v) }
    fun setShutterSound(v: Boolean) = viewModelScope.launch { repo.setShutterSound(v) }
    fun setTouchToCapture(v: Boolean) = viewModelScope.launch { repo.setTouchToCapture(v) }
    fun setMirrorFront(v: Boolean) = viewModelScope.launch { repo.setMirrorFront(v) }
    fun setPhotoMaxDim(v: Int) = viewModelScope.launch { repo.setPhotoMaxDim(v) }
    fun setVolumeKeysCapture(v: Boolean) = viewModelScope.launch { repo.setVolumeKeysCapture(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(v) }
    fun setShowSeconds(v: Boolean) = viewModelScope.launch { repo.setShowSeconds(v) }
    fun setTime24h(v: Boolean) = viewModelScope.launch { repo.setTime24h(v) }
    fun setVideoQuality(v: Int) = viewModelScope.launch { repo.setVideoQuality(v) }
    fun setVideoAudio(v: Boolean) = viewModelScope.launch { repo.setVideoAudio(v) }
}
