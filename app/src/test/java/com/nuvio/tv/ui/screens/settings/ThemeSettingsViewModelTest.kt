package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.player.UiFont
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.ThemeSelection
import com.nuvio.tv.launcher.AppIconManager
import com.nuvio.tv.launcher.AppIconSettingsState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val gradient = CustomThemeColors(0x112233, 0x445566, 0x778899)
    private val solid = CustomThemeColors.solid(0x445566)
    private val selection = MutableStateFlow(ThemeSelection(AppTheme.CUSTOM, gradient))
    private val access = MutableStateFlow(MemberAccess.None)
    private val customFontId = MutableStateFlow<String?>(null)
    // UiFontStore.get(context) reads the font index from context.applicationContext.filesDir
    // at ViewModel construction; point it at a scratch dir so no test writes land in the repo.
    private val context: Context = mockk {
        every { applicationContext } returns mockk {
            every { filesDir } returns File(System.getProperty("java.io.tmpdir"), "nuvio-theme-settings-test")
        }
    }
    private val store = mockk<ThemeDataStore> {
        every { themeSelection } returns selection
        every { selectedFont } returns flowOf(AppFont.INTER)
        every { selectedCustomUiFontId } returns customFontId
        every { amoledMode } returns flowOf(false)
        every { amoledSurfacesMode } returns flowOf(false)
        every { settingsUiStyle } returns flowOf(SettingsUiStyle.CLASSIC)
        coEvery { setCustomTheme(any()) } coAnswers {
            selection.value = ThemeSelection(AppTheme.CUSTOM, firstArg())
        }
        coEvery { setFont(any()) } just Runs
        coEvery { setCustomUiFontId(any()) } coAnswers { customFontId.value = firstArg() }
        coEvery { setCustomUiFontId(null) } coAnswers { customFontId.value = null }
    }

    @Test
    fun nonMembersSeeAndSaveOnlySingleColorCustomThemes() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        assertTrue(AppTheme.CUSTOM in viewModel.uiState.value.availableThemes)
        assertEquals(AppTheme.CUSTOM, viewModel.uiState.value.selectedTheme)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
        assertFalse(viewModel.uiState.value.customThemeGradientEnabled)

        viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
        runCurrent()

        coVerify(exactly = 1) { store.setCustomTheme(solid) }
        assertEquals(ThemeSelection(AppTheme.CUSTOM, solid), selection.value)
    }

    @Test
    fun bothMemberTiersCanSaveAllGradientStops() = runTest {
        val viewModel = createViewModel()

        MemberTier.entries.forEach { tier ->
            access.value = MemberAccess.preview(tier)
            runCurrent()

            assertTrue(viewModel.uiState.value.customThemeGradientEnabled)
            assertEquals(gradient, viewModel.uiState.value.customThemeColors)
            viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
            runCurrent()
        }

        coVerify(exactly = MemberTier.entries.size) { store.setCustomTheme(gradient) }
    }

    @Test
    fun membershipLossKeepsSavedGradientForRenewal() = runTest {
        access.value = MemberAccess.preview(MemberTier.SUPPORTER)
        val viewModel = createViewModel()
        runCurrent()

        access.value = MemberAccess.None
        runCurrent()

        assertEquals(AppTheme.CUSTOM, viewModel.uiState.value.selectedTheme)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
        assertFalse(viewModel.uiState.value.customThemeGradientEnabled)
        assertEquals(gradient, selection.value.customColors)

        access.value = MemberAccess.preview(MemberTier.SUPPORTER_PLUS)
        runCurrent()

        assertEquals(gradient, viewModel.uiState.value.customThemeColors)
        assertTrue(viewModel.uiState.value.customThemeGradientEnabled)
        coVerify(exactly = 0) { store.setCustomTheme(any()) }
    }

    @Test
    fun membershipLossBeforeSaveConvertsTheOldEditorDraftToOneColor() = runTest {
        access.value = MemberAccess.preview(MemberTier.SUPPORTER)
        val viewModel = createViewModel()
        runCurrent()

        access.value = MemberAccess.None
        viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
        runCurrent()

        coVerify(exactly = 1) { store.setCustomTheme(solid) }
        coVerify(exactly = 0) { store.setCustomTheme(gradient) }
    }

    @Test
    fun membershipChangesUpdateEditorModeEvenWhenColorsAndEntitlementsStayTheSame() = runTest {
        selection.value = ThemeSelection(AppTheme.CUSTOM, solid)
        val viewModel = createViewModel()
        runCurrent()
        val availableThemes = viewModel.uiState.value.availableThemes

        access.value = MemberAccess(tier = MemberTier.SUPPORTER)
        runCurrent()

        assertTrue(viewModel.uiState.value.customThemeGradientEnabled)
        assertEquals(availableThemes, viewModel.uiState.value.availableThemes)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
    }

    @Test
    fun selectCustomFontSetsCustomIdAndLeavesBuiltInFontUntouched() = runTest {
        val viewModel = createViewModel()
        runCurrent()
        val font = UiFont("custom-11111111-1111-1111-1111-111111111111", "Test Font")

        viewModel.onEvent(ThemeSettingsEvent.SelectCustomFont(font))
        runCurrent()

        assertEquals(font.id, viewModel.uiState.value.selectedCustomUiFontId)
        assertEquals(AppFont.INTER, viewModel.uiState.value.selectedFont)
        coVerify(exactly = 1) { store.setCustomUiFontId(font.id) }
        coVerify(exactly = 0) { store.setFont(any()) }
    }

    @Test
    fun selectBuiltInFontClearsPreviouslySelectedCustomFont() = runTest {
        customFontId.value = "custom-11111111-1111-1111-1111-111111111111"
        val viewModel = createViewModel()
        runCurrent()
        assertEquals(customFontId.value, viewModel.uiState.value.selectedCustomUiFontId)

        viewModel.onEvent(ThemeSettingsEvent.SelectFont(AppFont.INTER))
        runCurrent()

        coVerify(exactly = 1) { store.setFont(AppFont.INTER) }
        coVerify(exactly = 1) { store.setCustomUiFontId(null) }
        assertEquals(null, viewModel.uiState.value.selectedCustomUiFontId)
        assertEquals(AppFont.INTER, viewModel.uiState.value.selectedFont)
    }

    // No test for RemoveCustomFont: it switches to Dispatchers.IO (a real dispatcher the
    // MainDispatcherRule does not replace, so its completion cannot be awaited with
    // runCurrent()/advanceUntilIdle()) and it calls the real static UiFontStore singleton,
    // which would write the font index to disk. It needs a seam in production code first.
    private fun createViewModel(): ThemeSettingsViewModel {
        val members = mockk<MemberAccessRepository> { every { access } returns this@ThemeSettingsViewModelTest.access }
        val icons = mockk<AppIconManager> { every { state } returns MutableStateFlow(AppIconSettingsState()) }
        return ThemeSettingsViewModel(context, store, members, icons)
    }
}
