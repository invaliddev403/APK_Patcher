package dev.apkpatcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class SourceActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FILE = "file"
    }

    private lateinit var webView: WebView
    private lateinit var addressField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 252))
        }
        setContentView(root)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(toolbar, linearParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        toolbar.addView(Button(this).apply {
            setText(R.string.back)
            setOnClickListener { finish() }
        }, linearParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        addressField = EditText(this).apply {
            setHint(R.string.website_address)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
            setText(intent.getStringExtra(EXTRA_URL))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) openEnteredUrl() else false
            }
        }
        toolbar.addView(addressField, linearParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        toolbar.addView(Button(this).apply {
            setText(R.string.go)
            setOnClickListener { openEnteredUrl() }
        }, linearParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))

        webView = WebView(this)
        root.addView(webView, linearParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                addressField.setText(url)
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.setDownloadListener { url, userAgent, _, _, _ -> startDownload(url, userAgent) }

        webView.loadUrl(intent.getStringExtra(EXTRA_URL) ?: SourceCatalog.all.first().url)
    }

    private fun openEnteredUrl(): Boolean {
        var url = addressField.text.toString().trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            url = "https://$url"
        }
        webView.loadUrl(url)
        return true
    }

    private fun startDownload(url: String, userAgent: String) {
        if (url.startsWith("blob:")) {
            Toast.makeText(this, R.string.unsupported_blob_download, Toast.LENGTH_LONG).show()
            return
        }
        val cookies = CookieManager.getInstance().getCookie(url)
        PatchJobService.download(this, url, userAgent, cookies)
        Toast.makeText(this, R.string.download_moved_to_background, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun linearParams(width: Int, height: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight)

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
