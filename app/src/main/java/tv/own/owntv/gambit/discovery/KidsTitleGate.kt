package tv.own.owntv.gambit.discovery

import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

/**
 * Which titles a kids profile may open: the ids that approved catalogues have returned, grouped by
 * catalogue key. One instance is shared by Discovery and Home's addon row. Kept in memory only, never
 * saved, so after a restart, a cleared view model or a profile switch nothing is reachable until an
 * approved catalogue loads again (fails closed). Holds no addon address.
 *
 * Any feature that opens or resumes an addon title for a kids profile must go through [allows], with a test.
 */
class KidsTitleGate {
    private val lock = Any()
    private var owner = NO_PROFILE
    private var approved: Set<String> = emptySet()
    private val ids = HashMap<String, MutableSet<String>>()

    /**
     * Sets the catalogues approved right now for [profileId]. Ids from a different profile are forgotten,
     * and so are ids of catalogues that are no longer approved.
     */
    fun retainApproved(profileId: Long, keys: Set<String>) {
        synchronized(lock) {
            if (profileId != owner) {
                ids.clear()
                owner = profileId
            }
            approved = keys.toSet()
            ids.keys.retainAll(approved)
        }
    }

    /** No kids profile is active: nothing is approved and nothing is remembered. */
    fun reset() {
        synchronized(lock) {
            owner = NO_PROFILE
            approved = emptySet()
            ids.clear()
        }
    }

    /**
     * Remembers [titleIds] from a page of [catalogKey]. Added only when the catalogue is approved and the
     * request had no extras, or skip and nothing else (an allow list: any other extra, including ones added
     * later, adds nothing).
     */
    fun report(catalogKey: String, titleIds: Collection<String>, extras: CatalogExtraArgs) {
        synchronized(lock) {
            if (catalogKey !in approved) return
            if (extras.copy(skip = null) != CatalogExtraArgs()) return
            ids.getOrPut(catalogKey) { HashSet() }.addAll(titleIds)
        }
    }

    /** True when [id] came from [catalogKey] and that catalogue is approved right now. */
    fun allows(catalogKey: String, id: String): Boolean = synchronized(lock) {
        catalogKey in approved && ids[catalogKey]?.contains(id) == true
    }

    private companion object {
        const val NO_PROFILE = Long.MIN_VALUE
    }
}

/**
 * The title page a kids profile has open. Opening goes through the [KidsTitleGate]; streams and playback
 * work only while that title is still allowed. One per Discovery view model.
 */
class KidsTitleSession(private val gate: KidsTitleGate) {
    private var current: Pair<String, String>? = null

    /** Opens [id] from [catalogKey] when the gate allows it; false (and nothing changes) when it does not. */
    fun open(catalogKey: String, id: String): Boolean {
        if (!gate.allows(catalogKey, id)) return false
        current = catalogKey to id
        return true
    }

    fun close() {
        current = null
    }

    /** True while a gated title is open and its catalogue is still approved. */
    fun mayStream(): Boolean = current?.let { gate.allows(it.first, it.second) } == true

    /** An episode of the open, gated series: [episodeIds] are the ones its loaded page lists (null until loaded). */
    fun mayStreamEpisode(episodeIds: Collection<String>?, videoId: String): Boolean =
        mayStream() && episodeIds?.contains(videoId) == true
}
