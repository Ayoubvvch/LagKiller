package com.acmods.lagkiller

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var monitor: SystemMonitor
    private lateinit var controller: AppActionController
    private var lastBackPressTime = 0L

    private val iconCache = object : LruCache<String, ByteArray>(120) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AMOLED Void Dark Bars (#050811)
        window.statusBarColor = Color.parseColor("#050811")
        window.navigationBarColor = Color.parseColor("#050811")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        monitor = SystemMonitor(this)
        controller = AppActionController(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#050811"))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                offscreenPreRaster = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                displayZoomControls = false
            }
        }
        setContentView(webView)

        // Add Bridge
        webView.addJavascriptInterface(LagKillerBridge(this, webView, monitor, controller), "LagKillerBridge")

        // Asset Loader
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // Intercept app-icon requests for high-performance direct icon rendering
                if (url.contains("/app-icon/")) {
                    val pkg = url.substringAfter("/app-icon/").substringBefore(".png")
                    val iconBytes = getAppIconBytes(pkg)
                    if (iconBytes != null) {
                        return WebResourceResponse(
                            "image/png",
                            "utf-8",
                            ByteArrayInputStream(iconBytes)
                        )
                    }
                }

                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("LagKiller Root")
                    .setMessage(message ?: "")
                    .setPositiveButton("موافق") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("LagKiller Root")
                    .setMessage(message ?: "")
                    .setPositiveButton("تأكيد") { _, _ -> result?.confirm() }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        // Double Back press to exit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "اضغط رجوع مرة أخرى للخروج من LagKiller", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Warm up cache and root check on startup in background
        CoroutineScope(Dispatchers.IO).launch {
            monitor.ensureAppCache()
            RootShell.isRootAvailable()
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun getAppIconBytes(packageName: String): ByteArray? {
        iconCache.get(packageName)?.let { return it }

        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
            val bytes = stream.toByteArray()
            iconCache.put(packageName, bytes)
            bytes
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 84
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 84
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    class LagKillerBridge(
        private val activity: MainActivity,
        private val webView: WebView,
        private val monitor: SystemMonitor,
        private val controller: AppActionController
    ) {

        @JavascriptInterface
        fun requestScanAsync() {
            CoroutineScope(Dispatchers.IO).launch {
                val statsJson = monitor.getSystemStats()
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("window.onSystemStatsReceived($statsJson);", null)
                }
            }
        }

        @JavascriptInterface
        fun getSystemStats(): String {
            return monitor.getSystemStats()
        }

        @JavascriptInterface
        fun forceStop(pkg: String): String {
            CoroutineScope(Dispatchers.IO).launch {
                controller.forceStop(pkg)
            }
            return "{\"success\":true}"
        }

        @JavascriptInterface
        fun suspendApp(pkg: String): String {
            CoroutineScope(Dispatchers.IO).launch {
                controller.suspendApp(pkg)
            }
            return "{\"success\":true}"
        }

        @JavascriptInterface
        fun unsuspendApp(pkg: String): String {
            CoroutineScope(Dispatchers.IO).launch {
                controller.unsuspendApp(pkg)
            }
            return "{\"success\":true}"
        }

        @JavascriptInterface
        fun freezeApp(pkg: String): String {
            CoroutineScope(Dispatchers.IO).launch {
                controller.freezeApp(pkg)
            }
            return "{\"success\":true}"
        }

        @JavascriptInterface
        fun unfreezeApp(pkg: String): String {
            CoroutineScope(Dispatchers.IO).launch {
                controller.unfreezeApp(pkg)
            }
            return "{\"success\":true}"
        }

        @JavascriptInterface
        fun quickBoost(): String {
            return controller.quickBoost()
        }

        @JavascriptInterface
        fun isRootGranted(): Boolean {
            return RootShell.isRootAvailable()
        }
    }
}
