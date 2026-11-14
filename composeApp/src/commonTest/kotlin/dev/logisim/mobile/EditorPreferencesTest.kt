package dev.logisim.mobile

import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditorPreferencesTest {
    private val header = "LOGISIM-EDITOR-PREFERENCES\n2\n"

    @Test
    fun gateShapeDefaultFollowsTheSystemLanguageLikeEvolution() {
        // AppPreferences.getDefaultGateShape(): rectangular for a Russian system locale, shaped otherwise.
        assertEquals(EditorGateShape.IEC, defaultEditorGateShape(AppLanguage.RUSSIAN))
        AppLanguage.entries.filter { it != AppLanguage.RUSSIAN }.forEach { assertEquals(EditorGateShape.ANSI, defaultEditorGateShape(it), it.tag) }
        assertEquals(EditorGateShape.ANSI, EditorPreferencesCodec.read(header + "gateShape=ANSI\n").preferences.gateShape)
    }

    @Test
    fun serializesEveryTypedPreferenceAndPreservesUnknownValues() {
        val preferences = EditorPreferences(
            locale = EditorLocale.ENGLISH, showGrid = false, snapToGrid = false, highContrastSignals = true,
            signalRadix = EditorSignalRadix.SIGNED_DECIMAL, defaultTickFrequencyHz = 2.5,
            gateShape = EditorGateShape.IEC, shortcutScheme = EditorShortcutScheme.CONTROL,
            toolbarPlacement = EditorToolbarPlacement.DOWN_MIDDLE,
            wirePokePrimaryRadix = WirePokeRadix.OCTAL, wirePokeSecondaryRadix = WirePokeRadix.FLOATING_POINT,
            showHeaderBar = false,
            showPlacementPreview = false, afterPlacement = EditorAfterPlacement.KEEP_TOOL,
            extraValues = mapOf("extensão" to "a=b\nc\\d", "future" to "1"),
            showTickRate = true,
        )
        val encoded = EditorPreferencesCodec.write(preferences)
        val loaded = EditorPreferencesCodec.read(encoded)
        assertTrue(loaded.canPersist)
        assertTrue(loaded.diagnostics.isEmpty())
        assertEquals(preferences, loaded.preferences)
        assertEquals(encoded, EditorPreferencesCodec.write(loaded.preferences))
        assertTrue("extens%C3%A3o=" in encoded)
    }

    @Test
    fun shortcutAssignmentsRoundTripEvenWhenTheyReuseAFreedChord() {
        val preferences = EditorPreferences(
            showPlacementPreview = false,
            afterPlacement = EditorAfterPlacement.KEEP_TOOL,
            colors = mapOf(EditorColor.CANVAS to 0x104000),
            shortcutOverrides = mapOf(
                EditorShortcut.NEW to EditorShortcutBinding("O"),
                EditorShortcut.OPEN to EditorShortcutBinding("J", shift = true),
            ),
        )
        val loaded = EditorPreferencesCodec.read(EditorPreferencesCodec.write(preferences))
        assertEquals(preferences, loaded.preferences)
        assertTrue(loaded.notices.isEmpty())
    }

    @Test
    fun invalidAndConflictingSavedShortcutsFallBackWithoutLosingOtherPreferences() {
        val loaded = EditorPreferencesCodec.read(header + """
            showGrid=false
            shortcut.NEW=O
            shortcut.SAVE=invalid
            shortcut.RESET=F8
        """.trimIndent())
        assertEquals(mapOf(EditorShortcut.RESET to EditorShortcutBinding("F8")), loaded.preferences.shortcutOverrides)
        assertFalse(loaded.preferences.showGrid)
        assertEquals(2, loaded.notices.size)
        assertTrue(loaded.canPersist)
    }

    @Test
    fun contextualShortcutsSurviveReloadWithoutTakingOverPrimaryOrFixedCanvasCommands() {
        val overrides = mapOf(
            EditorShortcut.FACE_NORTH to EditorShortcutBinding("J", shift = true, primary = false),
            EditorShortcut.PLACEMENT_AUTO_LABEL to EditorShortcutBinding("↑", primary = false),
        )
        val serialized = EditorPreferencesCodec.write(EditorPreferences(shortcutOverrides = overrides))
        val loaded = EditorPreferencesCodec.read(serialized)
        assertEquals(overrides, loaded.preferences.shortcutOverrides)
        assertTrue(loaded.notices.isEmpty())

        val invalid = EditorPreferencesCodec.read(header + "shortcut.FACE_NORTH=J\nshortcut.FACE_EAST=Plain%2BX\n")
        assertTrue(invalid.preferences.shortcutOverrides.isEmpty())
        assertEquals(2, invalid.notices.size)
    }

    @Test
    fun missingPreferencesUseDefaultsWithoutAnError() {
        assertEquals(EditorPreferencesLoad(), EditorPreferencesCodec.read(null))
        assertEquals(EditorPreferencesLoad(), EditorPreferencesCodec.read(""))
        assertFalse(EditorPreferencesCodec.read(header + "showGrid=false\n").preferences.showTickRate)
    }

    @Test
    fun persistsEveryAvailableLanguageAndFallsBackForUnknownSavedTags() {
        assertEquals(setOf("system", "de", "el", "en", "es", "fr", "pt-BR", "ru", "it", "nl", "ja", "pl", "tr", "zh"),
            EditorLocale.available.map { it.tag }.toSet())
        val storage = TestPreferencesStorage(null)
        for (locale in EditorLocale.available) {
            assertEquals(PreferencesSaveResult.Success,
                EditorPreferencesRepository(storage).save(EditorPreferences(locale = locale)))
            val reloaded = EditorPreferencesRepository(storage).load()
            assertEquals(locale, reloaded.preferences.locale)
            assertTrue(reloaded.notices.isEmpty())
        }
        assertEquals(EditorLocale.SYSTEM, EditorPreferencesCodec.read(header + "locale=ar-EG\n").preferences.locale)
    }

    @Test
    fun readsPreferencesWithPaddingReturnedByAndroidSharedPreferences() {
        val serialized = header + """
            defaultTickFrequencyHz=4.0
            gateShape=DIN40700
            highContrastSignals=false
            locale=en
            shortcutScheme=PLATFORM
            showGrid=true
            signalRadix=BINARY
            snapToGrid=true
            toolbarPlacement=TOP
        """.trimIndent() + "\n    "
        val loaded = EditorPreferencesCodec.read(serialized)
        assertEquals(EditorPreferences(locale = EditorLocale.ENGLISH, gateShape = EditorGateShape.DIN40700, defaultTickFrequencyHz = 4.0), loaded.preferences)
        assertTrue(loaded.canPersist)
        assertTrue(loaded.notices.isEmpty())
        assertFalse(EditorPreferencesCodec.read(serialized + "broken").canPersist)
    }

    @Test
    fun oldWirePokeRadixIsPreservedAndTheSecondaryReadingCanBeDisabledIndependently() {
        val storage = TestPreferencesStorage(header + "signalRadix=HEXADECIMAL\n")
        val repository = EditorPreferencesRepository(storage)
        val previous = repository.load().preferences
        assertEquals(WirePokeRadix.HEXADECIMAL, previous.wirePokePrimaryRadix)
        assertEquals(WirePokeRadix.SIGNED_DECIMAL, previous.wirePokeSecondaryRadix)

        assertEquals(PreferencesSaveResult.Success, repository.save(previous.copy(
            signalRadix = EditorSignalRadix.BINARY, wirePokeSecondaryRadix = null,
        )))
        val reloaded = EditorPreferencesRepository(storage).load()
        assertTrue(reloaded.notices.isEmpty())
        assertEquals(EditorSignalRadix.BINARY, reloaded.preferences.signalRadix)
        assertEquals(WirePokeRadix.HEXADECIMAL, reloaded.preferences.wirePokePrimaryRadix)
        assertEquals(null, reloaded.preferences.wirePokeSecondaryRadix)
    }

    @Test
    fun olderPreferencesKeepTheTopToolbarAndInvalidPlacementReportsItsFallback() {
        assertEquals(EditorToolbarPlacement.TOP, EditorPreferencesCodec.read(header + "showGrid=false\n").preferences.toolbarPlacement)
        val invalid = EditorPreferencesCodec.read(header + "toolbarPlacement=UNKNOWN\n")
        assertEquals(EditorToolbarPlacement.TOP, invalid.preferences.toolbarPlacement)
        assertEquals(1, invalid.notices.size)
        assertTrue(invalid.canPersist)
    }

    @Test
    fun headerVisibilityPersistsIndependentlyOfTheEditingToolbar() {
        val storage = TestPreferencesStorage(header + "toolbarPlacement=HIDDEN\n")
        val initial = EditorPreferencesRepository(storage).load().preferences
        assertTrue(initial.showHeaderBar)
        assertEquals(EditorToolbarPlacement.HIDDEN, initial.toolbarPlacement)

        assertEquals(PreferencesSaveResult.Success, EditorPreferencesRepository(storage)
            .save(initial.copy(showHeaderBar = false)))
        val hidden = EditorPreferencesRepository(storage).load().preferences
        assertFalse(hidden.showHeaderBar)
        assertEquals(EditorToolbarPlacement.HIDDEN, hidden.toolbarPlacement)

        assertEquals(PreferencesSaveResult.Success, EditorPreferencesRepository(storage)
            .save(hidden.copy(toolbarPlacement = EditorToolbarPlacement.LEFT)))
        val changed = EditorPreferencesRepository(storage).load().preferences
        assertFalse(changed.showHeaderBar)
        assertEquals(EditorToolbarPlacement.LEFT, changed.toolbarPlacement)
    }

    @Test
    fun invalidNumbersAndEnumsUseExplicitDefaultsWithDiagnostics() {
        for (frequency in listOf("0", "-1", "NaN", "Infinity", "not-a-number")) {
            val loaded = EditorPreferencesCodec.read(header + "defaultTickFrequencyHz=$frequency\n")
            assertEquals(1.0, loaded.preferences.defaultTickFrequencyHz)
            assertEquals(1, loaded.diagnostics.size)
        }
        val loaded = EditorPreferencesCodec.read(header + "locale=missing\nshowGrid=perhaps\nsignalRadix=7\n")
        assertEquals(EditorPreferences(), loaded.preferences)
        assertEquals(3, loaded.diagnostics.size)
        assertTrue(loaded.canPersist)
    }

    @Test
    fun migratesVersionOneAliasesWithoutDuplicatingEngineOptions() {
        val legacy = "LOGISIM-EDITOR-PREFERENCES\n1\ngrid=false\nsnap=false\nhighContrast=true\nlanguage=pt_BR\nradix=16\ntickFrequency=0.5\nprivateOption=keep\n"
        val loaded = EditorPreferencesCodec.read(legacy)
        assertEquals(1, loaded.migratedFromVersion)
        assertEquals(EditorLocale.PORTUGUESE, loaded.preferences.locale)
        assertFalse(loaded.preferences.showGrid)
        assertFalse(loaded.preferences.snapToGrid)
        assertTrue(loaded.preferences.highContrastSignals)
        assertEquals(EditorSignalRadix.HEXADECIMAL, loaded.preferences.signalRadix)
        assertEquals(0.5, loaded.preferences.defaultTickFrequencyHz)
        assertEquals(mapOf("privateOption" to "keep"), loaded.preferences.extraValues)
        val encoded = EditorPreferencesCodec.write(loaded.preferences)
        assertTrue(encoded.startsWith(header))
        assertFalse("tickFrequency=" in encoded)
        assertFalse("gateUndefined" in encoded)
    }

    @Test
    fun currentKeysTakePrecedenceOverAliasesDuringMigration() {
        val loaded = EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n1\ngrid=false\nshowGrid=true\nlanguage=pt_BR\nlocale=en\n")
        assertTrue(loaded.preferences.showGrid)
        assertEquals(EditorLocale.ENGLISH, loaded.preferences.locale)
        assertTrue(loaded.preferences.extraValues.isEmpty())
    }

    @Test
    fun malformedAndFutureFormatsCannotBeOverwrittenImplicitly() {
        for (original in listOf("unknown", "LOGISIM-EDITOR-PREFERENCES\n9\nshowGrid=false\n",
            header + "showGrid=true\nshowGrid=false\n", header + "broken", header + "x=%-A", header + "x=%FF")) {
            val storage = TestPreferencesStorage(original)
            val repository = EditorPreferencesRepository(storage)
            val loaded = repository.load()
            assertFalse(loaded.canPersist)
            assertIs<PreferencesSaveResult.Failure>(repository.save(EditorPreferences(showGrid = false)))
            assertEquals(original, storage.contents)
            assertEquals(0, storage.writes)
        }
    }

    @Test
    fun savingBeforeLoadStillProtectsFutureFormats() {
        val original = "LOGISIM-EDITOR-PREFERENCES\n99\nfuture=keep\n"
        val storage = TestPreferencesStorage(original)
        val repository = EditorPreferencesRepository(storage)
        assertIs<PreferencesSaveResult.Failure>(repository.save(EditorPreferences()))
        assertEquals(original, storage.contents)
        assertEquals(0, storage.writes)
    }

    @Test
    fun anExplicitRestoreReplacesUnsupportedPreferencesWithUsableDefaults() {
        val storage = TestPreferencesStorage("LOGISIM-EDITOR-PREFERENCES\n99\nfuture=keep\n")
        val repository = EditorPreferencesRepository(storage)
        assertFalse(repository.load().canPersist)
        assertEquals(PreferencesSaveResult.Success, repository.restoreDefaults())
        assertEquals(EditorPreferences(), EditorPreferencesRepository(storage).load().preferences)
        assertEquals(PreferencesSaveResult.Success, repository.save(EditorPreferences(showGrid = false)))
        assertFalse(EditorPreferencesRepository(storage).load().preferences.showGrid)
    }

    @Test
    fun preferencesSurviveRepositoryReplacementAndReportStorageFailures() {
        val storage = TestPreferencesStorage(null)
        val preferences = EditorPreferences(locale = EditorLocale.PORTUGUESE, highContrastSignals = true,
            toolbarPlacement = EditorToolbarPlacement.DOWN_MIDDLE)
        assertEquals(PreferencesSaveResult.Success, EditorPreferencesRepository(storage).save(preferences))
        assertEquals(preferences, EditorPreferencesRepository(storage).load().preferences)
        storage.failWrites = true
        assertIs<PreferencesSaveResult.Failure>(EditorPreferencesRepository(storage).save(EditorPreferences()))
        assertEquals(preferences, EditorPreferencesCodec.read(storage.contents).preferences)
    }

    @Test
    fun formatsKnownValuesAtAllWidthsWithoutChangingTheirMeaning() {
        assertEquals("00101010", formatEditorSignal(LogicVector.of(8, 42uL), EditorSignalRadix.BINARY))
        assertEquals("0x2A", formatEditorSignal(LogicVector.of(8, 42uL), EditorSignalRadix.HEXADECIMAL))
        assertEquals("255", formatEditorSignal(LogicVector.of(8, 255uL), EditorSignalRadix.UNSIGNED_DECIMAL))
        assertEquals("-1", formatEditorSignal(LogicVector.of(8, 255uL), EditorSignalRadix.SIGNED_DECIMAL))
        assertEquals("-1", formatEditorSignal(LogicVector.high(64), EditorSignalRadix.SIGNED_DECIMAL))
        assertEquals("18446744073709551615", formatEditorSignal(LogicVector.high(64), EditorSignalRadix.UNSIGNED_DECIMAL))
        assertEquals("-4611686018427387904", formatEditorSignal(LogicVector.of(63, 1uL shl 62), EditorSignalRadix.SIGNED_DECIMAL))
        assertEquals("-1", formatEditorSignal(LogicVector.high(1), EditorSignalRadix.SIGNED_DECIMAL))
    }

    @Test
    fun formatsUndefinedAndErrorBitsWithoutInventingKnownNumbers() {
        val mixed = LogicVector.of(8, 0uL).withBit(7, LogicValue.UNKNOWN).withBit(0, LogicValue.ERROR)
        // Evolution's default UNKNOWN_CHAR and ERROR_CHAR (U and E).
        assertEquals("0xUE", formatEditorSignal(mixed, EditorSignalRadix.HEXADECIMAL))
        assertEquals("U000000E", formatEditorSignal(mixed, EditorSignalRadix.UNSIGNED_DECIMAL))
    }

    @Test
    fun everyOfferedDefaultFrequencyRoundTripsAndAnUnusualStoredOneSurvives() {
        val storage = TestPreferencesStorage(null)
        for (hz in dev.logisim.mobile.core.TickFrequencies.choices + 3.0) {
            assertEquals(PreferencesSaveResult.Success, EditorPreferencesRepository(storage).save(EditorPreferences(defaultTickFrequencyHz = hz)))
            assertEquals(hz, EditorPreferencesRepository(storage).load().preferences.defaultTickFrequencyHz)
        }
        // The stored Double is unchanged: a hand-edited 3 Hz loads as 3.0 and the choice lists it as a custom entry.
        assertTrue("defaultTickFrequencyHz=3.0" in storage.contents.orEmpty())
        assertEquals(3.0, EditorPreferencesCodec.read(header + "defaultTickFrequencyHz=3\n").preferences.defaultTickFrequencyHz)
        assertEquals(listOf(3.0), dev.logisim.mobile.core.TickFrequencies.choicesWith(3.0).filter { it.custom }.map { it.hz })
    }

    private class TestPreferencesStorage(var contents: String?) : PreferencesStorage {
        var writes = 0
        var failWrites = false
        override fun load(): String? = contents
        override fun save(serialized: String) {
            check(!failWrites) { "disk unavailable" }
            contents = serialized
            writes++
        }
    }
}
