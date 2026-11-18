package ai.forge.editorial

import ai.forge.comics.*
import ai.forge.editorial.export.Exporters
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class EditorialTest {    private fun words(n: Int) = (1..n).joinToString(" ") { "mot$it" } + "."

    @Test fun formatTargetsAreConfigurable() {
        assertEquals(180, FormatSpec.short(60, 180).targetWords)
        assertEquals(3000, FormatSpec.youtubeLong(20, 150).targetWords)
        assertEquals(12600, FormatSpec.documentary(90, 140).targetWords)
    }

    @Test fun qcFlagsLengthHookAndUnsourcedIssueNumbers() {
        val script = ScriptDocument("Le vibranium", "Comprendre le vibranium", "fans", FormatSpec.short(60),
            "scientifique", listOf(
                ScriptSegment("Hook", "Dans Black Panther #8 page 14 on voit tout, et c'est une très longue phrase qui ne finit jamais vraiment."),
            ))
        val issues = ScriptQualityControl.check(script, emptyMap())
        assertTrue(issues.any { it.message.startsWith("Longueur") })
        assertTrue(issues.any { it.message.startsWith("Accroche trop lente") })
        assertTrue(issues.any { it.severity == QcIssue.Severity.BLOQUANT && it.message.contains("risque d'invention") })
    }

    @Test fun qcAcceptsWellFormedShortAndWarnsOnWeakClaims() {
        val weak = Claim("w1", "Le vibranium vient d'une météorite", "Wakanda",
            listOf(EvidenceSource(SourceTier.WIKI_COMMUNITY, description = "wiki")), "wiki", EvidenceLevel.P4)
        val script = ScriptDocument("Vibranium", "Une idée", "fans", FormatSpec.short(60, 180), "origine", listOf(
            ScriptSegment("Hook", "Et si le Wakanda venait du ciel ?"),
            ScriptSegment("Idée", words(170), claimIds = listOf("w1")),
        ))
        val issues = ScriptQualityControl.check(script, mapOf("w1" to weak))
        assertFalse(issues.any { it.message.startsWith("Longueur") })
        assertTrue(issues.any { it.message.contains("P4") })
        assertTrue(Exporters.scriptToMarkdown(script).contains("## 2. Idée"))
    }

    @Test fun shortBriefRequiresRebuildNotCut() {
        val long = ScriptDocument("Long", "Tout sur Wakanda", "fans", FormatSpec.youtubeLong(), "histoire", emptyList())
        val prompt = ScriptPromptBuilder.build(EditorialRole.SCRIPT_DOCTOR, ScriptBrief("Wakanda", FormatSpec.short(), sourceScript = long))
        assertTrue(prompt.contains("ne coupe pas le script long"))
        assertTrue(prompt.contains("[À VÉRIFIER]"))
    }

}
