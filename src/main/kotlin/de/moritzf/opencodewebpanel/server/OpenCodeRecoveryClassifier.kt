package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Classifies the last session message after a crash, kill, or machine suspend.
 * Call sites still go through [OpenCodeServerProtocol].
 */
internal object OpenCodeRecoveryClassifier {
    private const val SUSPEND_DETECTION_SLACK_MILLIS = 60_000L

    /**
     * Returns the wall-clock gap between two periodic-check runs when it is too large to be
     * scheduler jitter — i.e. the machine was suspended (sleep, hibernate) in between — or
     * null otherwise. On Apple Silicon the JVM's monotonic clock advances during sleep, so
     * the overdue tick fires right on wake and the gap approximates the sleep duration; on
     * platforms where it pauses, the tick fires up to one interval after wake instead.
     */
    fun detectSuspendGapMillis(previousRunMillis: Long, nowMillis: Long, intervalMillis: Long): Long? {
        if (previousRunMillis <= 0L) return null
        val gap = nowMillis - previousRunMillis
        return gap.takeIf { it > intervalMillis + SUSPEND_DETECTION_SLACK_MILLIS }
    }

    /**
     * Detects an assistant turn that a machine suspend severed: the turn started before the
     * machine went to sleep ([createdBeforeMillis]) and settled with an error only after it
     * resumed ([completedAfterMillis]) — the provider connection cannot survive the gap, and
     * nobody was at the machine to stop the turn in between. The error payload cannot serve
     * as the discriminator because a user stop settles with the same
     * `{"type":"unknown",...}` shape (see [isInterruptedLastMessage]); the timestamps can.
     */
    fun isSuspendSeveredLastMessage(messageJson: String, createdBeforeMillis: Long, completedAfterMillis: Long): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        if (message.stringMember("type") != "assistant") return false
        if (message.get("error")?.isJsonNull != false) return false
        val time = message.objectMember("time") ?: return false
        val created = time.longMember("created") ?: return false
        val completed = time.longMember("completed") ?: return false
        return created <= createdBeforeMillis && completed >= completedAfterMillis
    }

    /**
     * An assistant turn that started before [createdBeforeMillis] and has not settled yet
     * (no `time.completed`). After a resume from suspend such a turn is either hung on a dead
     * provider connection (and will settle with an error once the server notices) or genuinely
     * survived the sleep and is still streaming; callers poll until it settles either way.
     */
    fun isUnsettledTurnFromBefore(messageJson: String, createdBeforeMillis: Long): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        if (message.stringMember("type") != "assistant") return false
        val time = message.objectMember("time") ?: return false
        val created = time.longMember("created") ?: return false
        return created <= createdBeforeMillis && !time.has("completed")
    }

    /**
     * Maps a stored message onto the flat `{type, time, error?, content[]}` shape that
     * [isInterruptedLastMessage] and friends read.
     *
     * - v2 `SessionMessage` already has top-level `type` → returned unchanged.
     * - v1 `{info:{role,time,error?}, parts:[…]}` → `type` from `info.role`, `content` from
     *   `parts` (tool parts already carry `state.status` the same way).
     */
    fun normalizeLastMessageForClassification(messageJson: String): String? {
        val message = parseJsonObject(messageJson) ?: return null
        if (message.stringMember("type") != null) return messageJson
        val info = message.objectMember("info") ?: return null
        val role = info.stringMember("role") ?: return null
        val normalized = JsonObject()
        normalized.addProperty("type", role)
        info.objectMember("time")?.let { normalized.add("time", it) }
        info.get("error")?.takeUnless { it.isJsonNull }?.let { normalized.add("error", it) }
        message.get("parts")?.takeIf { it.isJsonArray }?.let { normalized.add("content", it) }
        return normalized.toString()
    }

    /**
     * Inspects the last projected session message for signs that the agent turn was
     * interrupted by a crash or kill (not by a user-initiated stop). Verified against a
     * live opencode 1.17.13 server:
     * - A hard kill mid-turn never persists the partial assistant reply, so after a crash
     *   the last message is the unanswered `user` prompt.
     * - An assistant message missing `time.completed`, or with a tool in `pending`/`running`
     *   state, is an in-flight projection that only an unclean shutdown leaves behind.
     * - A user-initiated stop settles the message: it sets both `time.completed` and the
     *   top-level `error` field, so it is intentionally not treated as a crash here.
     *
     * [createdBeforeMillis] bounds the check to turns from before the current server
     * process was launched: a message created on the live server (a prompt the user just
     * sent, or its still-streaming reply) can look identical to an interrupted turn but
     * must never be "continued" — that would steer a spurious prompt into a running turn.
     */
    fun isInterruptedLastMessage(messageJson: String, createdBeforeMillis: Long = Long.MAX_VALUE): Boolean {
        val message = parseJsonObject(messageJson) ?: return false
        if (createdBeforeMillis != Long.MAX_VALUE) {
            // Without a creation timestamp the pre-restart origin cannot be proven; treat
            // the message as live rather than risk a false continuation.
            val created = message.objectMember("time")?.longMember("created")
            if (created == null || created >= createdBeforeMillis) return false
        }
        // A user prompt with no assistant reply after it: the turn died before any part of
        // the reply was persisted. Other non-assistant types (compaction, model-switched,
        // system, ...) do not imply an unanswered prompt.
        if (message.stringMember("type") == "user") return true
        if (message.stringMember("type") != "assistant") return false
        // Top-level error → the turn ended (user stop or provider failure), not a crash.
        if (message.get("error")?.isJsonNull == false) return false
        // time.completed missing → turn never finished (process died mid-turn).
        val time = message.objectMember("time")
        if (time != null && !time.has("completed")) return true
        // Any tool part with pending/running state → unsettled work.
        val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return content.any { part ->
            val partObject = part.takeIf { it.isJsonObject }?.asJsonObject
            partObject?.stringMember("type") == "tool" &&
                partObject.objectMember("state")
                    ?.stringMember("status") in listOf("pending", "running")
        }
    }

    private fun parseJsonObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
    }
}
