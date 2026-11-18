package ai.forge.editorial

import ai.forge.core.text.TextUtils

/**
 * Le texte d'une vidéo est fait pour être DIT (§9.4). Ce contrôle repère ce qui sonne comme un
 * article académique ou ce qui se prononce mal. Ce sont des signalements de style, pas des fautes :
 * le Script Doctor décide.
 */
object OralStyle {
    data class Finding(val kind: String, val excerpt: String, val suggestion: String)

    private val academic = listOf(
        "il convient de" to "dites directement ce qu'il faut faire",
        "force est de constater" to "« on le voit bien », ou rien",
        "nonobstant" to "« malgré »",
        "susmentionn" to "nommez la chose à nouveau",
        "ledit" to "nommez la chose", "ladite" to "nommez la chose",
        "dans le cadre de" to "« pour », « dans »",
        "il apparait que" to "« on voit que »",
        "par consequent" to "« du coup », « alors »",
        "en outre" to "« et puis », « en plus »",
        "toutefois" to "« mais »",
        "afin de" to "« pour »",
        "a cet egard" to "à supprimer",
        "il s'avere que" to "« en fait »",
        "en d'autres termes" to "« autrement dit », ou reformulez",
        "cf" to "à supprimer à l'oral",
    )

    fun check(text: String, maxSpokenWords: Int = 28, generalPublic: Boolean = true): List<Finding> = buildList {
        val folded = TextUtils.fold(text)
        if (generalPublic) academic.forEach { (expr, sugg) ->
            if (Regex("\\b${Regex.escape(expr)}\\b").containsMatchIn(folded)) add(Finding("formulation écrite", expr, sugg))
        }
        TextUtils.sentences(text).forEach { s ->
            val n = TextUtils.wordCount(s)
            if (n > maxSpokenWords) add(Finding("phrase trop longue à dire ($n mots)", s.take(70) + "…", "couper en deux phrases"))
            val subordinates = Regex("\\b(qui|que|qu'|dont|lequel|laquelle|lesquels)\\b").findAll(TextUtils.fold(s)).count()
            if (subordinates >= 4) add(Finding("trop de subordonnées", s.take(70) + "…", "une idée par phrase"))
        }
        Regex("\\([^)]{3,}\\)").findAll(text).forEach { add(Finding("parenthèse", it.value, "les parenthèses ne s'entendent pas : intégrer ou supprimer")) }
        Regex("\\b(etc|cf|p|vol|éd|n°)\\.").findAll(text).forEach { add(Finding("abréviation", it.value, "écrire le mot en entier")) }
        Regex("\\b\\d{4}\\s*[-–/]\\s*\\d{2,4}\\b").findAll(text).forEach { add(Finding("plage de dates", it.value, "« de 1978 à 1986 »")) }
        Regex("[;:]").findAll(text).take(1).forEach { _ -> add(Finding("ponctuation écrite", "; ou :", "préférer un point")) }
    }
}
