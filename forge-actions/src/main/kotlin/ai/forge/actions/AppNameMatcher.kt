package ai.forge.actions

import ai.forge.core.text.TextUtils

/**
 * Retrouve l'application voulue parmi celles installées à partir de ce que l'utilisateur dit
 * (« galerie », « l'appareil photo », « mes messages », « Insta »…). Pur et testable.
 */
object AppNameMatcher {
    data class App(val label: String, val packageName: String)

    /** Noms courants → libellés ou paquets possibles (Samsung et Google). */
    private val synonyms: Map<String, List<String>> = mapOf(
        "galerie" to listOf("galerie", "gallery", "com.sec.android.gallery3d", "photos", "com.google.android.apps.photos"),
        "photos" to listOf("galerie", "gallery", "photos"),
        "appareil photo" to listOf("appareil photo", "camera", "com.sec.android.app.camera"),
        "camera" to listOf("appareil photo", "camera", "com.sec.android.app.camera"),
        "messages" to listOf("messages", "com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "sms" to listOf("messages"),
        "telephone" to listOf("telephone", "phone", "com.samsung.android.dialer"),
        "parametres" to listOf("parametres", "settings", "com.android.settings"),
        "reglages" to listOf("parametres", "settings"),
        "agenda" to listOf("calendrier", "calendar", "agenda"),
        "calendrier" to listOf("calendrier", "calendar"),
        "horloge" to listOf("horloge", "clock"),
        "reveil" to listOf("horloge", "clock"),
        "musique" to listOf("spotify", "musique", "music", "youtube music"),
        "carte" to listOf("maps", "plans"),
        "gps" to listOf("maps", "waze"),
        "mail" to listOf("gmail", "e-mail", "email", "outlook"),
        "mails" to listOf("gmail", "e-mail", "email"),
        "notes" to listOf("samsung notes", "notes", "keep"),
        "calculatrice" to listOf("calculatrice", "calculator"),
        "meteo" to listOf("meteo", "weather"),
        "fichiers" to listOf("mes fichiers", "fichiers", "files"),
        "insta" to listOf("instagram"),
        "store" to listOf("play store", "galaxy store"),
        "navigateur" to listOf("internet", "chrome", "samsung internet"),
        "internet" to listOf("internet", "chrome"),
        "contacts" to listOf("contacts"),
    )

    private val filler = Regex("^(l'application|l'appli|l'app|application|appli|app|mon|ma|mes|le|la|les|l')\\s+")

    fun normalize(s: String) = TextUtils.fold(s).replace('’', '\'').replace(filler, "").replace(Regex("[^a-z0-9' +-]"), " ").trim().replace(Regex("\\s+"), " ")

    fun best(spoken: String, apps: List<App>): App? {
        val q = normalize(spoken).replace(filler, "")
        if (q.isEmpty()) return null
        val wanted = listOf(q) + synonyms[q].orEmpty() + synonyms[q.removeSuffix("s")].orEmpty()
        var bestApp: App? = null; var bestScore = 0.0
        for (app in apps) {
            val label = normalize(app.label)
            var score = 0.0
            wanted.forEachIndexed { i, w ->
                val penalty = if (i == 0) 0.0 else 0.05 * i                  // le nom dit exactement passe avant les synonymes
                val sc = when {
                    w.contains('.') && app.packageName == w -> 1.0
                    label == w -> 0.98
                    label.startsWith("$w ") || label.endsWith(" $w") -> 0.85
                    w.length >= 4 && label.contains(w) -> 0.75
                    w.length >= 4 && w.contains(label) && label.length >= 4 -> 0.7
                    else -> 0.0
                } - penalty
                if (sc > score) score = sc
            }
            if (score > bestScore) { bestScore = score; bestApp = app }
        }
        return bestApp?.takeIf { bestScore >= 0.6 }
    }
}
