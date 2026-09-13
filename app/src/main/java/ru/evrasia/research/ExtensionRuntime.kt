package ru.evrasia.research

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

internal class ExtensionRuntime(private val context: Context, private val web: WebView) {
    private val prefs = context.getSharedPreferences("extension-storage", Context.MODE_PRIVATE)

    fun installBridge() {
        web.addJavascriptInterface(Bridge(), "WebResearchExtensionBridge")
    }

    fun bootstrap() {
        val js = """
            (function(){
              if(window.chrome && window.chrome.__webResearch) return;
              var listeners=[];
              var storage={
                get:function(keys,cb){var raw=WebResearchExtensionBridge.storageGet();var data={};try{data=JSON.parse(raw||'{}')}catch(e){};if(cb)cb(data);return Promise.resolve(data);},
                set:function(items,cb){WebResearchExtensionBridge.storageSet(JSON.stringify(items||{}));if(cb)cb();return Promise.resolve();}
              };
              window.chrome=window.chrome||{};
              window.chrome.__webResearch=true;
              window.chrome.storage=window.chrome.storage||{};
              window.chrome.storage.local=storage;
              window.chrome.runtime=window.chrome.runtime||{};
              window.chrome.runtime.sendMessage=function(message,cb){var result=WebResearchExtensionBridge.sendMessage(JSON.stringify(message));if(cb)cb(result);return Promise.resolve(result);};
              window.chrome.runtime.onMessage={addListener:function(fn){listeners.push(fn)}};
              window.__WR_EXTENSION_DISPATCH=function(message){listeners.forEach(function(fn){try{fn(message,{},function(){})}catch(e){}})};
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private inner class Bridge {
        @JavascriptInterface fun storageGet(): String = prefs.getString("local", "{}") ?: "{}"

        @JavascriptInterface fun storageSet(json: String) {
            val incoming = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
            val current = runCatching { JSONObject(storageGet()) }.getOrElse { JSONObject() }
            incoming.keys().forEach { key -> current.put(key, incoming.opt(key)) }
            prefs.edit().putString("local", current.toString()).apply()
        }

        @JavascriptInterface fun sendMessage(json: String): String = json
    }
}
