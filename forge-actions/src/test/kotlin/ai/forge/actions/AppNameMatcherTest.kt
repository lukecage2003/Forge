package ai.forge.actions

import kotlin.test.*

class AppNameMatcherTest {
    private val apps = listOf(
        AppNameMatcher.App("Galerie", "com.sec.android.gallery3d"),
        AppNameMatcher.App("Photos", "com.google.android.apps.photos"),
        AppNameMatcher.App("Appareil photo", "com.sec.android.app.camera"),
        AppNameMatcher.App("Paramètres", "com.android.settings"),
        AppNameMatcher.App("Spotify", "com.spotify.music"),
        AppNameMatcher.App("Instagram", "com.instagram.android"),
        AppNameMatcher.App("Samsung Notes", "com.samsung.android.app.notes"),
        AppNameMatcher.App("Comic Geeks", "com.leagueofcomicgeeks"),
    )
    private fun m(s: String) = AppNameMatcher.best(s, apps)?.label

    @Test fun findsAppsFromEverydayWords() {
        assertEquals("Galerie", m("galerie")); assertEquals("Galerie", m("ma galerie"))
        assertEquals("Photos", m("Photos"))                                 // le nom exact passe avant le synonyme
        assertEquals("Appareil photo", m("l'appareil photo")); assertEquals("Appareil photo", m("caméra"))
        assertEquals("Paramètres", m("réglages")); assertEquals("Instagram", m("insta"))
        assertEquals("Samsung Notes", m("notes")); assertEquals("Comic Geeks", m("comic geeks"))
    }

    @Test fun refusesUnknownApps() { assertNull(m("Snapchat")); assertNull(m("")) }
}
