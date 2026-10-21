package com.metrolist.innertubex.extraction.strategy

import com.metrolist.innertubex.extraction.ContentHints
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min
import kotlin.time.Clock

@Serializable
enum class ClientFailureKind {
    PLAYER_REQUEST,
    PLAYABILITY,
    TOKEN,
    MEDIA_FORBIDDEN,
    MEDIA_FAILURE,
}

@Serializable
enum class ClientHealthContent {
    NORMAL,
    EXPLICIT,
    KIDS,
    AGE_RESTRICTED,
    LIVE,
    UPLOAD,
}

@Serializable
data class ClientHealthScope(
    val content: ClientHealthContent,
    val authenticated: Boolean,
    val wantVideo: Boolean,
) {
    companion object {
        fun from(
            hints: ContentHints,
            authenticated: Boolean,
        ): ClientHealthScope =
            ClientHealthScope(
                content =
                    when {
                        hints.isUploaded == true -> ClientHealthContent.UPLOAD
                        hints.isLive == true -> ClientHealthContent.LIVE
                        hints.isAgeRestricted == true -> ClientHealthContent.AGE_RESTRICTED
                        hints.isKidsContent == true -> ClientHealthContent.KIDS
                        hints.isExplicit == true -> ClientHealthContent.EXPLICIT
                        else -> ClientHealthContent.NORMAL
                    },
                authenticated = authenticated,
                wantVideo = hints.wantVideo,
            )
    }
}

interface ClientHealthMonitor {
    fun scoreAdjustment(
        clientId: String,
        scope: ClientHealthScope? = null,
    ): Int = 0

    fun recordSuccess(
        clientId: String,
        scope: ClientHealthScope? = null,
    ) = Unit

    fun recordFailure(
        clientId: String,
        kind: ClientFailureKind,
        scope: ClientHealthScope? = null,
    ) = Unit

    companion object {
        val NONE: ClientHealthMonitor = object : ClientHealthMonitor {}
    }
}

/**
 * Process-local default used when the host supplies no monitor, so a client that keeps timing out stops
 * leading every extraction. Nothing is persisted, and entries are bounded.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class InMemoryClientHealthMonitor(
    private val sessionGeneration: () -> Long = { 0L },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ClientHealthMonitor {
    private data class Key(
        val clientId: String,
        val scope: ClientHealthScope?,
    )

    private data class Health(
        val consecutiveFailures: Int,
        val cooldownUntilMs: Long,
        val lastFailureAtMs: Long,
    )

    private data class State(
        val generation: Long,
        val health: Map<Key, Health> = emptyMap(),
    )

    private val state = AtomicReference(State(sessionGeneration()))

    override fun scoreAdjustment(
        clientId: String,
        scope: ClientHealthScope?,
    ): Int {
        val now = nowMs()
        if (state.load().generation != sessionGeneration()) update { it }
        val health = state.load().health
        return listOfNotNull(health[Key(clientId, null)], scope?.let { health[Key(clientId, it)] })
            // The library only reports failures, so old ones must fade out or a client would stay demoted forever.
            .filter { now - it.lastFailureAtMs < FAILURE_MEMORY_MS }
            .sumOf {
                -min(it.consecutiveFailures, MAX_CONSECUTIVE_FAILURES) * FAILURE_PENALTY -
                    if (it.cooldownUntilMs > now) COOLDOWN_PENALTY else 0
            }
    }

    override fun recordSuccess(
        clientId: String,
        scope: ClientHealthScope?,
    ) = update { it - Key(clientId, scope) }

    override fun recordFailure(
        clientId: String,
        kind: ClientFailureKind,
        scope: ClientHealthScope?,
    ) {
        val now = nowMs()
        update { health ->
            val key = Key(clientId, scope)
            val previous = health[key]?.takeIf { now - it.lastFailureAtMs < FAILURE_MEMORY_MS }
            val consecutive = (previous?.consecutiveFailures ?: 0) + 1
            val cooldownMs =
                when {
                    kind == ClientFailureKind.MEDIA_FORBIDDEN -> FORBIDDEN_COOLDOWN_MS
                    consecutive >= FAILURES_BEFORE_COOLDOWN -> DEFAULT_COOLDOWN_MS
                    else -> 0L
                }
            // Re-inserting moves the key to the end, so trimming drops the least recently updated entries.
            ((health - key) + (key to Health(consecutive, now + cooldownMs, now)))
                .entries
                .toList()
                .takeLast(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
    }

    private inline fun update(transform: (Map<Key, Health>) -> Map<Key, Health>) {
        while (true) {
            val current = state.load()
            val generation = sessionGeneration()
            // Generation also changes for visitor/locale updates: conservatively forget authenticated learning.
            val health =
                if (current.generation == generation) current.health else current.health.filterKeys { it.scope?.authenticated != true }
            if (state.compareAndSet(current, State(generation, transform(health)))) return
        }
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val FAILURE_PENALTY = 18
        const val COOLDOWN_PENALTY = 1_000
        const val FAILURES_BEFORE_COOLDOWN = 2
        const val DEFAULT_COOLDOWN_MS = 2 * 60 * 1000L
        const val FORBIDDEN_COOLDOWN_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 128
        const val FAILURE_MEMORY_MS = 10 * 60 * 1000L
    }
}
