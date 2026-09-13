package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class CaptureRegressionTest {
    @Before
    fun resetStore() {
        NetworkDebugStore.recording = true
        NetworkDebugStore.clear()
    }

    @Test
    fun forensicTimelineAssignsIdsAndBuildsTransparentTemporalRelations() {
        val archive = ResearchArchive()
        archive.addRecord(JSONObject()
            .put("source", "user-action")
            .put("time", 1_000L)
            .put("action", "click")
            .put("browserActionToken", "ctx-1")
            .put("page", "https://example.test/app"))
        archive.addRecord(JSONObject()
            .put("source", "webview")
            .put("time", 1_020L)
            .put("method", "POST")
            .put("url", "https://example.test/api/save"))
        archive.addRecord(JSONObject()
            .put("source", "fetch")
            .put("time", 1_018L)
            .put("duration", 120L)
            .put("method", "POST")
            .put("url", "https://example.test/api/save")
            .put("browserActionToken", "ctx-1")
            .put("initiatorStack", "Error\n    at XP.send (<anonymous>:57:97)\n    at clickHandler (https://example.test/app.js:42:7)")
            .put("status", 200))
        archive.addRecord(JSONObject()
            .put("source", "dom-mutation")
            .put("time", 1_300L)
            .put("page", "https://example.test/app"))

        val action = archive.records.getJSONObject(0)
        val webview = archive.records.getJSONObject(1)
        val fetch = archive.records.getJSONObject(2)
        val mutation = archive.records.getJSONObject(3)

        assertTrue(action.getString("eventId").startsWith("event-"))
        assertTrue(action.getString("actionId").startsWith("action-"))
        assertEquals(webview.getString("requestId"), fetch.getString("requestId"))
        assertEquals(action.getString("actionId"), fetch.getString("relatedActionId"))
        assertEquals("observed-browser-event-context", fetch.getString("actionRelation"))
        assertEquals(fetch.getString("requestId"), mutation.getString("relatedRequestId"))
        assertEquals("temporal-nearest", mutation.getString("requestRelation"))
        assertTrue(mutation.getString("mutationId").startsWith("mutation-"))
        assertEquals(4L, mutation.getLong("sequence"))
        assertTrue(mutation.getLong("monotonicUs") >= 0L)

        val timeline = ForensicTimelineExport.buildTimeline(archive)
        assertEquals(4, timeline.getJSONArray("events").length())
        val relations = ForensicTimelineExport.buildRelations(archive)
        assertEquals(1, relations.getJSONArray("actions").length())
        assertEquals(1, relations.getJSONArray("requests").length())
        assertEquals(1, relations.getJSONArray("initiators").length())
        assertEquals(1, relations.getJSONArray("causalityChains").length())
        assertTrue(relations.getJSONArray("initiators").getJSONObject(0).getString("frame").contains("clickHandler"))
        assertFalse(relations.getJSONArray("initiators").getJSONObject(0).getString("frame").contains("XP.send"))
        assertEquals("observed-browser-event-context", relations.getJSONArray("causalityChains").getJSONObject(0).getString("actionRelation"))
        assertEquals(1, relations.getJSONArray("mutations").length())
        assertTrue(relations.getJSONArray("links").length() >= 3)
        assertTrue(relations.getJSONObject("relationPolicy").getString("temporal-nearest").contains("not proof"))
    }

    @Test
    fun checkpointDiffsTrackCookieStorageAndDomChanges() {
        val archive = ResearchArchive()
        val before = JSONObject()
            .put("time", 1_000L)
            .put("url", "https://example.test/app")
            .put("title", "Before")
            .put("cookie", "sid=one")
            .put("nativeCookie", "sid=one")
            .put("localStorage", JSONObject().put("values", JSONObject().put("a", "1")))
            .put("sessionStorage", JSONObject().put("values", JSONObject().put("step", "one")))
            .put("viewport", JSONObject().put("scrollY", 0).put("innerWidth", 400).put("innerHeight", 800).put("devicePixelRatio", 2))
            .put("focus", JSONObject().put("key", "id:name"))
            .put("selection", JSONObject().put("anchorOffset", 1).put("focusOffset", 1).put("text", "A"))
            .put("frames", JSONObject().put("items", JSONArray()
                .put(JSONObject().put("key", "id:frame").put("sameOrigin", true)
                    .put("snapshot", JSONObject().put("dom", JSONObject().put("elements", JSONArray()
                        .put(JSONObject().put("key", "id:frameInput").put("runtime", JSONObject().put("value", "frame-before")))))))))
            .put("shadowDom", JSONObject().put("roots", JSONArray()
                .put(JSONObject().put("host", JSONObject().put("key", "id:widget")).put("html", "<input>")
                    .put("elements", JSONArray()
                        .put(JSONObject().put("key", "id:shadowInput").put("runtime", JSONObject().put("value", "shadow-before")))))))
            .put("dom", JSONObject().put("elements", JSONArray()
                .put(JSONObject().put("key", "id:save").put("text", "Save"))
                .put(JSONObject().put("key", "id:name").put("runtime", JSONObject().put("value", "Alice").put("selectionStart", 1).put("selectionEnd", 1)))
                .put(JSONObject().put("key", "id:agree").put("runtime", JSONObject().put("checked", false).put("indeterminate", false)))
                .put(JSONObject().put("key", "id:city").put("runtime", JSONObject().put("selectedIndex", 0).put("selectedValues", JSONArray().put("spb"))))))
        val after = JSONObject()
            .put("time", 2_000L)
            .put("url", "https://example.test/app")
            .put("title", "After")
            .put("cookie", "sid=two; token=abc")
            .put("nativeCookie", "sid=two; token=abc")
            .put("localStorage", JSONObject().put("values", JSONObject().put("a", "2").put("b", "3")))
            .put("sessionStorage", JSONObject().put("values", JSONObject().put("step", "two")))
            .put("viewport", JSONObject().put("scrollY", 240).put("innerWidth", 400).put("innerHeight", 650).put("devicePixelRatio", 2))
            .put("focus", JSONObject().put("key", "id:city"))
            .put("selection", JSONObject().put("anchorOffset", 0).put("focusOffset", 0).put("text", ""))
            .put("frames", JSONObject().put("items", JSONArray()
                .put(JSONObject().put("key", "id:frame").put("sameOrigin", true)
                    .put("snapshot", JSONObject()
                        .put("url", "https://example.test/frame")
                        .put("dom", JSONObject().put("elements", JSONArray()
                            .put(JSONObject().put("key", "id:frameInput").put("runtime", JSONObject().put("value", "frame-after")))))))))
            .put("shadowDom", JSONObject().put("roots", JSONArray()
                .put(JSONObject().put("host", JSONObject().put("key", "id:widget")).put("html", "<input value='after'>")
                    .put("elements", JSONArray()
                        .put(JSONObject().put("key", "id:shadowInput").put("runtime", JSONObject().put("value", "shadow-after")))))))
            .put("dom", JSONObject().put("elements", JSONArray()
                .put(JSONObject().put("key", "id:save").put("text", "Saved"))
                .put(JSONObject().put("key", "id:done").put("text", "Done"))
                .put(JSONObject().put("key", "id:name").put("runtime", JSONObject().put("value", "Alice Smith").put("selectionStart", 11).put("selectionEnd", 11)))
                .put(JSONObject().put("key", "id:agree").put("runtime", JSONObject().put("checked", true).put("indeterminate", false)))
                .put(JSONObject().put("key", "id:city").put("runtime", JSONObject().put("selectedIndex", 1).put("selectedValues", JSONArray().put("msk"))))))

        val beforeId = archive.addCheckpoint("before-action", before, null)
        val afterId = archive.addCheckpoint("after-action", after, byteArrayOf(1, 2, 3))

        assertEquals("checkpoint-00000001", beforeId)
        assertEquals("checkpoint-00000002", afterId)
        val index = CheckpointExport.buildIndex(archive)
        assertEquals(2, index.getInt("count"))
        val diffs = CheckpointExport.buildDiffs(archive).getJSONArray("diffs")
        assertEquals(1, diffs.length())
        val diff = diffs.getJSONObject(0)
        assertTrue(diff.getJSONObject("documentCookies").getJSONArray("changed").toString().contains("sid"))
        assertTrue(diff.getJSONObject("documentCookies").getJSONArray("added").toString().contains("token"))
        assertTrue(diff.getJSONObject("localStorage").getJSONArray("changed").toString().contains("a"))
        assertTrue(diff.getJSONObject("localStorage").getJSONArray("added").toString().contains("b"))
        assertTrue(diff.getJSONObject("dom").getJSONArray("changed").toString().contains("id:save"))
        assertTrue(diff.getJSONObject("dom").getJSONArray("added").toString().contains("id:done"))
        assertTrue(diff.getJSONObject("formValues").getJSONArray("changed").toString().contains("id:name"))
        assertTrue(diff.getJSONObject("formValues").getJSONArray("details").toString().contains("Alice Smith"))
        assertTrue(diff.getJSONObject("formValues").getJSONArray("changed").toString().contains("frame:id:frame/id:frameInput"))
        assertTrue(diff.getJSONObject("formValues").getJSONArray("changed").toString().contains("shadow:id:widget/id:shadowInput"))
        assertTrue(diff.getJSONObject("formChecked").getJSONArray("changed").toString().contains("id:agree"))
        assertTrue(diff.getJSONObject("formSelected").getJSONArray("changed").toString().contains("id:city"))
        assertTrue(diff.getJSONObject("focus").getJSONArray("changed").toString().contains("key"))
        assertTrue(diff.getJSONObject("selection").getInt("changedCount") > 0)
        assertTrue(diff.getJSONObject("viewport").getJSONArray("changed").toString().contains("scrollY"))
        assertTrue(diff.getJSONObject("frames").getJSONArray("changed").toString().contains("id:frame"))
        assertTrue(diff.getJSONObject("shadowDom").getJSONArray("changed").toString().contains("id:widget"))

        val output = ByteArrayOutputStream()
        ResearchArchiveExporter(archive).writeZip(output, "https://example.test/app")
        val entries = unzip(output.toByteArray())
        assertTrue(entries.containsKey("checkpoints/index.json"))
        assertTrue(entries.containsKey("checkpoint-diffs.json"))
        assertTrue(entries.containsKey("checkpoints/$beforeId/state.json"))
        assertTrue(entries.containsKey("checkpoints/$afterId/state.json"))
        assertTrue(entries.containsKey("checkpoints/$afterId/screenshot.jpg"))
    }

    @Test
    fun runtimeInstrumentationUsesStableUniqueElementIdsAndDebouncedInputCheckpoints() {
        val script = WebResearchScripts.instrumentation()

        assertTrue(script.contains("const wrElementIds=new WeakMap()"))
        assertTrue(script.contains("element-'+wrPageId+'-"))
        assertFalse(script.contains("return t.id?'id:'"))
        assertTrue(script.contains("addEventListener('beforeinput'"))
        assertTrue(script.contains("'before-input'"))
        assertTrue(script.contains("'after-input'"))
        assertTrue(script.contains("setTimeout(()=>{window.__WR_CAPTURE_CHECKPOINT('after-input'"))
    }

    @Test
    fun nativeCheckpointScriptInstallsInstrumentationAndReportsCaptureSuccess() {
        val script = WebResearchScripts.instrumentedCheckpoint("recording-start")

        assertTrue(script.contains("window.__WR10='installing'"))
        assertTrue(script.contains("window.__WR10=true"))
        assertTrue(script.contains("recording-start"))
        assertTrue(script.contains("return window.__WR_CAPTURE_CHECKPOINT"))
        assertTrue(script.contains("return true"))
        assertTrue(script.contains("return false"))

        val fullSnapshot = WebResearchScripts.fullSnapshot("", "snapshot-request-1")
        assertTrue(fullSnapshot.contains("snapshotRequestId:\"snapshot-request-1\""))
    }

    @Test
    fun forensicCorrelationDoesNotCrossBrowsingWindows() {
        val archive = ResearchArchive()
        archive.addRecord(JSONObject()
            .put("source", "user-action")
            .put("time", 1_000L)
            .put("windowId", "window-0001")
            .put("frameId", "frame-0001-main")
            .put("action", "click"))
        archive.addRecord(JSONObject()
            .put("source", "webview")
            .put("time", 1_010L)
            .put("windowId", "window-0001")
            .put("method", "POST")
            .put("url", "https://example.test/api"))
        archive.addRecord(JSONObject()
            .put("source", "webview")
            .put("time", 1_020L)
            .put("windowId", "window-0002")
            .put("method", "POST")
            .put("url", "https://example.test/api"))
        archive.addRecord(JSONObject()
            .put("source", "fetch")
            .put("time", 1_030L)
            .put("windowId", "window-0002")
            .put("frameId", "frame-0002-main")
            .put("method", "POST")
            .put("url", "https://example.test/api")
            .put("status", 200))

        val action = archive.records.getJSONObject(0)
        val webviewOne = archive.records.getJSONObject(1)
        val webviewTwo = archive.records.getJSONObject(2)
        val fetchTwo = archive.records.getJSONObject(3)

        assertEquals(webviewTwo.getString("requestId"), fetchTwo.getString("requestId"))
        assertFalse(fetchTwo.getString("requestId") == webviewOne.getString("requestId"))
        assertFalse(fetchTwo.has("relatedActionId"))
        assertTrue(action.has("actionId"))
    }

    @Test
    fun multiContextTimelineAndManifestPreserveWindowsAndFrames() {
        val archive = ResearchArchive()
        archive.addRecord(JSONObject()
            .put("source", "window-created")
            .put("time", 1_000L)
            .put("windowId", "window-0001")
            .put("frameId", "frame-0001-main")
            .put("creationReason", "initial"))
        archive.addRecord(JSONObject()
            .put("source", "frame-capture-mode")
            .put("time", 1_010L)
            .put("windowId", "window-0001")
            .put("frameId", "frame-0001-main")
            .put("mode", "modern"))
        archive.addRecord(JSONObject()
            .put("source", "user-action")
            .put("time", 1_100L)
            .put("windowId", "window-0001")
            .put("frameId", "frame-0001-0001")
            .put("sourceOrigin", "https://frame.example")
            .put("action", "click")
            .put("browserActionToken", "frame-token"))
        archive.addRecord(JSONObject()
            .put("source", "fetch")
            .put("time", 1_120L)
            .put("windowId", "window-0001")
            .put("frameId", "frame-0001-0001")
            .put("sourceOrigin", "https://frame.example")
            .put("method", "POST")
            .put("url", "https://frame.example/api")
            .put("status", 200)
            .put("browserActionToken", "frame-token"))
        archive.putArtifact("frames/window-0001/frame-0001-0001/snapshot-1.json", "{}".toByteArray())
        archive.putArtifact("windows/window-0001/page-snapshot.json", "{}".toByteArray())

        val timeline = ForensicTimelineExport.buildTimeline(archive).getJSONArray("events")
        assertEquals("window-0001", timeline.getJSONObject(2).getString("windowId"))
        assertEquals("frame-0001-0001", timeline.getJSONObject(2).getString("frameId"))

        val relations = ForensicTimelineExport.buildRelations(archive)
        assertEquals(1, relations.getJSONArray("windows").length())
        assertEquals(2, relations.getJSONArray("frames").length())
        assertEquals("observed-browser-event-context", relations.getJSONArray("requests").getJSONObject(0).getString("actionRelation"))

        val manifest = SessionManifestBuilder(archive).build("https://example.test")
        assertEquals(1, manifest.getJSONObject("counters").getInt("windowCount"))
        assertEquals(2, manifest.getJSONObject("counters").getInt("forensicFrameCount"))
        assertEquals("modern", manifest.getJSONObject("browsingContexts").getString("frameCaptureMode"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("frameSnapshotArtifacts"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("windowSnapshotArtifacts"))

        val output = ByteArrayOutputStream()
        ResearchArchiveExporter(archive).writeZip(output, "https://example.test")
        val entries = unzip(output.toByteArray())
        assertTrue(entries.containsKey("browsing-contexts.json"))
        assertTrue(entries.containsKey("browser/frames/window-0001/frame-0001-0001/snapshot-1.json"))
        assertTrue(entries.containsKey("browser/windows/window-0001/page-snapshot.json"))
    }

    @Test
    fun recordingWindowKeepsEarlierSupportingAssetsAndFiltersCookieTrace() {
        val archive = ResearchArchive()
        val base = System.currentTimeMillis()
        val startedAt = base + 10_000L
        val endedAt = base + 11_000L

        archive.putScript("https://example.test/app.js", "console.log('support')".toByteArray())
        archive.putResource(
            "https://example.test/app.css",
            "body{}".toByteArray(),
            JSONObject().put("contentType", "text/css")
        )
        archive.putArtifact(
            "cookie-trace.json",
            JSONObject()
                .put("format", "evrasia-cookie-trace-v2")
                .put("events", JSONArray()
                    .put(JSONObject().put("time", startedAt - 500L).put("name", "before"))
                    .put(JSONObject().put("time", startedAt + 500L).put("name", "inside")))
                .toString()
                .toByteArray()
        )

        val window = archive.snapshotWindow(startedAt, endedAt)

        assertTrue(window.scripts.containsKey("https://example.test/app.js"))
        assertTrue(window.resources.containsKey("https://example.test/app.css"))
        val trace = JSONObject(requireNotNull(window.extraArtifacts["cookie-trace.json"]).toString(Charsets.UTF_8))
        assertEquals(1, trace.getJSONArray("events").length())
        assertEquals("inside", trace.getJSONArray("events").getJSONObject(0).getString("name"))
    }

    @Test
    fun rawRecordIsNotMutatedByDebuggerNormalization() {
        val raw = JSONArray()
        val record = JSONObject()
            .put("source", "webview")
            .put("time", 1000L)
            .put("method", "GET")
            .put("url", "https://example.test/api/items")
            .put("headers", JSONObject().put("X-Legacy", "legacy-value"))
        val original = record.toString()

        NetworkRecordPipeline.appendRawAndDebug(raw, record)

        assertEquals(1, raw.length())
        assertEquals(original, raw.getJSONObject(0).toString())
        assertFalse(raw.getJSONObject(0).has("requestHeaders"))
        val debug = NetworkDebugStore.snapshot().single()
        assertEquals("legacy-value", debug.getJSONObject("requestHeaders").getString("X-Legacy"))
    }

    @Test
    fun largeResponseBodyStaysRawButDebuggerUsesBoundedPreview() {
        val largeBody = "x".repeat(900_000)
        val raw = JSONArray()
        val record = JSONObject()
            .put("source", "fetch")
            .put("time", 1000L)
            .put("method", "GET")
            .put("url", "https://example.test/large")
            .put("status", 200)
            .put("responseBody", largeBody)

        NetworkRecordPipeline.appendRawAndDebug(raw, record)

        assertEquals(largeBody.length, raw.getJSONObject(0).getString("responseBody").length)
        assertFalse(raw.getJSONObject(0).has("responseBodyTruncated"))

        val debug = NetworkDebugStore.snapshot().single()
        assertTrue(debug.getString("responseBody").length < largeBody.length)
        assertTrue(debug.getBoolean("responseBodyTruncated"))
        assertEquals(largeBody.length, debug.getInt("responseBodyOriginalChars"))
    }

    @Test
    fun correlatedDebuggerCopyKeepsOneRequestAndCombinesEvidence() {
        val webview = JSONObject()
            .put("source", "webview")
            .put("time", 1000L)
            .put("method", "GET")
            .put("url", "https://example.test/api/items")
        val fetch = JSONObject()
            .put("source", "fetch")
            .put("time", 1050L)
            .put("method", "GET")
            .put("url", "https://example.test/api/items")
            .put("status", 200)
            .put("responseBody", "{\"ok\":true}")

        NetworkDebugStore.add(webview)
        NetworkDebugStore.add(fetch)

        val snapshot = NetworkDebugStore.snapshot()
        assertEquals(1, snapshot.size)
        val merged = snapshot.single()
        assertEquals(200, merged.getInt("status"))
        assertEquals("{\"ok\":true}", merged.getString("responseBody"))
        val sources = merged.getJSONArray("capturedSources")
        assertTrue((0 until sources.length()).map { sources.getString(it) }.containsAll(listOf("webview", "fetch")))
    }

    @Test
    fun projectionCollapsesRealtimeAndPreservesActions() {
        val fixture = fixture()
        val all = fixture.getJSONArray("events").objects().reversed()
        val result = NetworkDebuggerProjection.build(
            allItems = all,
            mergeMode = false,
            domain = "Все домены",
            type = "ALL",
            method = "ALL",
            query = "",
            methodFilters = listOf("ALL", "GET", "POST", "WS", "SSE", "ACTION", "OTHER")
        )

        val ws = result.rows.single { it.optBoolean("_realtimeSession") && it.optString("_realtimeProtocol") == "WS" }
        val sse = result.rows.single { it.optBoolean("_realtimeSession") && it.optString("_realtimeProtocol") == "SSE" }
        assertEquals(3, ws.getInt("_sessionCount"))
        assertEquals(2, sse.getInt("_sessionCount"))
        assertTrue(result.rows.any { it.optString("source") == "user-action" })
        assertTrue(result.counterText.contains("действий"))
    }

    @Test
    fun changedDetectionMarksNewestDifferentResponse() {
        val older = JSONObject()
            .put("source", "fetch")
            .put("_storeId", 10L)
            .put("time", 1000L)
            .put("method", "GET")
            .put("url", "https://example.test/api/state")
            .put("status", 200)
            .put("mimeType", "application/json")
            .put("responseBody", "{\"value\":1}")
        val newer = JSONObject(older.toString())
            .put("_storeId", 11L)
            .put("time", 2000L)
            .put("responseBody", "{\"value\":2}")

        val result = NetworkDebuggerProjection.build(
            allItems = listOf(newer, older),
            mergeMode = false,
            domain = "Все домены",
            type = "ALL",
            method = "ALL",
            query = "",
            methodFilters = listOf("ALL", "GET", "OTHER")
        )

        assertTrue(result.changedIds.contains(11L))
        assertFalse(result.changedIds.contains(10L))
    }

    @Test
    fun cookieSupportSelectsExactCurrentOrigin() {
        val history = listOf(
            JSONObject()
                .put("time", 1000L)
                .put("action", "OBSERVED")
                .put("name", "session")
                .put("value", "old")
                .put("confidence", "UNKNOWN")
                .put("origin", "PREEXISTING_OR_UNKNOWN"),
            JSONObject()
                .put("time", 2000L)
                .put("action", "SET")
                .put("name", "session")
                .put("value", "current")
                .put("confidence", "EXACT")
                .put("origin", "HTTP_RESPONSE")
        )

        val current = CookieTraceSupport.currentOrigin(history, "current")
        val regeneration = CookieTraceSupport.regenerationEvent(history, "current")
        assertNotNull(current)
        assertEquals("HTTP_RESPONSE", current!!.getString("origin"))
        assertEquals("HTTP_RESPONSE", regeneration!!.getString("origin"))
        assertEquals("abc", CookieTraceSupport.parseCookieHeader("a=abc; b=2")["a"])
    }

    @Test
    fun archiveExporterKeepsRawEventsAndExpectedZipStructure() {
        val fixture = fixture()
        val archive = ResearchArchive()
        val events = fixture.getJSONArray("events")
        for (index in 0 until events.length()) {
            archive.addRecord(JSONObject(events.getJSONObject(index).toString()))
        }
        archive.updateSnapshot(JSONObject()
            .put("html", "<html><body>fixture</body></html>")
            .put("serviceWorkers", JSONArray().put(JSONObject().put("scope", "https://example.test/").put("active", "https://example.test/sw.js")))
            .put("resources", JSONArray().put(JSONObject().put("name", "https://example.test/app.js")))
            .put("runtimeUi", JSONObject()
                .put("viewport", JSONObject().put("scrollY", 120).put("innerWidth", 400).put("innerHeight", 800).put("devicePixelRatio", 2))
                .put("focus", JSONObject().put("key", "id:name"))
                .put("selection", JSONObject().put("anchorOffset", 0).put("focusOffset", 0))
                .put("dom", JSONObject().put("captured", 3).put("elements", JSONArray()))
                .put("frames", JSONObject().put("items", JSONArray()
                    .put(JSONObject().put("key", "id:frame").put("sameOrigin", true).put("snapshot", JSONObject().put("url", "https://example.test/frame")))))
                .put("shadowDom", JSONObject().put("captured", 1).put("roots", JSONArray()
                    .put(JSONObject().put("host", JSONObject().put("key", "id:widget")).put("html", "<button>Shadow</button>"))))))
        archive.putScript("https://example.test/app.js", "console.log('fixture')\n//# sourceMappingURL=app.js.map".toByteArray())
        archive.putResource(
            "https://example.test/logo.png",
            byteArrayOf(1, 2, 3, 4),
            JSONObject().put("contentType", "image/png")
        )
        archive.putArtifact("fixture/meta.json", "{\"fixture\":true}".toByteArray())

        val output = ByteArrayOutputStream()
        ResearchArchiveExporter(archive).writeZip(output, fixture.getString("page"))
        val entries = unzip(output.toByteArray())

        val required = setOf(
            "session-manifest.json",
            "timeline.json",
            "relations.json",
            "checkpoints/index.json",
            "checkpoint-diffs.json",
            "raw-events.json",
            "network.har",
            "api-summary.json",
            "actions.json",
            "browsing-contexts.json",
            "dom-mutations.json",
            "realtime.json",
            "performance.json",
            "page-snapshot.json",
            "page.html",
            "js/manifest.json",
            "resources/manifest.json",
            "browser/fixture/meta.json"
        )
        assertTrue(entries.keys.containsAll(required))
        val raw = JSONObject(entries.getValue("raw-events.json").toString(Charsets.UTF_8))
        assertEquals("evrasia-research-v5", raw.getString("format"))
        assertEquals(events.length(), raw.getJSONArray("records").length())
        assertTrue(raw.getJSONArray("records").getJSONObject(0).has("eventId"))
        val timeline = JSONObject(entries.getValue("timeline.json").toString(Charsets.UTF_8))
        assertEquals(events.length(), timeline.getJSONArray("events").length())
        val relations = JSONObject(entries.getValue("relations.json").toString(Charsets.UTF_8))
        assertEquals(archive.forensicSessionId, relations.getString("sessionId"))
        val har = JSONObject(entries.getValue("network.har").toString(Charsets.UTF_8))
        assertEquals(3, har.getJSONObject("log").getJSONArray("entries").length())
        val api = JSONObject(entries.getValue("api-summary.json").toString(Charsets.UTF_8))
        assertEquals(4, api.getInt("endpointCount"))
        val manifest = JSONObject(entries.getValue("session-manifest.json").toString(Charsets.UTF_8))
        assertEquals(1, manifest.getInt("schemaVersion"))
        assertEquals(events.length(), manifest.getJSONObject("counters").getInt("rawEvents"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("scriptsArchived"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("resourcesArchived"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("browserArtifacts"))
        assertEquals(events.length(), manifest.getJSONObject("counters").getInt("forensicEventIds"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("sourceMapHints"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("serviceWorkerRegistrations"))
        assertTrue(manifest.getJSONObject("advancedChannels").getJSONObject("sourceMaps").getJSONArray("hints").toString().contains("app.js.map"))
        assertTrue(manifest.getJSONObject("advancedChannels").getJSONObject("connectionDiagnostics").getString("dns").contains("unavailable"))
        assertTrue(manifest.getJSONObject("forensic").getBoolean("allRawEventsHaveEventId"))
        assertEquals(80, manifest.getJSONObject("limits").getInt("checkpointsPerRecordingWindow"))
        assertEquals(80, manifest.getJSONObject("limits").getInt("checkpointScreenshotsPerRecordingWindow"))
        assertTrue(manifest.getJSONObject("completeness").getBoolean("runtimeUiSnapshotCaptured"))
        assertTrue(manifest.getJSONObject("completeness").getBoolean("viewportStateCaptured"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("iframeCount"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("sameOriginFrameSnapshots"))
        assertEquals(1, manifest.getJSONObject("counters").getInt("openShadowRoots"))
        assertEquals("unavailable-without-invasive-attachShadow-interception", manifest.getJSONObject("runtimeUi").getString("closedShadowRoots"))
        assertTrue(manifest.getJSONObject("completeness").getBoolean("pageHtmlCaptured"))
        assertFalse(manifest.getJSONObject("completeness").getBoolean("fullSnapshotCaptured"))
        assertTrue(
            manifest.getJSONArray("warnings").objects()
                .any { it.getString("code") == "full_snapshot_missing" }
        )
    }

    private fun fixture(): JSONObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("session-fixture.json"))
        return stream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val buffer = ByteArrayOutputStream()
                zip.copyTo(buffer)
                out[entry.name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }
        return out
    }
}
