package com.orexii.aimarkdownreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.orexii.aimarkdownreader.reader.Documents
import com.orexii.aimarkdownreader.reader.OpenDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the reader page asks for. Delivered on the main thread. */
interface PageListener {
    fun onReady()
    fun onNarration(json: String, selection: Boolean)
    fun onReadFrom(line: Int, character: Int)
    fun onCommand(action: String)
    fun onLink(href: String)
    fun onState(sentence: String, word: String, following: Boolean)
}

/**
 * Shows the reader page (web/reader.html with the extension's preview.js) for the open document. The page is
 * served from the app's assets at a secure origin; images next to the document come from the document's folder.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(context: Context, private val listener: PageListener) : WebView(context) {
    var document: OpenDocument? = null
    private var ready = false
    private val pending = ArrayList<String>()

    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/document/") { path -> documentFile(path) }
        .build()

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addJavascriptInterface(Bridge(), "AndroidBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The page never navigates; links are handled by preview.js and the app.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        loadUrl(PAGE)
    }

    private fun documentFile(path: String): WebResourceResponse? {
        val document = document ?: return null
        val resolved = Documents.resolve(context, document, path) ?: return null
        val stream = Documents.open(context, resolved) ?: return null
        val type = when (resolved.name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> return null
        }
        return WebResourceResponse(type, null, stream)
    }

    /** Runs a call on the page's AMR object once the page is ready. */
    fun call(script: String) {
        if (ready) evaluateJavascript(script, null) else pending += script
    }

    fun open(document: OpenDocument, options: JsonObject) {
        this.document = document
        call("AMR.open(${JsonPrimitive(document.text)}, $options); AMR.top();")
    }

    fun rebuild(options: JsonObject) = call("AMR.rebuild($options)")
    fun position(index: Int, word: Int) = call("AMR.position($index, $word)")
    fun clear() = call("AMR.clear()")
    fun configure(follow: Boolean, words: Boolean) = call("AMR.settings($follow, $words)")
    fun reveal() = call("AMR.reveal()")
    fun theme(dark: Boolean) = call("AMR.theme($dark)")

    private inner class Bridge {
        @JavascriptInterface
        fun post(message: String) {
            val json = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
            post { handle(json) }
        }

        @JavascriptInterface
        fun narration(json: String, selection: Boolean) {
            post { listener.onNarration(json, selection) }
        }
    }

    private fun handle(message: JsonObject) {
        when (message["type"]?.jsonPrimitive?.content) {
            "ready" -> {
                ready = true
                pending.forEach { evaluateJavascript(it, null) }
                pending.clear()
                listener.onReady()
            }
            "readFrom" -> listener.onReadFrom(message.getValue("line").jsonPrimitive.int, message.getValue("character").jsonPrimitive.int)
            "command" -> message["action"]?.jsonPrimitive?.content?.let(listener::onCommand)
            "openLink" -> message["href"]?.jsonPrimitive?.content?.let(listener::onLink)
            "state" -> listener.onState(
                message["sentence"]?.jsonPrimitive?.content ?: "",
                message["word"]?.jsonPrimitive?.content ?: "",
                message["following"]?.jsonPrimitive?.boolean ?: true,
            )
        }
    }

    // "Read aloud from here" and "Read selection aloud" in the text selection menu.
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(SelectionCallback(callback), type)

    private inner class SelectionCallback(private val wrapped: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = wrapped.onCreateActionMode(mode, menu)
            menu.add(Menu.NONE, MENU_READ_FROM, 0, "Read aloud from here")
            menu.add(Menu.NONE, MENU_READ_SELECTION, 1, "Read selection aloud")
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = wrapped.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                MENU_READ_FROM -> evaluateJavascript("AMR.selectionStart()") { result ->
                    val at = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject }.getOrNull()
                    if (at != null) listener.onReadFrom(at.getValue("line").jsonPrimitive.int, at.getValue("character").jsonPrimitive.int)
                }
                MENU_READ_SELECTION -> evaluateJavascript("AMR.readSelection()", null)
                else -> return wrapped.onActionItemClicked(mode, item)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            if (wrapped is ActionMode.Callback2) wrapped.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        const val PAGE = "https://appassets.androidplatform.net/assets/reader/reader.html"
        private const val MENU_READ_FROM = 0x414d01
        private const val MENU_READ_SELECTION = 0x414d02

        /** Opens web and mail links outside the app. */
        fun openExternal(context: Context, href: String) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
