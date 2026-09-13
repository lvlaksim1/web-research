package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

internal class SessionManifestBuilder(private val archive: ResearchArchive) {
    fun build(pageUrl: String): JSONObject {
        val sourceCounts = linkedMapOf<String, Int>()
        val warningCodeCounts = linkedMapOf<String, Int>()
        val rawWarnings = JSONArray()
        var eventIds = 0
        var actionIds = 0
        var requestIds = 0
        var mutationIds = 0
        var relatedActions = 0
        var relatedRequests = 0
        synchronized(archive) {
            for (index in 0 until archive.records.length()) {
                val record = archive.records.optJSONObject(index) ?: continue
                val source = record.optString("source", "").ifBlank { "unknown" }
                sourceCounts[source] = (sourceCounts[source] ?: 0) + 1
                if (record.optString("eventId", "").isNotBlank()) eventIds++
                if (record.optString("actionId", "").isNotBlank()) actionIds++
                if (record.optString("requestId", "").isNotBlank()) requestIds++
                if (record.optString("mutationId", "").isNotBlank()) mutationIds++
                if (record.optString("relatedActionId", "").isNotBlank()) relatedActions++
                if (record.optString("relatedRequestId", "").isNotBlank()) relatedRequests++
                if (source == "capture-warning") {
                    val code = record.optString("code", "capture_warning")
                    warningCodeCounts[code] = (warningCodeCounts[code] ?: 0) + 1
                    rawWarnings.put(JSONObject(record.toString()))
                }
            }
        }

        val snapshot = try { JSONObject(archive.snapshot.toString()) } catch (_: Exception) { JSONObject() }
        val sourceCountsJson = JSONObject()
        sourceCounts.toSortedMap().forEach { (source, count) -> sourceCountsJson.put(source, count) }
        val warningCodeCountsJson = JSONObject()
        warningCodeCounts.toSortedMap().forEach { (code, count) -> warningCodeCountsJson.put(code, count) }

        var redirectChains = 0
        var redirectHops = 0
        var redirectLimitReached = 0
        var resourceCopyFailures = 0
        archive.resourceMeta.values.forEach { meta ->
            val hops = meta.optInt("redirectCount", 0)
            if (hops > 0) redirectChains++
            redirectHops += hops
            if (meta.optBoolean("redirectLimitReached", false)) redirectLimitReached++
            val status = meta.optInt("status", 0)
            if (meta.has("error") || status >= 400) resourceCopyFailures++
        }

        val metadataOnly = archive.resourceMeta.keys.count { !archive.resources.containsKey(it) }
        val indexedDbArtifacts = archive.extraArtifacts.keys.count { it.startsWith("indexeddb-") }
        val cacheStorageArtifacts = archive.extraArtifacts.keys.count { it.startsWith("cache-") }
        val scriptRedirectArtifacts = archive.extraArtifacts.keys.count { it.startsWith("script-redirect-") }
        val checkpointStateArtifacts = archive.extraArtifacts.keys.count { it.startsWith("checkpoints/") && it.endsWith("/state.json") }
        val checkpointScreenshots = archive.extraArtifacts.keys.count { it.startsWith("checkpoints/") && it.endsWith("/screenshot.jpg") }
        val checkpointEvents = sourceCounts["checkpoint"] ?: 0
        val advancedChannels = buildAdvancedChannels(snapshot, sourceCounts)
        val sourceMapHints = advancedChannels.getJSONObject("sourceMaps").optInt("count", 0)
        val serviceWorkerRegistrations = advancedChannels.getJSONObject("serviceWorkers").optInt("count", 0)
        val webSocketEvents = advancedChannels.getJSONObject("webSocket").optInt("events", 0)
        val sseEvents = advancedChannels.getJSONObject("sse").optInt("events", 0)

        val runtimeUi = snapshot.optJSONObject("runtimeUi") ?: JSONObject()
        val runtimeFrames = runtimeUi.optJSONObject("frames")?.optJSONArray("items") ?: JSONArray()
        var sameOriginFrameSnapshots = 0
        for (index in 0 until runtimeFrames.length()) {
            val frame = runtimeFrames.optJSONObject(index) ?: continue
            if (frame.optBoolean("sameOrigin", false) && frame.has("snapshot")) sameOriginFrameSnapshots++
        }
        val shadowRootCount = runtimeUi.optJSONObject("shadowDom")?.optInt("captured", 0) ?: 0
        val runtimeDomElements = runtimeUi.optJSONObject("dom")?.optInt("captured", 0) ?: 0
        val runtimeUiCaptured = runtimeUi.length() > 0 && !runtimeUi.has("error")

        val fullSnapshotCaptured = snapshot.optBoolean("fullSnapshot", false)
        val pageHtmlCaptured = snapshot.optString("html", "").isNotEmpty()
        val cacheSnapshotHasError = cacheSnapshotHasError(snapshot)
        val indexedDbSnapshotHasError = indexedDbSnapshotHasError(snapshot)

        val warnings = JSONArray()
        if (!fullSnapshotCaptured) {
            addWarning(warnings, "full_snapshot_missing", "Full page snapshot was not present when the ZIP was created.")
        }
        if (!runtimeUiCaptured) {
            addWarning(warnings, "runtime_ui_snapshot_missing", "Runtime UI state was not present in the final page snapshot.")
        }
        if (archive.scriptErrors.isNotEmpty()) {
            addWarning(warnings, "script_archive_errors", "One or more JavaScript files could not be archived.", archive.scriptErrors.size)
        }
        if (resourceCopyFailures > 0) {
            addWarning(warnings, "resource_copy_failures", "One or more derivative resource copies failed.", resourceCopyFailures)
        }
        if (redirectLimitReached > 0) {
            addWarning(warnings, "redirect_limit_reached", "A derivative resource redirect chain reached the configured hop limit.", redirectLimitReached)
        }
        if (cacheSnapshotHasError) {
            addWarning(warnings, "cache_storage_snapshot_error", "Cache Storage snapshot reported an error.")
        }
        if (indexedDbSnapshotHasError) {
            addWarning(warnings, "indexeddb_snapshot_error", "IndexedDB snapshot reported an error.")
        }
        for (index in 0 until rawWarnings.length()) warnings.put(rawWarnings.getJSONObject(index))

        val completeness = JSONObject()
            .put("snapshotMode", when {
                fullSnapshotCaptured -> "full"
                snapshot.optBoolean("lightweight", false) -> "light"
                snapshot.length() > 0 -> "partial"
                else -> "none"
            })
            .put("pageSnapshotCaptured", snapshot.length() > 0)
            .put("fullSnapshotCaptured", fullSnapshotCaptured)
            .put("pageHtmlCaptured", pageHtmlCaptured)
            .put("cookiesSnapshotCaptured", snapshot.has("cookie") || snapshot.has("nativeCookie"))
            .put("localStorageSnapshotCaptured", snapshot.has("localStorage"))
            .put("sessionStorageSnapshotCaptured", snapshot.has("sessionStorage"))
            .put("serviceWorkersSnapshotCaptured", snapshot.has("serviceWorkers"))
            .put("cacheStorageSnapshotCaptured", snapshot.has("cacheStorage"))
            .put("indexedDbSnapshotCaptured", snapshot.has("indexedDB"))
            .put("resourceTimingSnapshotCaptured", snapshot.has("resources"))
            .put("runtimeUiSnapshotCaptured", runtimeUiCaptured)
            .put("viewportStateCaptured", runtimeUi.has("viewport"))
            .put("focusStateCaptured", runtimeUi.has("focus"))
            .put("selectionStateCaptured", runtimeUi.has("selection"))
            .put("iframeInventoryCaptured", runtimeUi.has("frames"))
            .put("openShadowDomCaptured", runtimeUi.has("shadowDom"))
            .put("checkpointsCaptured", checkpointEvents > 0)
            .put("checkpointDiffsAvailable", checkpointEvents > 1)
            .put("advancedChannelSummaryAvailable", true)
            .put("sourceMapHintsAvailable", sourceMapHints > 0)

        val counters = JSONObject()
            .put("rawEvents", archive.records.length())
            .put("rawEventsBySource", sourceCountsJson)
            .put("captureWarnings", rawWarnings.length())
            .put("captureWarningsByCode", warningCodeCountsJson)
            .put("scriptsArchived", archive.scripts.size)
            .put("scriptErrors", archive.scriptErrors.size)
            .put("resourcesArchived", archive.resources.size)
            .put("resourceMetadata", archive.resourceMeta.size)
            .put("resourceMetadataOnly", metadataOnly)
            .put("resourceCopyFailures", resourceCopyFailures)
            .put("derivativeRedirectChains", redirectChains)
            .put("derivativeRedirectHops", redirectHops)
            .put("redirectLimitReached", redirectLimitReached)
            .put("browserArtifacts", archive.extraArtifacts.size)
            .put("indexedDbArtifacts", indexedDbArtifacts)
            .put("cacheStorageArtifacts", cacheStorageArtifacts)
            .put("scriptRedirectArtifacts", scriptRedirectArtifacts)
            .put("checkpointEvents", checkpointEvents)
            .put("checkpointStateArtifacts", checkpointStateArtifacts)
            .put("checkpointScreenshots", checkpointScreenshots)
            .put("runtimeDomElements", runtimeDomElements)
            .put("iframeCount", runtimeFrames.length())
            .put("sameOriginFrameSnapshots", sameOriginFrameSnapshots)
            .put("openShadowRoots", shadowRootCount)
            .put("sourceMapHints", sourceMapHints)
            .put("serviceWorkerRegistrations", serviceWorkerRegistrations)
            .put("webSocketEvents", webSocketEvents)
            .put("sseEvents", sseEvents)
            .put("warnings", warnings.length())
            .put("forensicEventIds", eventIds)
            .put("forensicActionIds", actionIds)
            .put("forensicRequestIds", requestIds)
            .put("forensicMutationIds", mutationIds)
            .put("forensicRelatedActions", relatedActions)
            .put("forensicRelatedRequests", relatedRequests)

        val forensic = JSONObject()
            .put("sessionId", archive.forensicSessionId)
            .put("sessionStartedAt", archive.forensicSessionStartedAtMs)
            .put("timelineFormat", "web-research-forensic-timeline-v1")
            .put("relationsFormat", "web-research-forensic-relations-v1")
            .put("allRawEventsHaveEventId", eventIds == archive.records.length())
            .put("relationPolicy", "temporal-nearest links are inferred, not proof of JavaScript causality")

        val limits = JSONObject()
            .put("cacheRequestsPerCache", 250)
            .put("cacheTextBodyChars", 200000)
            .put("indexedDbValuesPerStore", 1000)
            .put("fullSnapshotElements", 10000)
            .put("lightSnapshotElements", 2500)
            .put("bridgeChunkChars", 100000)
            .put("derivativeRedirectHops", 10)
            .put("checkpointsPerRecordingWindow", 80)
            .put("checkpointScreenshotsPerRecordingWindow", 80)
            .put("checkpointStateChars", 1500000)
            .put("checkpointDomElements", 500)
            .put("checkpointStorageKeys", 50)
            .put("checkpointStorageValueChars", 4096)
            .put("runtimeFormValueChars", 4096)
            .put("runtimeSelectedValues", 100)
            .put("iframeInventoryPerDocument", 50)
            .put("sameOriginFrameSnapshotDepth", 1)
            .put("sameOriginFrameSnapshots", 10)
            .put("frameRuntimeDomElements", 150)
            .put("openShadowRoots", 40)
            .put("shadowScanElements", 5000)
            .put("shadowElementsPerRoot", 80)
            .put("shadowHtmlCharsPerRoot", 10000)
            .put("frameOpenShadowRoots", 10)
            .put("frameShadowScanElements", 1500)
            .put("frameShadowElementsPerRoot", 40)
            .put("frameShadowHtmlCharsPerRoot", 5000)
            .put("sourceMapHints", 200)

        return JSONObject()
            .put("schemaVersion", 1)
            .put("format", "web-research-session-manifest-v1")
            .put("exportedAt", System.currentTimeMillis())
            .put("page", pageUrl)
            .put("counters", counters)
            .put("completeness", completeness)
            .put("forensic", forensic)
            .put("runtimeUi", JSONObject()
                .put("captured", runtimeUiCaptured)
                .put("viewport", runtimeUi.optJSONObject("viewport") ?: JSONObject.NULL)
                .put("frameCount", runtimeFrames.length())
                .put("sameOriginFrameSnapshots", sameOriginFrameSnapshots)
                .put("openShadowRoots", shadowRootCount)
                .put("closedShadowRoots", "unavailable-without-invasive-attachShadow-interception"))
            .put("advancedChannels", advancedChannels)
            .put("limits", limits)
            .put("warnings", warnings)
    }

    private fun buildAdvancedChannels(snapshot: JSONObject, sourceCounts: Map<String, Int>): JSONObject {
        val serviceWorkers = try {
            JSONArray(snapshot.optJSONArray("serviceWorkers")?.toString() ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val sourceMaps = buildSourceMapHints()
        val webSocketEvents = (sourceCounts["websocket-open"] ?: 0) +
            (sourceCounts["websocket-state"] ?: 0) +
            (sourceCounts["websocket-send"] ?: 0) +
            (sourceCounts["websocket-receive"] ?: 0)
        val sseEvents = (sourceCounts["sse-open"] ?: 0) +
            (sourceCounts["sse-state"] ?: 0) +
            (sourceCounts["sse-message"] ?: 0)
        val performanceEvents = (sourceCounts["performance"] ?: 0) +
            (sourceCounts["long-task"] ?: 0) +
            (sourceCounts["resource-timing"] ?: 0) +
            (sourceCounts["navigation-timing"] ?: 0)
        val snapshotResources = snapshot.optJSONArray("resources")?.length() ?: 0

        return JSONObject()
            .put("serviceWorkers", JSONObject()
                .put("snapshotCaptured", snapshot.has("serviceWorkers"))
                .put("count", serviceWorkers.length())
                .put("registrations", serviceWorkers))
            .put("workers", JSONObject()
                .put("dedicatedWorkerRuntimeCaptured", false)
                .put("sharedWorkerRuntimeCaptured", false)
                .put("reason", "Worker global execution is not instrumented because rewriting worker script URLs would change the researched page execution path."))
            .put("webSocket", JSONObject()
                .put("events", webSocketEvents)
                .put("opens", sourceCounts["websocket-open"] ?: 0)
                .put("sends", sourceCounts["websocket-send"] ?: 0)
                .put("receives", sourceCounts["websocket-receive"] ?: 0))
            .put("sse", JSONObject()
                .put("events", sseEvents)
                .put("opens", sourceCounts["sse-open"] ?: 0)
                .put("messages", sourceCounts["sse-message"] ?: 0))
            .put("performance", JSONObject()
                .put("rawEvents", performanceEvents)
                .put("snapshotResourceEntries", snapshotResources)
                .put("navigationTimingEvents", sourceCounts["navigation-timing"] ?: 0)
                .put("resourceTimingEvents", sourceCounts["resource-timing"] ?: 0)
                .put("longTaskEvents", sourceCounts["long-task"] ?: 0))
            .put("sourceMaps", sourceMaps)
            .put("connectionDiagnostics", JSONObject()
                .put("dns", "unavailable-via-current-webview-api")
                .put("tlsHandshake", "unavailable-via-current-webview-api")
                .put("certificateChain", "unavailable-via-current-webview-api")
                .put("note", "No synthetic proxy or MITM layer is introduced solely to obtain these fields."))
    }

    private fun buildSourceMapHints(): JSONObject {
        val hints = JSONArray()
        var total = 0
        archive.scripts.entries.sortedBy { it.key }.forEach { entry ->
            val bytes = entry.value
            if (bytes.isEmpty()) return@forEach
            val start = maxOf(0, bytes.size - 16384)
            val tail = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
            val marker = "sourceMappingURL="
            tail.lines().forEach { line ->
                if (!line.contains(marker)) return@forEach
                val value = line.substringAfter(marker)
                    .substringBefore("*/")
                    .trim()
                    .trimEnd(';')
                    .trim()
                if (value.isBlank()) return@forEach
                total++
                if (hints.length() < 200) {
                    hints.put(
                        JSONObject()
                            .put("scriptUrl", entry.key)
                            .put("mappingUrl", value)
                            .put("inlineData", value.startsWith("data:", true))
                    )
                }
            }
        }
        return JSONObject()
            .put("count", total)
            .put("captured", hints.length())
            .put("truncated", total > hints.length())
            .put("hints", hints)
    }

    private fun addWarning(target: JSONArray, code: String, message: String, count: Int = 1) {
        target.put(
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put("count", count)
        )
    }

    private fun cacheSnapshotHasError(snapshot: JSONObject): Boolean {
        val values = snapshot.optJSONArray("cacheStorage") ?: return false
        for (index in 0 until values.length()) {
            if (values.optString(index, "").startsWith("ERROR:")) return true
        }
        return false
    }

    private fun indexedDbSnapshotHasError(snapshot: JSONObject): Boolean {
        val values = snapshot.optJSONArray("indexedDB") ?: return false
        for (index in 0 until values.length()) {
            if (values.optJSONObject(index)?.has("error") == true) return true
        }
        return false
    }
}
