package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorToolbarLayoutTest {
    @Test
    fun sideRailsStayBesideTheCanvasWithoutASidebar() {
        for (side in listOf(EditorToolbarPlacement.LEFT, EditorToolbarPlacement.RIGHT)) {
            assertEquals(side, side.effectivePlacement(hasSidebar = true, hasProjectToolbar = true))
            assertEquals(side, side.effectivePlacement(hasSidebar = false, hasProjectToolbar = true))
            assertEquals(EditorToolbarOrientation.VERTICAL, side.orientation)
        }
        val middle = EditorToolbarPlacement.DOWN_MIDDLE
        assertEquals(middle, middle.effectivePlacement(hasSidebar = true, hasProjectToolbar = true))
        assertEquals(EditorToolbarPlacement.LEFT, middle.effectivePlacement(hasSidebar = false, hasProjectToolbar = true))
    }

    @Test
    fun shortLandscapeWindowsMoveTheDefaultTopToolbarToARail() {
        assertEquals(EditorToolbarPlacement.LEFT,
            EditorToolbarPlacement.TOP.effectivePlacement(hasSidebar = false, hasProjectToolbar = true, shortLandscape = true))
        assertEquals(EditorToolbarPlacement.BOTTOM,
            EditorToolbarPlacement.BOTTOM.effectivePlacement(hasSidebar = false, hasProjectToolbar = true, shortLandscape = true))
    }

    @Test
    fun simulationControlsFollowTheToolbarAndTheWindowWidth() {
        assertEquals(SimulationControlsPlacement.TOOLBAR, simulationControlsPlacement(EditorToolbarPlacement.TOP, narrow = false))
        assertEquals(SimulationControlsPlacement.TOOLBAR, simulationControlsPlacement(EditorToolbarPlacement.BOTTOM, narrow = false))
        assertEquals(SimulationControlsPlacement.BOTTOM_BAR, simulationControlsPlacement(EditorToolbarPlacement.TOP, narrow = true))
        assertEquals(SimulationControlsPlacement.HEADER, simulationControlsPlacement(EditorToolbarPlacement.LEFT, narrow = false))
        assertEquals(SimulationControlsPlacement.HEADER, simulationControlsPlacement(EditorToolbarPlacement.HIDDEN, narrow = false))
        assertEquals(SimulationControlsPlacement.BOTTOM_BAR, simulationControlsPlacement(EditorToolbarPlacement.RIGHT, narrow = true))
    }

    @Test
    fun hidingTheHeaderKeepsSimulationControlsInTheToolbarOrBottomBar() {
        assertEquals(SimulationControlsPlacement.BOTTOM_BAR,
            simulationControlsPlacement(EditorToolbarPlacement.TOP, narrow = true, showHeaderBar = false))
        assertEquals(SimulationControlsPlacement.TOOLBAR,
            simulationControlsPlacement(EditorToolbarPlacement.TOP, narrow = false, showHeaderBar = false))
        for (placement in listOf(EditorToolbarPlacement.LEFT, EditorToolbarPlacement.RIGHT,
            EditorToolbarPlacement.DOWN_MIDDLE, EditorToolbarPlacement.HIDDEN)) {
            assertEquals(SimulationControlsPlacement.BOTTOM_BAR,
                simulationControlsPlacement(placement, narrow = false, showHeaderBar = false))
        }
    }

    @Test
    fun hertzLabelsDropATrailingZero() {
        assertEquals("4", formatHertz(4.0))
        assertEquals("0.5", formatHertz(0.5))
    }

    @Test
    fun hiddenPlacementRequiresTheProjectToolbarThatOpensTheCommandsMenu() {
        val hidden = EditorToolbarPlacement.HIDDEN
        assertEquals(hidden, hidden.effectivePlacement(hasSidebar = true, hasProjectToolbar = true))
        assertEquals(hidden, hidden.effectivePlacement(hasSidebar = false, hasProjectToolbar = true))
        assertEquals(EditorToolbarPlacement.TOP, hidden.effectivePlacement(hasSidebar = false, hasProjectToolbar = false))
    }

    @Test
    fun horizontalPlacementsWorkWithOrWithoutTheSidebar() {
        for (placement in listOf(EditorToolbarPlacement.TOP, EditorToolbarPlacement.BOTTOM)) {
            assertEquals(placement, placement.effectivePlacement(hasSidebar = true, hasProjectToolbar = true))
            assertEquals(placement, placement.effectivePlacement(hasSidebar = false, hasProjectToolbar = false))
            assertEquals(EditorToolbarOrientation.HORIZONTAL, placement.orientation)
        }
    }

    @Test
    fun allSixPlacementPreferencesRoundTrip() {
        for (placement in EditorToolbarPlacement.entries) {
            val settings = EditorPreferences(toolbarPlacement = placement)
            assertEquals(placement, EditorPreferencesCodec.read(EditorPreferencesCodec.write(settings)).preferences.toolbarPlacement)
        }
    }

    @Test
    fun allActionsCanUseTheSpaceThatAnUnneededOverflowButtonWouldOccupy() {
        assertEquals(3, toolbarVisibleActionCount(listOf(48, 64, 80), 200, 48, 4))
    }

    @Test
    fun aNarrowToolbarLeavesRoomForTheEntireOverflowButton() {
        assertEquals(2, toolbarVisibleActionCount(listOf(96, 96, 96, 96), 320, 48, 4))
        assertEquals(3, toolbarVisibleActionCount(listOf(96, 96, 96, 96), 390, 48, 4))
    }

    @Test
    fun theGapBeforeOverflowCountsAtTheExactFitBoundary() {
        assertEquals(2, toolbarVisibleActionCount(listOf(100, 100, 100), 252, 48, 2))
        assertEquals(1, toolbarVisibleActionCount(listOf(100, 100, 100), 251, 48, 2))
    }

    @Test
    fun largeTextMovesWholeActionsIntoOverflow() {
        assertEquals(3, toolbarVisibleActionCount(listOf(88, 80, 76), 320, 48, 4))
        assertEquals(1, toolbarVisibleActionCount(listOf(144, 132, 124), 320, 48, 4))
    }

    @Test
    fun aSingleActionBoundedByItsParentRemainsVisibleWithoutOverflow() {
        assertEquals(1, toolbarVisibleActionCount(listOf(320), 320, 48, 4))
        assertEquals(0, toolbarVisibleActionCount(listOf(320, 48), 320, 48, 4))
        assertEquals(0, toolbarVisibleActionCount(listOf(400, 48), 320, 48, 4))
    }

    @Test
    fun emptyAndOverflowOnlyToolbarsHaveNoVisibleActionPrefix() {
        assertEquals(0, toolbarVisibleActionCount(emptyList(), 320, 48, 4))
        assertEquals(0, toolbarVisibleActionCount(listOf(64, 64), 48, 48, 4))
        assertEquals(0, toolbarVisibleActionCount(listOf(64, 64), 0, 48, 4))
    }

    @Test
    fun widthTotalsCannotWrapIntoAnApparentlyFittingToolbar() {
        assertEquals(0, toolbarVisibleActionCount(listOf(Int.MAX_VALUE, Int.MAX_VALUE), Int.MAX_VALUE, 48, 0))
    }
}
