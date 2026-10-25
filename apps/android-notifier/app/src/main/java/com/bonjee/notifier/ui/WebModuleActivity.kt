package com.bonjee.notifier.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R

/**
 * Hosts a Bonjee OS web module (Inbox, Tasks, Calendar, Memory, ...) inside the app.
 *
 * The dashboard is server-rendered against the same PostgreSQL database that n8n
 * and the Evolution API write to, so the Android app, the mobile browser and the
 * desktop view all show identical, live data. Nothing is duplicated locally.
 */
class WebModuleActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private var moduleTitle: String = "BONJEE OS"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_module)

        val prefs = Prefs.get(this)
        val path = intent.getStringExtra(EXTRA_PATH) ?: "/"
        moduleTitle = intent.getStringExtra(EXTRA_TITLE) ?: "BONJEE OS"

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progressBar)
        titleView = findViewById(R.id.txtModuleTitle)
        titleView.text = moduleTitle

        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            setSupportZoom(false)
            userAgentString = "$userAgentString BonjeeJarvis/2.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@WebModuleActivity,
                        "Could not reach the Bonjee dashboard. Check the Dashboard URL in Settings.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.loadUrl(prefs.dashboardUrl(path))
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_TITLE = "extra_title"
    }
}
