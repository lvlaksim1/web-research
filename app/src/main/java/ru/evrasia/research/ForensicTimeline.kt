package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Capture-side forensic metadata.
 *
 * Existing evidence fields are never rewritten. The recorder only adds stable identifiers,
 * capture ordering and explicitly-labelled temporal relations before the object enters RAW.
 */
internal class ForensicTimeline(
    val sessionId: String = UUID.randomUUID().toString(),
    val sessionStartedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        private const val ACTION_WINDOW_MS = 5_000L
        private const val REQUEST_MATCH_WINDOW_MS = 3_000L
        private const val MUTATION_REQUEST_WINDOW_MS = 8_000L
        private const val MAX_RECENT_ACTIONS = 64
        private const val MAX_RECENT_REQUESTS = 512
    }

    private data class ActionHint(val id: String, val time: Long)
    private data class RequestHint(
        val id: String,
        val time: Long,
        val method: String,
        val url: String,
        var claimedByBrowserApi: Boolean = false
    )

    private val startedNano = System.nanoTime()
    private var eventCounter = 0L
    private var actionCounter = 0L
    private var requestCounter = 0L
    private var mutationCounter = 0L
    private var checkpointCounter = 0L
    private val recentActions = ArrayDeque<ActionHint>()
    private val recentWebViewRequests = ArrayDeque<RequestHint>()
    private val recentApplicationRequests = ArrayDeque<RequestHint>()
    private val browserActionTokens = LinkedHashMap<String, ActionHint>()

    @Synchronized
    fun annotate(record: JSONObject) {
        val capturedAt = System.currentTimeMillis()
        val eventTime = record.optLong("time", capturedAt).takeIf { it > 0L } ?: capturedAt
        val source = record.optString("source", "").ifBlank { "unknown" }

        eventCounter++
        if (record.optString("eventId", "").isBlank()) record.put("eventId", id("event", eventCounter))
        record.put("sequence", eventCounter)
        record.put("capturedAt", capturedAt)
        record.put("monotonicUs", ((System.nanoTime() - startedNano).coerceAtLeast(0L)) / 1_000L)

        when (source) {
            "user-action", "form-submit" -> {
                val actionId = record.optString("actionId", "").ifBlank {
                    actionCounter++
                    id("action", actionCounter)
                }
                record.put("actionId", actionId)
                val hint = ActionHint(actionId, eventTime)
                rememberAction(hint)
                val token = record.optString("browserActionToken", "")
                if (token.isNotBlank()) rememberBrowserAction(token, hint)
            }

            "webview" -> {
                val requestId = nextRequestId(record)
                rememberWebViewRequest(
                    RequestHint(
                        id = requestId,
                        time = eventTime,
                        method = methodOf(record),
                        url = comparableUrl(record.optString("url", ""))
                    )
                )
            }

            "fetch", "xhr" -> {
                val requestId = record.optString("requestId", "").ifBlank {
                    matchWebViewRequest(record, eventTime)?.id ?: newRequestId()
                }
                record.put("requestId", requestId)
                rememberApplicationRequest(
                    RequestHint(
                        id = requestId,
                        time = eventTime,
                        method = methodOf(record),
                        url = comparableUrl(record.optString("url", ""))
                    )
                )
                if (!linkBrowserAction(record)) linkNearestAction(record, eventTime)
            }

            "resource-copy", "download" -> {
                nextRequestId(record)
            }

            "dom-mutation", "shadow-root", "custom-element" -> {
                mutationCounter++
                if (record.optString("mutationId", "").isBlank()) {
                    record.put("mutationId", id("mutation", mutationCounter))
                }
                if (!linkBrowserAction(record)) linkNearestAction(record, eventTime)
                nearestApplicationRequest(eventTime)?.let {
                    record.put("relatedRequestId", it.id)
                    record.put("requestRelation", "temporal-nearest")
                }
            }

            "checkpoint" -> {
                checkpointCounter++
                if (record.optString("checkpointId", "").isBlank()) {
                    record.put("checkpointId", id("checkpoint", checkpointCounter))
                }
                linkNearestAction(record, eventTime)
                nearestApplicationRequest(eventTime)?.let {
                    record.put("relatedRequestId", it.id)
                    record.put("requestRelation", "temporal-nearest")
                }
            }

            "navigation", "history", "websocket-open", "websocket-send", "sse-open" -> {
                linkNearestAction(record, eventTime)
            }
        }
    }

    private fun nextRequestId(record: JSONObject): String {
        val existing = record.optString("requestId", "")
        if (existing.isNotBlank()) return existing
        val value = newRequestId()
        record.put("requestId", value)
        return value
    }

    private fun newRequestId(): String {
        requestCounter++
        return id("request", requestCounter)
    }

    private fun matchWebViewRequest(record: JSONObject, eventTime: Long): RequestHint? {
        val method = methodOf(record)
        val url = comparableUrl(record.optString("url", ""))
        var best: RequestHint? = null
        var bestDelta = Long.MAX_VALUE
        for (candidate in recentWebViewRequests) {
            if (candidate.claimedByBrowserApi) continue
            if (candidate.method != method || candidate.url != url) continue
            val delta = abs(candidate.time - eventTime)
            if (delta <= REQUEST_MATCH_WINDOW_MS && delta < bestDelta) {
                best = candidate
                bestDelta = delta
            }
        }
        best?.claimedByBrowserApi = true
        return best
    }

    private fun linkBrowserAction(record: JSONObject): Boolean {
        val token = record.optString("browserActionToken", "")
        if (token.isBlank()) return false
        val action = browserActionTokens[token] ?: return false
        record.put("relatedActionId", action.id)
        record.put("actionRelation", "observed-browser-event-context")
        return true
    }

    private fun linkNearestAction(record: JSONObject, eventTime: Long) {
        val action = nearestAction(eventTime) ?: return
        record.put("relatedActionId", action.id)
        record.put("actionRelation", "temporal-nearest")
    }

    private fun nearestAction(eventTime: Long): ActionHint? {
        var best: ActionHint? = null
        for (candidate in recentActions) {
            val delta = eventTime - candidate.time
            if (delta < 0L || delta > ACTION_WINDOW_MS) continue
            if (best == null || candidate.time > best.time) best = candidate
        }
        return best
    }

    private fun nearestApplicationRequest(eventTime: Long): RequestHint? {
        var best: RequestHint? = null
        for (candidate in recentApplicationRequests) {
            val delta = eventTime - candidate.time
            if (delta < 0L || delta > MUTATION_REQUEST_WINDOW_MS) continue
            if (best == null || candidate.time > best.time) best = candidate
        }
        return best
    }

    private fun rememberAction(value: ActionHint) {
        recentActions.addLast(value)
        while (recentActions.size > MAX_RECENT_ACTIONS) recentActions.removeFirst()
    }

    private fun rememberBrowserAction(token: String, value: ActionHint) {
        browserActionTokens[token] = value
        while (browserActionTokens.size > MAX_RECENT_ACTIONS * 2) {
            val first = browserActionTokens.keys.firstOrNull() ?: break
            browserActionTokens.remove(first)
        }
    }

    private fun rememberWebViewRequest(value: RequestHint) {
        recentWebViewRequests.addLast(value)
        while (recentWebViewRequests.size > MAX_RECENT_REQUESTS) recentWebViewRequests.removeFirst()
    }

    private fun rememberApplicationRequest(value: RequestHint) {
        recentApplicationRequests.addLast(value)
        while (recentApplicationRequests.size > MAX_RECENT_REQUESTS) recentApplicationRequests.removeFirst()
    }

    private fun methodOf(record: JSONObject): String =
        record.optString("method", "GET").ifBlank { "GET" }.uppercase()

    private fun comparableUrl(value: String): String = value.substringBefore('#')

    private fun id(prefix: String, value: Long): String = "%s-%08d".format(prefix, value)
}

internal object ForensicTimelineExport {
    fun buildTimeline(archive: ResearchArchive): JSONObject {
        val events = JSONArray()
        synchronized(archive) {
            for (index in 0 until archive.records.length()) {
                val source = archive.records.optJSONObject(index) ?: continue
                events.put(timelineEvent(source, index))
            }
        }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("format", "web-research-forensic-timeline-v1")
            .put("sessionId", archive.forensicSessionId)
            .put("sessionStartedAt", archive.forensicSessionStartedAtMs)
            .put("generatedAt", System.currentTimeMillis())
            .put(
                "clocks",
                JSONObject()
                    .put("time", "source-event epoch milliseconds when available")
                    .put("capturedAt", "native ingestion epoch milliseconds")
                    .put("monotonicUs", "native monotonic microseconds since recorder session start")
            )
            .put(
                "idSchema",
                JSONObject()
                    .put("eventId", "event-########")
                    .put("actionId", "action-########")
                    .put("requestId", "request-########")
                    .put("mutationId", "mutation-########")
                    .put("checkpointId", "checkpoint-########; activated by checkpoint capture")
            )
            .put("events", events)
    }

    fun buildRelations(archive: ResearchArchive): JSONObject {
        val actions = linkedMapOf<String, JSONObject>()
        val requests = linkedMapOf<String, JSONObject>()
        val mutations = JSONArray()
        val checkpoints = JSONArray()
        val initiators = linkedMapOf<String, JSONObject>()
        val causalityChains = JSONArray()
        val mutationsByRequest = linkedMapOf<String, MutableList<String>>()
        val links = JSONArray()
        val linkKeys = linkedSetOf<String>()

        synchronized(archive) {
            for (index in 0 until archive.records.length()) {
                val record = archive.records.optJSONObject(index) ?: continue
                val eventId = eventId(record, index)
                val source = record.optString("source", "unknown")
                val time = record.optLong("time", record.optLong("capturedAt", 0L))

                record.optString("actionId", "").takeIf { it.isNotBlank() }?.let { actionId ->
                    actions.getOrPut(actionId) {
                        JSONObject()
                            .put("actionId", actionId)
                            .put("eventId", eventId)
                            .put("time", time)
                            .put("action", record.optString("action", ""))
                            .put("page", record.optString("page", record.optString("url", "")))
                            .put("target", record.optJSONObject("target") ?: JSONObject.NULL)
                    }
                }

                record.optString("requestId", "").takeIf { it.isNotBlank() }?.let { requestId ->
                    val request = requests.getOrPut(requestId) {
                        JSONObject()
                            .put("requestId", requestId)
                            .put("method", record.optString("method", "GET"))
                            .put("url", record.optString("url", ""))
                            .put("eventIds", JSONArray())
                            .put("sources", JSONArray())
                            .put("firstTime", time)
                            .put("lastTime", time)
                    }
                    putUnique(request.getJSONArray("eventIds"), eventId)
                    putUnique(request.getJSONArray("sources"), source)
                    request.put("lastTime", maxOf(request.optLong("lastTime", time), time))
                    if (record.has("status")) request.put("status", record.optInt("status"))
                    val relatedActionId = record.optString("relatedActionId", "")
                    if (relatedActionId.isNotBlank()) {
                        request.put("relatedActionId", relatedActionId)
                        addLink(
                            links,
                            linkKeys,
                            relatedActionId,
                            requestId,
                            "action-to-request",
                            record.optString("actionRelation", "temporal-nearest")
                        )
                    }
                    if (source == "fetch" || source == "xhr") {
                        val stack = record.optString("initiatorStack", "")
                        val frame = initiatorFrame(stack)
                        if (frame.isNotBlank()) {
                            val initiatorId = "initiator-" + sha256(frame).take(16)
                            request.put("initiatorId", initiatorId)
                            initiators.getOrPut(initiatorId) {
                                JSONObject()
                                    .put("initiatorId", initiatorId)
                                    .put("frame", frame)
                                    .put("stack", stack.take(16000))
                            }
                            addLink(
                                links,
                                linkKeys,
                                initiatorId,
                                requestId,
                                "initiator-to-request",
                                "observed-initiator-stack"
                            )
                            if (relatedActionId.isNotBlank()) {
                                addLink(
                                    links,
                                    linkKeys,
                                    relatedActionId,
                                    initiatorId,
                                    "action-to-initiator",
                                    record.optString("actionRelation", "temporal-nearest")
                                )
                            }
                        }
                    }
                }

                record.optString("mutationId", "").takeIf { it.isNotBlank() }?.let { mutationId ->
                    val mutation = JSONObject()
                        .put("mutationId", mutationId)
                        .put("eventId", eventId)
                        .put("time", time)
                        .put("page", record.optString("page", record.optString("url", "")))
                    val relatedActionId = record.optString("relatedActionId", "")
                    val relatedRequestId = record.optString("relatedRequestId", "")
                    if (relatedActionId.isNotBlank()) {
                        mutation.put("relatedActionId", relatedActionId)
                        addLink(
                            links,
                            linkKeys,
                            relatedActionId,
                            mutationId,
                            "action-to-mutation",
                            record.optString("actionRelation", "temporal-nearest")
                        )
                    }
                    if (relatedRequestId.isNotBlank()) {
                        mutation.put("relatedRequestId", relatedRequestId)
                        mutationsByRequest.getOrPut(relatedRequestId) { mutableListOf() }.add(mutationId)
                        addLink(
                            links,
                            linkKeys,
                            relatedRequestId,
                            mutationId,
                            "request-to-mutation",
                            record.optString("requestRelation", "temporal-nearest")
                        )
                    }
                    mutations.put(mutation)
                }

                record.optString("checkpointId", "").takeIf { it.isNotBlank() }?.let { checkpointId ->
                    checkpoints.put(
                        JSONObject()
                            .put("checkpointId", checkpointId)
                            .put("eventId", eventId)
                            .put("time", time)
                            .put("page", record.optString("page", record.optString("url", "")))
                    )
                }
            }
        }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("format", "web-research-forensic-relations-v1")
            .put("sessionId", archive.forensicSessionId)
            .put("generatedAt", System.currentTimeMillis())
            .put(
                "relationPolicy",
                JSONObject()
                    .put(
                        "temporal-nearest",
                        "inferred proximity only; not proof of JavaScript causality"
                    )
                    .put(
                        "observed-initiator-stack",
                        "JavaScript stack was captured synchronously when fetch/XHR started"
                    )
                    .put(
                        "observed-browser-event-context",
                        "same browser action context token was observed by both events"
                    )
            )
            .put("actions", JSONArray(actions.values.toList()))
            .put("requests", JSONArray(requests.values.toList()))
            .put("initiators", JSONArray(initiators.values.toList()))
            .put("causalityChains", causalityChains)
            .put("mutations", mutations)
            .put("checkpoints", checkpoints)
            .put("links", links)
    }

    private fun timelineEvent(record: JSONObject, index: Int): JSONObject {
        val out = JSONObject()
            .put("eventId", eventId(record, index))
            .put("sequence", record.optLong("sequence", index + 1L))
            .put("time", record.optLong("time", 0L))
            .put("capturedAt", record.optLong("capturedAt", 0L))
            .put("monotonicUs", record.optLong("monotonicUs", -1L))
            .put("source", record.optString("source", "unknown"))

        copyIfPresent(record, out, "action")
        copyIfPresent(record, out, "actionId")
        copyIfPresent(record, out, "relatedActionId")
        copyIfPresent(record, out, "actionRelation")
        copyIfPresent(record, out, "requestId")
        copyIfPresent(record, out, "relatedRequestId")
        copyIfPresent(record, out, "requestRelation")
        copyIfPresent(record, out, "mutationId")
        copyIfPresent(record, out, "checkpointId")
        copyIfPresent(record, out, "browserActionToken")
        copyIfPresent(record, out, "method")
        copyIfPresent(record, out, "url")
        copyIfPresent(record, out, "page")
        copyIfPresent(record, out, "status")
        copyIfPresent(record, out, "duration")
        copyIfPresent(record, out, "evidenceType")
        copyIfPresent(record, out, "code")
        record.optJSONObject("target")?.let { out.put("target", JSONObject(it.toString())) }
        return out
    }

    private fun addLink(
        links: JSONArray,
        keys: MutableSet<String>,
        from: String,
        to: String,
        type: String,
        method: String
    ) {
        val key = type + "|" + from + "|" + to
        if (!keys.add(key)) return
        links.put(
            JSONObject()
                .put("from", from)
                .put("to", to)
                .put("type", type)
                .put("method", method)
                .put("confidence", if (method == "temporal-nearest") "inferred" else "observed")
        )
    }

    private fun initiatorFrame(stack: String): String {
        if (stack.isBlank()) return ""
        val frames = stack.lines().map { it.trim() }.filter { it.startsWith("at ") }
        return frames.firstOrNull { !it.contains("wrapped", true) && !it.contains("__wr", true) }
            ?: frames.firstOrNull().orEmpty()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun putUnique(array: JSONArray, value: String) {
        for (index in 0 until array.length()) if (array.optString(index) == value) return
        array.put(value)
    }

    private fun copyIfPresent(source: JSONObject, target: JSONObject, key: String) {
        if (source.has(key)) target.put(key, source.opt(key))
    }

    private fun eventId(record: JSONObject, index: Int): String =
        record.optString("eventId", "").ifBlank { "legacy-event-%08d".format(index + 1) }
}
