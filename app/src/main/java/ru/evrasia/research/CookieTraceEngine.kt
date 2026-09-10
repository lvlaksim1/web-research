package ru.evrasia.research

import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

internal class CookieTraceEngine {
    private var processedRecords = 0
    private var lastHookUrl = ""
    private var lastProgress = -1
    private val snapshots = linkedMapOf<String, MutableMap<String, String>>()
    private val events = mutableListOf<JSONObject>()
    private val recentRequests = ArrayDeque<JSONObject>()
    private val lastExact = mutableMapOf<String, Long>()
    private val fingerprints = linkedSetOf<String>()

    private data class ParsedCookie(
        val name: String,
        val value: String,
        val action: String,
        val domain: String,
        val path: String
    )

    fun onBrowserResumed(activity: WebResearchV10Activity) {
        activity.researchWebView()?.let { injectCookieHooks(it) }
        sample(activity)
    }

    fun sample(activity: WebResearchV10Activity) {
        val web = activity.researchWebView() ?: return
        val page = web.url.orEmpty()
        val progress = web.progress
        if (page.isNotBlank() && (page != lastHookUrl || progress < 100 || lastProgress < 100)) {
            injectCookieHooks(web)
            lastHookUrl = page
        }
        lastProgress = progress

        val archive = activity.researchArchive() ?: return
        synchronized(archive) {
            val count = archive.records.length()
            if (count < processedRecords) reset()
            for (i in processedRecords until count) {
                archive.records.optJSONObject(i)?.let { ingestNetworkRecord(JSONObject(it.toString())) }
            }
            processedRecords = count
            ingestJsArtifacts(archive)
        }

        if (page.startsWith("http://") || page.startsWith("https://")) {
            observeCookieSnapshot(page, CookieManager.getInstance().getCookie(page).orEmpty(), System.currentTimeMillis())
        }
        archive.extraArtifacts["cookie-trace.json"] = exportJson(page).toString(2).toByteArray(Charsets.UTF_8)
    }

    fun eventsSnapshot(): List<JSONObject> = events.map { JSONObject(it.toString()) }

    fun reset() {
        processedRecords = 0
        snapshots.clear()
        events.clear()
        recentRequests.clear()
        lastExact.clear()
        fingerprints.clear()
        lastHookUrl = ""
        lastProgress = -1
    }

    fun scopeHost(page: String, explicitDomain: String = ""): String {
        if (explicitDomain.isNotBlank()) return explicitDomain.lowercase(Locale.US).trimStart('.')
        return try { URL(page).host.lowercase(Locale.US) } catch (_: Exception) { "" }
    }

    fun relevantToHost(event: JSONObject, host: String): Boolean {
        val domain = event.optString("domain", "").trimStart('.').lowercase(Locale.US)
        val eventHost = if (domain.isNotBlank()) domain else scopeHost(event.optString("page", event.optString("url", "")))
        if (eventHost.isBlank() || host.isBlank()) return true
        return host == eventHost || host.endsWith(".$eventHost") || eventHost.endsWith(".$host")
    }

    private fun injectCookieHooks(web: WebView) {
        val js = """
            (function(){
              if(window.__WR_COOKIE_TRACE)return;window.__WR_COOKIE_TRACE=true;
              const emit=o=>{try{const k='cookie-trace-event/'+Date.now()+'-'+Math.random().toString(36).slice(2);EvrasiaResearch.artifactChunk(k,0,1,JSON.stringify(o))}catch(e){}};
              const first=raw=>{raw=String(raw||'');const p=raw.indexOf(';'),head=(p>=0?raw.slice(0,p):raw),eq=head.indexOf('=');return {raw:raw,name:(eq>=0?head.slice(0,eq):head).trim(),value:eq>=0?head.slice(eq+1):''}};
              const hasName=(raw,name)=>String(raw||'').split(';').some(x=>x.trim().startsWith(name+'='));
              const deleted=raw=>/max-age\s*=\s*0/i.test(raw)||/expires\s*=\s*(?:thu,\s*)?0?1[-\s]jan[-\s]1970/i.test(raw);
              const stack=()=>{try{return (new Error()).stack||''}catch(e){return''}};
              try{
                const d=Object.getOwnPropertyDescriptor(Document.prototype,'cookie')||Object.getOwnPropertyDescriptor(HTMLDocument.prototype,'cookie');
                if(d&&d.get&&d.set){
                  Object.defineProperty(document,'cookie',{configurable:true,enumerable:d.enumerable,get:function(){return d.get.call(document)},set:function(v){
                    const raw=String(v),p=first(raw);let before='';try{before=d.get.call(document)||''}catch(e){}
                    const existed=p.name?hasName(before,p.name):false;const action=deleted(raw)?'DELETE':(existed?'UPDATE':'CREATE');const s=stack();
                    const r=d.set.call(document,v);
                    if(p.name)emit({source:'cookie-js-trace',time:Date.now(),action:action,mechanism:'document.cookie',name:p.name,value:p.value,raw:raw,page:location.href,stack:s,confidence:'EXACT'});
                    return r;
                  }});
                }
              }catch(e){}
              try{
                const cs=window.cookieStore;
                if(cs&&!cs.__wrCookieTrace){
                  try{Object.defineProperty(cs,'__wrCookieTrace',{value:true})}catch(e){cs.__wrCookieTrace=true}
                  ['set','delete'].forEach(fn=>{try{const orig=cs[fn]&&cs[fn].bind(cs);if(!orig)return;cs[fn]=function(){
                    const a=arguments,o=(a[0]&&typeof a[0]==='object')?a[0]:null,name=String(o?.name??a[0]??''),value=fn==='set'?String(o?.value??a[1]??''):'';
                    let before='';try{before=document.cookie||''}catch(e){};const existed=name?hasName(before,name):false;const action=fn==='delete'?'DELETE':(existed?'UPDATE':'CREATE');
                    if(name)emit({source:'cookie-js-trace',time:Date.now(),action:action,mechanism:'CookieStore.'+fn,name:name,value:value,raw:o?JSON.stringify(o):name+(fn==='set'?'='+value:''),page:location.href,stack:stack(),confidence:'EXACT'});
                    return orig(...a);
                  }}catch(e){}});
                }
              }catch(e){}
            })();
        """.trimIndent()
        try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
    }

    private fun ingestJsArtifacts(archive: ResearchArchive) {
        archive.extraArtifacts.keys.filter { it.startsWith("cookie-trace-event/") }.forEach { key ->
            val bytes = archive.extraArtifacts.remove(key) ?: return@forEach
            try {
                val src = JSONObject(bytes.toString(Charsets.UTF_8))
                val name = src.optString("name", "").trim()
                if (name.isBlank()) return@forEach
                val page = src.optString("page", "")
                val time = src.optLong("time", System.currentTimeMillis())
                addTrace(JSONObject()
                    .put("time", time)
                    .put("action", src.optString("action", "SET"))
                    .put("name", name)
                    .put("value", src.optString("value", ""))
                    .put("page", page)
                    .put("mechanism", src.optString("mechanism", "JavaScript"))
                    .put("confidence", "EXACT")
                    .put("origin", "JAVASCRIPT")
                    .put("raw", src.optString("raw", ""))
                    .put("stack", src.optString("stack", "")))
                lastExact[cookieKey(scopeHost(page), name)] = time
            } catch (_: Exception) {}
        }
    }

    private fun ingestNetworkRecord(record: JSONObject) {
        val url = record.optString("finalUrl", record.optString("url", ""))
        val source = record.optString("source", "")
        val endTime = record.optLong("time", System.currentTimeMillis()) + record.optDouble("duration", 0.0).coerceAtLeast(0.0).toLong()

        if ((url.startsWith("http://") || url.startsWith("https://")) && source in setOf("fetch", "xhr", "resource-copy", "resource-timing", "navigation", "navigation-timing", "replay")) {
            val req = JSONObject()
                .put("time", endTime)
                .put("method", record.optString("method", "GET").ifBlank { "GET" })
                .put("url", url)
                .put("status", record.optInt("status", 0))
                .put("source", source)
                .put("requestBody", record.optString("requestBody", ""))
                .put("requestMimeType", record.optString("requestMimeType", ""))
            val headers = record.optJSONObject("requestHeaders") ?: record.optJSONObject("headers")
            if (headers != null) req.put("requestHeaders", JSONObject(headers.toString()))
            if (record.has("_storeId")) req.put("_storeId", record.optLong("_storeId"))
            recentRequests.addLast(req)
            trimRequests(System.currentTimeMillis())
        }

        val setCookies = mutableListOf<String>()
        record.optJSONObject("responseHeaders")?.let { headers ->
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.equals("Set-Cookie", true)) splitSetCookieHeader(headers.opt(key)?.toString().orEmpty()).forEach(setCookies::add)
            }
        }
        record.optString("responseHeadersRaw", "").lines().forEach { line ->
            val p = line.indexOf(':')
            if (p > 0 && line.substring(0, p).trim().equals("Set-Cookie", true)) splitSetCookieHeader(line.substring(p + 1).trim()).forEach(setCookies::add)
        }
        if (setCookies.isEmpty()) return

        setCookies.forEach { raw ->
            val parsed = parseSetCookie(raw) ?: return@forEach
            val host = scopeHost(url, parsed.domain)
            val trace = JSONObject()
                .put("time", endTime)
                .put("action", parsed.action)
                .put("name", parsed.name)
                .put("value", parsed.value)
                .put("page", url)
                .put("domain", parsed.domain)
                .put("path", parsed.path)
                .put("mechanism", "HTTP Set-Cookie")
                .put("confidence", "EXACT")
                .put("origin", "HTTP_RESPONSE")
                .put("method", record.optString("method", "GET"))
                .put("status", record.optInt("status", 0))
                .put("url", url)
                .put("raw", raw)
                .put("requestBody", record.optString("requestBody", ""))
                .put("requestMimeType", record.optString("requestMimeType", ""))
            val headers = record.optJSONObject("requestHeaders") ?: record.optJSONObject("headers")
            if (headers != null) trace.put("requestHeaders", JSONObject(headers.toString()))
            if (record.has("_storeId")) trace.put("_storeId", record.optLong("_storeId"))
            addTrace(trace)
            lastExact[cookieKey(host, parsed.name)] = endTime
        }
    }

    private fun splitSetCookieHeader(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(Regex(",\\s*(?=[!#$%&'*+.^_`|~0-9A-Za-z-]+=)")).map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun parseSetCookie(raw: String): ParsedCookie? {
        val parts = raw.split(';')
        val head = parts.firstOrNull()?.trim().orEmpty()
        val eq = head.indexOf('=')
        if (eq <= 0) return null
        val name = head.substring(0, eq).trim()
        val value = head.substring(eq + 1)
        var domain = ""
        var path = ""
        parts.drop(1).forEach { p0 ->
            val p = p0.trim()
            when {
                p.startsWith("domain=", true) -> domain = p.substringAfter('=').trim().trimStart('.')
                p.startsWith("path=", true) -> path = p.substringAfter('=').trim()
            }
        }
        val lower = raw.lowercase(Locale.US)
        val delete = Regex("max-age\\s*=\\s*0", RegexOption.IGNORE_CASE).containsMatchIn(raw) || lower.contains("01 jan 1970") || lower.contains("01-jan-1970")
        return ParsedCookie(name, value, if (delete) "DELETE" else "SET", domain, path)
    }

    private fun observeCookieSnapshot(page: String, raw: String, now: Long) {
        val host = scopeHost(page)
        if (host.isBlank()) return
        val current = CookieTraceSupport.parseCookieHeader(raw)
        val previous = snapshots[host]
        if (previous == null) {
            snapshots[host] = current.toMutableMap()
            current.forEach { (name, value) ->
                val exactTime = lastExact[cookieKey(host, name)] ?: 0L
                if (now - exactTime > 1800L) addTrace(JSONObject()
                    .put("time", now)
                    .put("action", "OBSERVED")
                    .put("name", name)
                    .put("value", value)
                    .put("page", page)
                    .put("mechanism", "CookieManager initial snapshot")
                    .put("confidence", "UNKNOWN")
                    .put("origin", "PREEXISTING_OR_UNKNOWN"))
            }
            return
        }

        current.forEach { (name, value) ->
            val old = previous[name]
            if (old == null) inferChange(page, host, name, value, "CREATE", now)
            else if (old != value) inferChange(page, host, name, value, "UPDATE", now)
        }
        previous.keys.filter { it !in current }.forEach { name -> inferChange(page, host, name, "", "DELETE", now) }
        snapshots[host] = current.toMutableMap()
    }

    private fun inferChange(page: String, host: String, name: String, value: String, action: String, now: Long) {
        val exactTime = lastExact[cookieKey(host, name)] ?: 0L
        if (now - exactTime in 0..1800L) return
        val request = likelyRequest(now)
        val delta = request?.let { abs(now - it.optLong("time", now)) } ?: Long.MAX_VALUE
        val confidence = when {
            request == null -> "UNKNOWN"
            delta <= 350L -> "MEDIUM"
            delta <= 2000L -> "LOW"
            else -> "UNKNOWN"
        }
        val trace = JSONObject()
            .put("time", now)
            .put("action", action)
            .put("name", name)
            .put("value", value)
            .put("page", page)
            .put("mechanism", "CookieManager diff")
            .put("confidence", confidence)
            .put("origin", if (request == null) "UNKNOWN" else "LIKELY_HTTP_RESPONSE")
        if (request != null) copyRequestData(request, trace, delta)
        addTrace(trace)
    }

    private fun copyRequestData(from: JSONObject, to: JSONObject, delta: Long? = null) {
        to.put("method", from.optString("method", "GET"))
            .put("status", from.optInt("status", 0))
            .put("url", from.optString("url", ""))
            .put("requestSource", from.optString("source", ""))
            .put("requestBody", from.optString("requestBody", ""))
            .put("requestMimeType", from.optString("requestMimeType", ""))
        from.optJSONObject("requestHeaders")?.let { to.put("requestHeaders", JSONObject(it.toString())) }
        if (from.has("_storeId")) to.put("_storeId", from.optLong("_storeId"))
        if (delta != null) to.put("deltaMs", delta)
    }

    private fun likelyRequest(now: Long): JSONObject? {
        trimRequests(now)
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        val it = recentRequests.descendingIterator()
        while (it.hasNext()) {
            val candidate = it.next()
            val delta = abs(now - candidate.optLong("time", now))
            if (delta > 2000L) continue
            val penalty = when (candidate.optString("source", "")) {
                "fetch", "xhr", "resource-copy", "replay" -> 0L
                "resource-timing", "navigation-timing" -> 80L
                else -> 160L
            }
            val score = delta + penalty
            if (score < bestScore) { best = candidate; bestScore = score }
        }
        return best
    }

    private fun trimRequests(now: Long) {
        while (recentRequests.isNotEmpty() && now - recentRequests.first.optLong("time", now) > 10000L) recentRequests.removeFirst()
        while (recentRequests.size > 200) recentRequests.removeFirst()
    }

    private fun addTrace(event: JSONObject) {
        val time = event.optLong("time", System.currentTimeMillis())
        val fingerprint = buildString {
            append(event.optString("origin", "")).append('|')
            append(event.optString("action", "")).append('|')
            append(event.optString("name", "")).append('|')
            append(event.optString("value", "")).append('|')
            append(event.optString("url", event.optString("page", ""))).append('|')
            append(time / 100L)
        }
        if (!fingerprints.add(fingerprint)) return
        events.add(event)
        if (events.size > 2000) events.removeAt(0)
        if (fingerprints.size > 5000) fingerprints.clear()
    }

    private fun cookieKey(host: String, name: String) = "${host.lowercase(Locale.US)}|$name"

    private fun exportJson(page: String): JSONObject {
        val arr = JSONArray()
        events.forEach { arr.put(JSONObject(it.toString())) }
        return JSONObject()
            .put("format", "evrasia-cookie-trace-v2")
            .put("generatedAt", System.currentTimeMillis())
            .put("page", page)
            .put("events", arr)
    }
}
