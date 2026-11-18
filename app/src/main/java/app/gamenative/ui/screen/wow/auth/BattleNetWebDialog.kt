package app.gamenative.ui.screen.wow.auth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows Battle.net's own page and reads only its final navigation address.
 * The ST redirect contract follows Scuffle's MIT-licensed browser login implementation.
 *
 * Ported from upstream's BattleNetWebDialog (jaredgei/wow-forever-android v2.4.5, Demineo04, GPL-3.0) with its WebView rules unchanged:
 * the host allow-list ([BrowserAuthUrl]), the http://localhost:0/?ST= callback captured and never requested, TLS errors refused,
 * no file/content access, no form data saved, no popups. DS FOREVER: a plain View (not a Compose Dialog), so the same page goes on the
 * Thor's BOTTOM screen (DsForeverScreen.browserPage: its window, its touch and keyboard) or, without a second screen, in the
 * fallback dialog on the game's screen ([BattleNetBrowserLoginHost]). The bar shows the site's origin only (never a path or query).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // Battle.net's sign-in page requires JavaScript.
internal class BattleNetSignInPage(
    context: Context,
    url: String,
    private val onTicket: (ByteArray) -> Unit,
    private val onCancel: () -> Unit,
    private val onError: (String) -> Unit,
) : FrameLayout(context) {
    private val completed = AtomicBoolean(false)
    private val origin = TextView(context)
    private var webView: WebView? = null

    init {
        setBackgroundColor(Color.BLACK)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(bar(context, origin, CANCEL) { cancel() })
        origin.text = BrowserAuthUrl.displayOrigin(url) ?: "Battle.net"
        if (!BrowserAuthUrl.isInitialUrl(url)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (completed.compareAndSet(false, true)) onError("Battle.net returned an unsupported sign-in address.")
            }
        } else {
            val view = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    @Suppress("DEPRECATION")
                    saveFormData = false
                }
                isFocusable = true
                isFocusableInTouchMode = true
                webViewClient = Client()
                loadUrl(url)
            }
            webView = view
            column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private inner class Client : WebViewClient() {
        private fun fail(view: WebView, message: String) {
            view.stopLoading()
            if (completed.compareAndSet(false, true)) onError(message)
        }

        private fun navigate(view: WebView, address: String): Boolean {
            if (completed.get()) return true
            val ticket = BrowserAuthUrl.ticket(address)
            if (ticket != null) {
                view.stopLoading()
                if (completed.compareAndSet(false, true)) onTicket(ticket) else ticket.fill(0)
                return true
            }
            if (!BrowserAuthUrl.isNavigationUrl(address)) {
                fail(view, "Sign-in tried to open an unsupported address. Cancel and try again.")
                return true
            }
            origin.text = BrowserAuthUrl.displayOrigin(address) ?: "Battle.net"
            return false
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return !request.url.scheme.equals("https", ignoreCase = true)
            return navigate(view, request.url.toString())
        }

        override fun onPageStarted(view: WebView, address: String, favicon: Bitmap?) {
            // Covers navigations for which WebView does not invoke shouldOverrideUrlLoading (e.g. POST).
            navigate(view, address)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame && !BrowserAuthUrl.isNavigationUrl(request.url.toString())) {
                // No network request is made to the localhost callback or another unapproved origin.
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            fail(view, "The sign-in page's secure connection could not be verified.")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !completed.get()) fail(view, "The sign-in page could not load. Check your connection and try again.")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && !completed.get()) fail(view, "Battle.net could not complete this sign-in page. Try again later.")
        }
    }

    /** the player's Cancel (once) */
    fun cancel() {
        if (completed.compareAndSet(false, true)) {
            webView?.stopLoading()
            onCancel()
        }
    }

    /** the page is gone for good: the WebView and everything it loaded */
    fun destroy() {
        completed.set(true)
        webView?.let { v ->
            v.stopLoading()
            v.webViewClient = WebViewClient()
            (v.parent as? ViewGroup)?.removeView(v)
            v.removeAllViews()
            v.destroy()
        }
        webView = null
    }

    companion object {
        const val CANCEL = "Cancel"
        const val CLOSE = "Close"

        /** the page's top bar: [label] (the origin, or the title), one button */
        internal fun bar(context: Context, label: TextView, button: String, onPress: () -> Unit): View {
            val d = context.resources.displayMetrics.density
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(24, 24, 28))
                setPadding((16 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                label.setTextColor(Color.WHITE)
                label.textSize = 16f
                label.isSingleLine = true
                addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = com.jrees.wowcompanion.Tr.t(button)
                    isAllCaps = false
                    // touch only: never takes the focus from the page's fields
                    isFocusable = false
                    setOnClickListener { onPress() }
                })
            }
        }
    }
}

/** Browser login's status on the same screen as its page: waiting, or what stopped it, and one button (Cancel / Close) */
@SuppressLint("ViewConstructor")
internal class BattleNetStatusPage(context: Context, text: String, failed: Boolean, onPress: () -> Unit) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.BLACK)
        val tr = { s: String -> com.jrees.wowcompanion.Tr.t(s) }
        val d = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(BattleNetSignInPage.bar(context, TextView(context).apply { this.text = tr(BattleNetBrowserLogin.TITLE) },
            if (failed) BattleNetSignInPage.CLOSE else BattleNetSignInPage.CANCEL, onPress))
        column.addView(TextView(context).apply {
            this.text = tr(text)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
