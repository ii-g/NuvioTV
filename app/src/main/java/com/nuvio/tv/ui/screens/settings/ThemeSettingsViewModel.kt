package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.player.UiFont
import com.nuvio.tv.core.player.UiFontStore
import com.nuvio.tv.core.player.UiFonts
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.SubtitleFontUploadServer
import com.nuvio.tv.core.server.UiFontWebPage
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.availableAppThemes
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.model.resolveCustomThemeColors
import com.nuvio.tv.launcher.AppIconManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ThemeSettingsUiState(
    val themesLoaded: Boolean = false,
    val selectedTheme: AppTheme = AppTheme.WHITE,
    val customThemeColors: CustomThemeColors = CustomThemeColors.solid(CustomThemeColors.Default.second),
    val customThemeGradientEnabled: Boolean = false,
    val availableThemes: List<AppTheme> = availableAppThemes(CosmeticEntitlements.None),
    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val customFonts: List<UiFont> = emptyList(),
    val selectedCustomUiFontId: String? = null,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val availableSettingsUiStyles: List<SettingsUiStyle> = SettingsUiStyle.entries.toList()
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SaveCustomTheme(val colors: CustomThemeColors) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class SelectCustomFont(val font: UiFont) : ThemeSettingsEvent()
    data class RemoveCustomFont(val font: UiFont) : ThemeSettingsEvent()
    data object StartUiFontImport : ThemeSettingsEvent()
    data object StopUiFontImport : ThemeSettingsEvent()
    data class ToggleAmoledMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleAmoledSurfacesMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class SelectSettingsUiStyle(val style: SettingsUiStyle) : ThemeSettingsEvent()
    data object DismissAppIconFailure : ThemeSettingsEvent()
}

data class UiFontImportState(
    val isActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val error: String? = null
)

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themeDataStore: ThemeDataStore,
    private val memberAccessRepository: MemberAccessRepository,
    private val appIconManager: AppIconManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()
    val appIconState = appIconManager.state

    private val store = UiFontStore.get(context)
    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }

    private val mutableImportState = MutableStateFlow(UiFontImportState())
    val importState: StateFlow<UiFontImportState> = mutableImportState.asStateFlow()
    private var server: SubtitleFontUploadServer? = null

    private var restoreStyleFocus = false

    fun consumeStyleFocusRestore(): Boolean {
        val pending = restoreStyleFocus
        restoreStyleFocus = false
        return pending
    }

    init {
        viewModelScope.launch {
            combine(
                themeDataStore.themeSelection,
                memberAccessRepository.access
            ) { selection, memberAccess ->
                val entitlements = memberAccess.entitlements
                Triple(
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, entitlements),
                        customColors = resolveCustomThemeColors(selection.customColors, memberAccess.tier)
                    ),
                    availableAppThemes(entitlements),
                    memberAccess.tier != null
                )
            }
                .distinctUntilChanged()
                .collectLatest { (selection, availableThemes, gradientEnabled) ->
                    _uiState.update { state ->
                        state.copy(
                            themesLoaded = true,
                            selectedTheme = selection.theme ?: AppTheme.WHITE,
                            customThemeColors = selection.customColors,
                            customThemeGradientEnabled = gradientEnabled,
                            availableThemes = availableThemes
                        )
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.selectedFont
                .distinctUntilChanged()
                .collectLatest { font ->
                    _uiState.update { state ->
                        if (state.selectedFont == font) state else state.copy(selectedFont = font)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.amoledMode
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.amoledMode == enabled) state else state.copy(amoledMode = enabled)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.amoledSurfacesMode
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.amoledSurfacesMode == enabled) state else state.copy(amoledSurfacesMode = enabled)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.settingsUiStyle
                .distinctUntilChanged()
                .collectLatest { style ->
                    _uiState.update { state ->
                        if (state.settingsUiStyle == style) state else state.copy(settingsUiStyle = style)
                    }
                }
        }
        viewModelScope.launch {
            store.fonts
                .distinctUntilChanged()
                .collectLatest { fonts ->
                    _uiState.update { state ->
                        if (state.customFonts == fonts) state else state.copy(customFonts = fonts)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.selectedCustomUiFontId
                .distinctUntilChanged()
                .collectLatest { id ->
                    _uiState.update { state ->
                        if (state.selectedCustomUiFontId == id) state else state.copy(selectedCustomUiFontId = id)
                    }
                }
        }
    }

    private fun currentTheme(): AppTheme {
        return _uiState.value.selectedTheme
    }

    fun onEvent(event: ThemeSettingsEvent) {
        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SaveCustomTheme -> saveCustomTheme(event.colors)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.SelectCustomFont -> selectCustomFont(event.font)
            is ThemeSettingsEvent.RemoveCustomFont -> removeCustomFont(event.font)
            ThemeSettingsEvent.StartUiFontImport -> startUiFontImport()
            ThemeSettingsEvent.StopUiFontImport -> stopUiFontImport()
            is ThemeSettingsEvent.ToggleAmoledMode -> setAmoledMode(event.enabled)
            is ThemeSettingsEvent.ToggleAmoledSurfacesMode -> setAmoledSurfacesMode(event.enabled)
            is ThemeSettingsEvent.SelectSettingsUiStyle -> selectSettingsUiStyle(event.style)
            ThemeSettingsEvent.DismissAppIconFailure -> appIconManager.clearFailure()
        }
    }

    fun selectAppIcon(option: AppIconOption): Boolean = appIconManager.select(option)

    private fun selectTheme(theme: AppTheme) {
        if (currentTheme() == theme) return
        viewModelScope.launch {
            val access = memberAccessRepository.access.value
            if (theme !in availableAppThemes(access.entitlements)) return@launch
            themeDataStore.setTheme(theme)
        }
    }

    private fun saveCustomTheme(colors: CustomThemeColors) {
        viewModelScope.launch {
            val access = memberAccessRepository.access.value
            themeDataStore.setCustomTheme(resolveCustomThemeColors(colors, access.tier))
        }
    }

    private fun selectFont(font: AppFont) {
        if (_uiState.value.selectedFont == font && _uiState.value.selectedCustomUiFontId == null) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
            themeDataStore.setCustomUiFontId(null)
        }
    }

    private fun selectCustomFont(font: UiFont) {
        if (_uiState.value.selectedCustomUiFontId == font.id) return
        viewModelScope.launch {
            themeDataStore.setCustomUiFontId(font.id)
        }
    }

    private fun removeCustomFont(font: UiFont) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(font.id) }
            if (_uiState.value.selectedCustomUiFontId == font.id) {
                themeDataStore.setCustomUiFontId(null)
            }
        }
    }

    fun startUiFontImport() {
        stopServer()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            showImportError(R.string.error_network_required)
            return
        }
        val startedServer = SubtitleFontUploadServer.startOnAvailablePort(
            context = context,
            onFontUploaded = ::importUploadedFont,
            logoProvider = { logoBytes },
            pageHtml = { ctx -> UiFontWebPage.html(ctx) },
            maxBytes = UiFonts.MAX_BYTES
        )
        if (startedServer == null) {
            showImportError(R.string.error_server_ports_unavailable)
            return
        }
        server = startedServer
        val url = "http://$ip:${startedServer.listeningPort}"
        mutableImportState.value = UiFontImportState(
            isActive = true,
            qrCodeBitmap = QrCodeGenerator.generate(url, 512),
            serverUrl = url
        )
    }

    fun stopUiFontImport() {
        stopServer()
        mutableImportState.value = UiFontImportState()
    }

    private fun importUploadedFont(fileName: String, bytes: ByteArray): Boolean {
        val font = runCatching { store.import(fileName, bytes) }.getOrNull() ?: return false
        viewModelScope.launch {
            themeDataStore.setCustomUiFontId(font.id)
            delay(IMPORT_CLOSE_DELAY_MS)
            stopUiFontImport()
        }
        return true
    }

    private fun showImportError(message: Int) {
        mutableImportState.value = UiFontImportState(
            isActive = true,
            error = context.withAppLocale().getString(message)
        )
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }

    private fun setAmoledMode(enabled: Boolean) {
        if (_uiState.value.amoledMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledMode(enabled)
        }
    }

    private fun setAmoledSurfacesMode(enabled: Boolean) {
        if (_uiState.value.amoledSurfacesMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledSurfacesMode(enabled)
        }
    }

    private fun selectSettingsUiStyle(style: SettingsUiStyle) {
        if (_uiState.value.settingsUiStyle == style) return
        restoreStyleFocus = true
        viewModelScope.launch {
            themeDataStore.setSettingsUiStyle(style)
        }
    }

    private companion object {
        const val IMPORT_CLOSE_DELAY_MS = 1_000L
    }
}
