package ai.forge.models

/**
 * Moteurs interchangeables (§13.3). Chaque type de moteur est enregistré sous un identifiant ;
 * l'utilisateur choisit l'actif dans les réglages, sans reconstruire l'application.
 */
enum class EngineKind { LLM, STT, TTS, OCR, EMBEDDINGS, BROWSER, SEARCH, VISION, STORAGE, CONNECTOR }

data class EngineDescriptor(
    val kind: EngineKind,
    val id: String,
    val displayName: String,
    val requiresNetwork: Boolean,
    val external: Boolean,
    val note: String = "",
)

class EngineRegistry {
    private val engines = mutableMapOf<EngineKind, MutableMap<String, Pair<EngineDescriptor, Any>>>()
    private val active = mutableMapOf<EngineKind, String>()

    fun <T : Any> register(d: EngineDescriptor, impl: T) {
        engines.getOrPut(d.kind) { linkedMapOf() }[d.id] = d to impl
        active.putIfAbsent(d.kind, d.id)
    }

    fun select(kind: EngineKind, id: String) {
        require(engines[kind]?.containsKey(id) == true) { "Moteur $kind « $id » inconnu" }
        active[kind] = id
    }

    fun available(kind: EngineKind): List<EngineDescriptor> = engines[kind]?.values?.map { it.first }.orEmpty()
    fun activeDescriptor(kind: EngineKind): EngineDescriptor? = active[kind]?.let { engines[kind]?.get(it)?.first }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> active(kind: EngineKind): T? = active[kind]?.let { engines[kind]?.get(it)?.second as T? }

    /**
     * Scénario H : en l'absence de réseau, bascule chaque type de moteur vers une alternative locale
     * si elle existe, et renvoie l'état de chaque capacité pour l'affichage.
     */
    fun offlineReport(networkAvailable: Boolean): List<CapabilityStatus> = EngineKind.entries.mapNotNull { kind ->
        val all = available(kind)
        if (all.isEmpty()) return@mapNotNull null
        val current = activeDescriptor(kind)!!
        if (networkAvailable || !current.requiresNetwork) CapabilityStatus(kind, true, current.displayName, null)
        else {
            val local = all.firstOrNull { !it.requiresNetwork }
            if (local != null) { active[kind] = local.id; CapabilityStatus(kind, true, local.displayName, "Bascule hors-ligne depuis ${current.displayName}") }
            else CapabilityStatus(kind, false, current.displayName, "Nécessite Internet : indisponible hors-ligne")
        }
    }
}

data class CapabilityStatus(val kind: EngineKind, val available: Boolean, val engine: String, val note: String?)
