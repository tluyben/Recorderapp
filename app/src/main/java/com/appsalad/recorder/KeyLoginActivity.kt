package com.appsalad.recorder

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appsalad.recorder.net.InferMux
import com.appsalad.recorder.ui.RecorderTheme
import com.appsalad.recorder.ui.ThemeMode
import com.appsalad.recorder.ui.isDark
import org.json.JSONObject
import java.time.Instant

/**
 * "Sign in to InferMux": hosts InferMux's own login widget — the same one websites embed —
 * in a WebView. Signing in (through AuthLock) mints a scoped router key in the user's
 * InferMux account; the widget hands it to this page as a `modelrouter:key` event, which
 * the bridge below saves. The password/code never passes through the app.
 */
class KeyLoginActivity : ComponentActivity() {
    private val app get() = application as RecorderApp
    private var popup: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by app.settings.prefs.collectAsStateWithLifecycle()
            val dark = (ThemeMode.entries.firstOrNull { it.name.equals(prefs.theme, true) } ?: ThemeMode.SYSTEM).isDark()
            RecorderTheme(darkTheme = dark) { Screen(dark) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(dark: Boolean) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Sign in to InferMux") },
                navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Text(
                    "Signing in creates a key in your own InferMux account that only this app uses. It lasts " +
                        "${KEY_DAYS} days; AI use is charged to your InferMux credits. Use the email code option — " +
                        "Google sign-in doesn't work inside apps.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AndroidView(factory = { ctx -> makeWebView(ctx, dark).also(::loadWidget) }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(ctx: android.content.Context, dark: Boolean) = WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(if (dark) 0xFF101418.toInt() else Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // the widget is an infermux.net iframe inside our page: its session cookie is third-party
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = WebViewClient()
        webChromeClient = Chrome()
    }

    private fun loadWidget(web: WebView) {
        web.addJavascriptInterface(Bridge(), "RecorderBridge")
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;background:transparent}#login{padding:4px 8px}</style></head>
            <body><div id="login"></div>
            <script src="${InferMux.SITE}/widget/${InferMux.WIDGET_ID}.js" data-target="#login" data-height="640"></script>
            <script>
              window.addEventListener('modelrouter:key', function (e) {
                RecorderBridge.onKey(JSON.stringify(e.detail || {}));
              });
            </script></body></html>
        """.trimIndent()
        // the widget only talks to the origin it was registered for
        web.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null)
    }

    /** The AuthLock step opens a popup (window.open); show it in a full-screen dialog. */
    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = makeWebView(view.context, false).apply {
                webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) { popup?.dismiss(); popup = null }
                }
            }
            popup?.dismiss()
            popup = Dialog(this@KeyLoginActivity, android.R.style.Theme_Material_Light_NoActionBar).apply {
                setContentView(child)
                setOnDismissListener { child.destroy() }
                show()
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onKey(json: String) {
            val d = runCatching { JSONObject(json) }.getOrNull() ?: return
            val key = d.optString("key")
            if (!key.startsWith("sk_")) return
            val expires = runCatching { Instant.parse(d.optString("expiresAt")).toEpochMilli() }.getOrDefault(0L)
            val email = d.optString("email").takeIf { it != "null" } ?: ""
            runOnUiThread {
                app.settings.update { it.copy(provider = "infermux", infermuxKey = key, infermuxExpires = expires, infermuxEmail = email) }
                app.retryMissingKey()
                Toast.makeText(this@KeyLoginActivity, "Signed in to InferMux" + if (email.isNotBlank()) " as $email" else "", Toast.LENGTH_LONG).show()
                popup?.dismiss()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onDestroy() {
        popup?.dismiss()
        super.onDestroy()
    }

    companion object {
        /** The page origin the Recorder login widget is registered for in InferMux. */
        const val ORIGIN = "https://recorder.appsalad.com"
        const val KEY_DAYS = 30
    }
}
