package ai.forge.academy

import ai.forge.core.text.TextUtils
import ai.forge.memory.Bm25Index
import kotlinx.serialization.Serializable

/** Extrait de cours localisé (le texte vient de l'extraction PDF/OCR faite côté Android). */
@Serializable
data class CourseChunk(val id: String, val courseId: String, val text: String, val page: Int? = null, val section: String? = null) {
    fun citation(courseTitle: String) = buildString {
        append(courseTitle); section?.let { append(" — ").append(it) }; page?.let { append(", p. ").append(it) }
    }
}

@Serializable
data class Course(val id: String, val title: String, val subject: String, val chunks: List<CourseChunk>)

/**
 * Index des cours (§10). Le cours de l'utilisateur est la référence prioritaire : la recherche
 * se fait d'abord dans ses cours, et chaque passage reste cité avec sa page.
 */
class CourseLibrary {
    private val courses = mutableMapOf<String, Course>()
    private val chunks = mutableMapOf<String, CourseChunk>()
    private val index = Bm25Index()

    fun add(course: Course) {
        courses[course.id] = course
        course.chunks.forEach { chunks[it.id] = it; index.put(it.id, (it.section ?: "") + " " + it.text) }
    }

    fun remove(courseId: String) {
        courses.remove(courseId)?.chunks?.forEach { chunks.remove(it.id); index.remove(it.id) }
    }

    fun course(id: String) = courses[id]
    fun search(query: String, courseId: String? = null, limit: Int = 5): List<CourseChunk> =
        index.search(query) { id -> courseId == null || chunks[id]?.courseId == courseId }
            .take(limit).mapNotNull { chunks[it.first] }

    /** Découpe un texte extrait en passages d'environ [targetWords] mots, en respectant les paragraphes. */
    companion object {
        fun chunk(courseId: String, pages: List<String>, targetWords: Int = 180): List<CourseChunk> {
            val out = mutableListOf<CourseChunk>()
            pages.forEachIndexed { p, pageText ->
                val buf = StringBuilder()
                fun flush() { if (buf.isNotBlank()) out += CourseChunk("$courseId-${out.size}", courseId, buf.toString().trim(), page = p + 1); buf.clear() }
                pageText.split(Regex("\\n\\s*\\n")).forEach { para ->
                    if (TextUtils.wordCount(buf.toString()) + TextUtils.wordCount(para) > targetWords) flush()
                    buf.append(para.trim()).append("\n\n")
                }
                flush()
            }
            return out
        }
    }
}
