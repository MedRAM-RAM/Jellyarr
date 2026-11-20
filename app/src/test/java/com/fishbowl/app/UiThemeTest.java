package com.fishbowl.app;

import android.content.Context;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@RunWith(RobolectricTestRunner.class)
public class UiThemeTest {

    @Test
    public void presetsProvideDistinctAccentsAndReadableOnColors() {
        Context context = RuntimeEnvironment.getApplication();
        int ocean = UiTheme.paletteFor(context, UiTheme.OCEAN).accent;
        int violet = UiTheme.paletteFor(context, UiTheme.VIOLET).accent;
        int emerald = UiTheme.paletteFor(context, UiTheme.EMERALD).accent;
        int amber = UiTheme.paletteFor(context, UiTheme.AMBER).accent;

        assertNotEquals(ocean, violet);
        assertNotEquals(violet, emerald);
        assertNotEquals(emerald, amber);
        assertEquals(Color.parseColor("#0B1220"), UiTheme.paletteFor(context, UiTheme.OCEAN).onAccent);
    }

    @Test
    public void legacyAccentAndSurfaceColorsMapToSelectedPalette() {
        Context context = RuntimeEnvironment.getApplication();
        UiTheme.saveTheme(context, UiTheme.VIOLET);
        UiTheme.Palette palette = UiTheme.palette(context);

        assertEquals(UiTheme.VIOLET, UiTheme.selectedTheme(context));
        assertEquals(palette.accent, UiTheme.normalizeAccentColor(context, Color.parseColor("#38BDF8")));
        assertEquals(palette.card, UiTheme.normalizeFill(context, Color.parseColor("#0C0C0E")));
        assertEquals(palette.textPrimary, UiTheme.normalizeTextColor(context, Color.WHITE));
    }
}
