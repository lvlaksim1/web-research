package ru.evrasia.research

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Locale

internal class FrameCaptureController(
    private val web: WebView,
    private val windowId: String,
    private val mainFrameId: String,
    private val archive: ResearchArchive,
    private val record: (JSONObject) -> Unit,
    private val onChanged: () -> Unit
) {
    companion object {
        private const val PAGE_BRIDGE = "WebResearchPageBridge"
        private const val INSPECTOR_BRIDGE = "WebResearchInspectorBridge"
        private const val INSPECTOR_WORLD = "web-research-inspector-v48"
        private const val MAX_FRAME_MESSAGE_CHARS = 1_000_000
    }

    private val frameIds = linkedMapOf<String, String>()
    private var frameSequence = 0
    private var installed = false
    var mode: String = "uninitialized"
        private set

    fun install() {
        if (installed) return
        installed = true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
            mode = "legacy"
            recordMode(false)
            return
        }
        try {
            val rules = setOf("*")
            val pageWorld = WebViewCompat.getExecutionWorld(web, JavaScriptExecutionWorld.PAGE_WORLD_NAME)
            val inspectorWorld = WebViewCompat.getExecutionWorld(web, INSPECTOR_WORLD)
            WebViewCompat.addWebMessageListener(web, PAGE_BRIDGE, rules, pageWorld, listener("page"))
            WebViewCompat.addWebMessageListener(web, INSPECTOR_BRIDGE, rules, inspectorWorld, listener("isolated"))
            WebViewCompat.addJavaScriptOnEvent(web, pageWorldScript(), WebViewCompat.INJECTION_EVENT_DOCUMENT_START, rules, pageWorld)
            WebViewCompat.addJavaScriptOnEvent(web, inspectorWorldScript(), WebViewCompat.INJECTION_EVENT_DOCUMENT_START, rules, inspectorWorld)
            mode = "modern"
            recordMode(true)
        } catch (e: Exception) {
            mode = "legacy"
            record(
                CaptureWarning.create(
                    code = "frame_capture_install_failed",
                    message = "Modern frame capture could not be installed; v47 legacy frame capture remains active.",
                    stage = "frame-capture",
                    url = web.url.orEmpty(),
                    error = e.toString(),
                    details = JSONObject().put("windowId", windowId).put("feature", WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
                ).put("windowId", windowId).put("frameId", mainFrameId)
            )
            recordMode(false)
        }
    }

    fun shutdown() = Unit

    private fun listener(world: String) = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy
        ) {
            val data = message.data ?: return
            if (data.length > MAX_FRAME_MESSAGE_CHARS) {
                record(
                    CaptureWarning.create(
                        code = "frame_message_oversize",
                        message = "A frame message exceeded the configured bridge limit and was omitted.",
                        stage = "frame-capture",
                        url = view.url.orEmpty(),
                        details = JSONObject()
                            .put("windowId", windowId)
                            .put("world", world)
                            .put("sourceOrigin", sourceOrigin.toString())
                            .put("payloadChars", data.length)
                            .put("limitChars", MAX_FRAME_MESSAGE_CHARS)
                    ).put("windowId", windowId)
                )
                return
            }
            val payload = try {
                JSONObject(data)
            } catch (e: Exception) {
                record(
                    CaptureWarning.create(
                        code = "frame_message_parse_failed",
                        message = "A frame message could not be parsed.",
                        stage = "frame-capture",
                        url = view.url.orEmpty(),
                        error = e.toString(),
                        details = JSONObject()
                            .put("windowId", windowId)
                            .put("world", world)
                            .put("sourceOrigin", sourceOrigin.toString())
                            .put("payloadChars", data.length)
                    ).put("windowId", windowId)
                )
                return
            }
            handle(payload, sourceOrigin, isMainFrame, world)
        }
    }

    private fun handle(payload: JSONObject, sourceOrigin: Uri, isMainFrame: Boolean, world: String) {
        val frameKey = payload.optString("frameKey", "").ifBlank {
            sourceOrigin.toString() + "|" + payload.optString("url", "") + "|" + payload.optLong("timeOrigin", 0L)
        }
        val frameId = if (isMainFrame) mainFrameId else frameIdFor(sourceOrigin.toString(), frameKey)
        val kind = payload.optString("kind", "frame-event")
        if (isMainFrame && kind !in setOf("frame-ready", "inspector-ready")) return

        val event = JSONObject(payload.toString())
            .put("windowId", windowId)
            .put("frameId", frameId)
            .put("sourceOrigin", sourceOrigin.toString())
            .put("isMainFrame", isMainFrame)
            .put("executionWorld", world)
            .put("frameCaptureMode", "modern")
            .put("frameIdentityMethod", "sourceOrigin+url+performance.timeOrigin+window.name+topFlag")

        when (kind) {
            "frame-ready", "inspector-ready" -> event.put("source", "frame-lifecycle")
            "user-action" -> event.put("source", "user-action")
            "fetch" -> event.put("source", "fetch")
            "xhr" -> event.put("source", "xhr")
            "history" -> event.put("source", "history")
            "mutation" -> event.put("source", "dom-mutation")
            "snapshot" -> {
                event.put("source", "frame-snapshot")
                val state = event.optJSONObject("state")
                if (state != null) {
                    val artifact = "frames/$windowId/$frameId/snapshot-" + System.currentTimeMillis() + ".json"
                    val stored = JSONObject(state.toString())
                        .put("windowId", windowId)
                        .put("frameId", frameId)
                        .put("sourceOrigin", sourceOrigin.toString())
                        .put("isMainFrame", isMainFrame)
                        .put("executionWorld", world)
                    archive.putArtifact(artifact, stored.toString(2).toByteArray(Charsets.UTF_8))
                    event.remove("state")
                    event.put("stateArtifact", artifact)
                    onChanged()
                }
            }
            else -> event.put("source", "frame-event")
        }
        event.remove("kind")
        record(event)
    }

    private fun frameIdFor(origin: String, frameKey: String): String {
        val key = origin + "|" + frameKey
        return frameIds.getOrPut(key) {
            frameSequence++
            "frame-" + windowId.removePrefix("window-") + "-" + String.format(Locale.US, "%04d", frameSequence)
        }
    }

    private fun recordMode(modern: Boolean) {
        record(
            JSONObject()
                .put("source", "frame-capture-mode")
                .put("time", System.currentTimeMillis())
                .put("windowId", windowId)
                .put("frameId", mainFrameId)
                .put("mode", if (modern) "modern" else "legacy")
                .put("feature", WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
                .put("featureSupported", modern)
        )
    }

    private fun pageWorldScript(): String = """
        (function(){
          try {
            if (window.__WR_FRAME_PAGE_V48) return;
            window.__WR_FRAME_PAGE_V48 = true;
            var bridge = window.$PAGE_BRIDGE;
            if (!bridge || typeof bridge.postMessage !== 'function') return;
            var isTop = false;
            try { isTop = window.top === window; } catch (_) {}
            var timeOrigin = 0;
            try { timeOrigin = Number(performance.timeOrigin || 0); } catch (_) {}
            var frameKey = String(location.href) + '|' + String(timeOrigin) + '|' + String(window.name || '') + '|' + (isTop ? 'top' : 'child');
            var seq = 0;
            var active = null;
            function target(e) {
              if (!e) return {};
              var o = {tag:String(e.tagName||'').toLowerCase()};
              try { o.id=String(e.id||''); o.name=String(e.name||''); o.type=String(e.type||''); o.role=String(e.getAttribute&&e.getAttribute('role')||''); o.text=String(e.innerText||e.textContent||'').trim().slice(0,300); } catch (_) {}
              return o;
            }
            function post(kind, extra) {
              try {
                var o = extra || {};
                o.kind = kind;
                o.time = Date.now();
                o.url = String(location.href);
                o.frameKey = frameKey;
                o.timeOrigin = timeOrigin;
                o.isTop = isTop;
                bridge.postMessage(JSON.stringify(o));
              } catch (_) {}
            }
            post('frame-ready', {title:String(document.title||'')});
            if (isTop) return;

            ['click','change','submit'].forEach(function(type){
              addEventListener(type, function(e){
                var token='framectx-'+frameKey+'-'+String(++seq);
                active={token:token,time:Date.now()};
                post('user-action',{action:type,target:target(e.target),browserActionToken:token,eventPhase:e.eventPhase||0});
                setTimeout(function(){ if(active && active.token===token) active=null; },0);
              }, true);
            });

            var inputTimer = 0;
            addEventListener('beforeinput', function(e){
              var token='frameinput-'+frameKey+'-'+String(++seq);
              active={token:token,time:Date.now()};
              post('user-action',{action:'input',phase:'before',inputType:String(e.inputType||''),target:target(e.target),browserActionToken:token});
            }, true);
            addEventListener('input', function(e){
              if (inputTimer) clearTimeout(inputTimer);
              var token=active && active.token || '';
              var info=target(e.target);
              var value='';
              try { if (e.target && 'value' in e.target) value=String(e.target.value||'').slice(0,4096); } catch (_) {}
              inputTimer=setTimeout(function(){
                post('user-action',{action:'input',phase:'after',target:info,value:value,browserActionToken:token});
                active=null;
              },450);
            }, true);

            var originalFetch = window.fetch;
            if (typeof originalFetch === 'function') {
              window.fetch = async function(input, init) {
                var started = Date.now();
                var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
                var url = '';
                try { url = String((input && input.url) || input || ''); } catch (_) {}
                var token = active && active.token || '';
                var stack=''; try { stack=String(new Error().stack||''); } catch (_) {}
                try {
                  var response = await originalFetch.apply(this, arguments);
                  var body = '';
                  try {
                    var clone = response.clone();
                    var ct = String(clone.headers.get('content-type')||'');
                    if (/json|text|javascript|xml|html|css|x-www-form-urlencoded/i.test(ct)) body=(await clone.text()).slice(0,500000);
                  } catch (_) {}
                  var headers={}; try { response.headers.forEach(function(v,k){headers[k]=v;}); } catch (_) {}
                  post('fetch',{method:method,url:url,status:response.status,duration:Date.now()-started,responseHeaders:headers,responseBody:body,initiatorStack:stack,browserActionToken:token});
                  return response;
                } catch (e) {
                  post('fetch',{method:method,url:url,status:0,duration:Date.now()-started,error:String(e),initiatorStack:stack,browserActionToken:token});
                  throw e;
                }
              };
            }

            var xo = XMLHttpRequest.prototype.open;
            var xs = XMLHttpRequest.prototype.send;
            var xm = new WeakMap();
            XMLHttpRequest.prototype.open = function(method,url){
              try { xm.set(this,{method:String(method||'GET').toUpperCase(),url:String(url||'')}); } catch (_) {}
              return xo.apply(this,arguments);
            };
            XMLHttpRequest.prototype.send = function(body){
              var self=this, meta=xm.get(this)||{method:'GET',url:''}, started=Date.now(), token=active&&active.token||'';
              var stack=''; try { stack=String(new Error().stack||''); } catch (_) {}
              function done(){
                try {
                  var text=''; try { if (typeof self.responseText==='string') text=self.responseText.slice(0,500000); } catch (_) {}
                  post('xhr',{method:meta.method,url:meta.url,status:self.status||0,duration:Date.now()-started,responseBody:text,responseHeadersRaw:String(self.getAllResponseHeaders&&self.getAllResponseHeaders()||''),initiatorStack:stack,browserActionToken:token});
                } catch (_) {}
              }
              try { this.addEventListener('loadend',done,{once:true}); } catch (_) {}
              return xs.apply(this,arguments);
            };

            ['pushState','replaceState'].forEach(function(name){
              try {
                var original=history[name];
                history[name]=function(){
                  var result=original.apply(this,arguments);
                  post('history',{action:name,page:String(location.href)});
                  return result;
                };
              } catch (_) {}
            });
            addEventListener('popstate',function(){post('history',{action:'popstate',page:String(location.href)});},true);
            addEventListener('hashchange',function(){post('history',{action:'hashchange',page:String(location.href)});},true);
          } catch (_) {}
        })();
    """.trimIndent()

    private fun inspectorWorldScript(): String = """
        (function(){
          try {
            if (window.__WR_FRAME_INSPECTOR_V48) return;
            window.__WR_FRAME_INSPECTOR_V48 = true;
            var bridge = window.$INSPECTOR_BRIDGE;
            if (!bridge || typeof bridge.postMessage !== 'function') return;
            var isTop=false; try { isTop=window.top===window; } catch (_) {}
            var timeOrigin=0; try { timeOrigin=Number(performance.timeOrigin||0); } catch (_) {}
            var frameKey=String(location.href)+'|'+String(timeOrigin)+'|'+String(window.name||'')+'|'+(isTop?'top':'child');
            var ids=new WeakMap(), idSeq=0, snapshots=0, snapshotTimer=0;
            function key(e){ if(!e)return''; var k=ids.get(e); if(k)return k; k='node-'+String(++idSeq).padStart(6,'0'); ids.set(e,k); return k; }
            function runtime(e){
              var o={};
              try {
                if('value'in e)o.value=String(e.value||'').slice(0,4096);
                if('checked'in e)o.checked=!!e.checked;
                if('selectedIndex'in e)o.selectedIndex=Number(e.selectedIndex);
                if('disabled'in e)o.disabled=!!e.disabled;
                if('readOnly'in e)o.readOnly=!!e.readOnly;
                o.focused=document.activeElement===e;
              } catch (_) {}
              return o;
            }
            function post(kind,extra){
              try {
                var o=extra||{}; o.kind=kind; o.time=Date.now(); o.url=String(location.href); o.frameKey=frameKey; o.timeOrigin=timeOrigin; o.isTop=isTop;
                bridge.postMessage(JSON.stringify(o));
              } catch (_) {}
            }
            function state(reason){
              var all=[]; try { all=Array.from(document.querySelectorAll('a,button,input,select,textarea,form,[role],[contenteditable]')); } catch (_) {}
              var elements=all.slice(0,300).map(function(e){
                var r={}; try { var b=e.getBoundingClientRect(); r={x:b.x,y:b.y,width:b.width,height:b.height}; } catch (_) {}
                return {key:key(e),tag:String(e.tagName||'').toLowerCase(),id:String(e.id||''),name:String(e.name||''),text:String(e.innerText||e.textContent||'').trim().slice(0,300),runtime:runtime(e),rect:r};
              });
              var selection={}; try { var s=getSelection(); selection={text:String(s&&s.toString()||'').slice(0,4096),anchorOffset:s?s.anchorOffset:0,focusOffset:s?s.focusOffset:0}; } catch (_) {}
              return {reason:reason,title:String(document.title||''),readyState:String(document.readyState||''),html:String(document.documentElement&&document.documentElement.outerHTML||'').slice(0,250000),dom:{total:all.length,captured:elements.length,truncated:all.length>elements.length,elements:elements},viewport:{scrollX:scrollX,scrollY:scrollY,innerWidth:innerWidth,innerHeight:innerHeight,devicePixelRatio:devicePixelRatio},focus:{key:key(document.activeElement)},selection:selection};
            }
            function snapshot(reason){
              if (isTop || snapshots>=20) return;
              snapshots++;
              post('snapshot',{state:state(reason),snapshotIndex:snapshots});
            }
            function start(){
              post('inspector-ready',{title:String(document.title||'')});
              if (isTop) return;
              snapshot('document-ready');
              try {
                new MutationObserver(function(list){
                  var added=0,removed=0,attributes=0;
                  list.forEach(function(m){ if(m.type==='childList'){added+=m.addedNodes.length;removed+=m.removedNodes.length;} if(m.type==='attributes')attributes++; });
                  post('mutation',{added:added,removed:removed,attributes:attributes,count:list.length});
                  if(snapshotTimer)clearTimeout(snapshotTimer);
                  snapshotTimer=setTimeout(function(){snapshot('after-mutation');},500);
                }).observe(document.documentElement||document,{subtree:true,childList:true,attributes:true});
              } catch (_) {}
            }
            if(document.readyState==='loading') addEventListener('DOMContentLoaded',start,{once:true}); else start();
          } catch (_) {}
        })();
    """.trimIndent()
}
