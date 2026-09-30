package com.laddu100

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "TMF_CF"

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step"
)

private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000
private const val SOLVER_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1000L
private const val CURSOR_STEP_DP = 10f
private const val SOLVE_COOLDOWN_MS = 10 * 60 * 1000L
private const val FRESH_COOKIE_MS = 60_000L

internal object TMFCFStore {
    private const val PREFS_NAME = "TMFCFBypass"

    class SavedCookies(val cookies: String, val userAgent: String, val savedAt: Long)

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun save(host: String, cookies: String, userAgent: String) {
        prefs?.edit()?.apply {
            putString("cf_cookies_$host", cookies)
            putString("cf_ua_$host", userAgent)
            putLong("cf_time_$host", System.currentTimeMillis())
        }?.apply()
    }

    fun cookiesFor(host: String): SavedCookies? {
        val p = prefs ?: return null
        val cookies = p.getString("cf_cookies_$host", null) ?: return null
        val ua = p.getString("cf_ua_$host", null) ?: return null
        val savedAt = p.getLong("cf_time_$host", 0L)
        if (System.currentTimeMillis() - savedAt > COOKIE_TTL_MS) {
            clear(host)
            return null
        }
        return SavedCookies(cookies, ua, savedAt)
    }

    fun clear(host: String) {
        prefs?.edit()?.apply {
            remove("cf_cookies_$host")
            remove("cf_ua_$host")
            remove("cf_time_$host")
        }?.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}

internal fun isCloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    if (code == 503) return true
    val body = try { response.text.lowercase() } catch (e: Exception) { "" }
    if (code == 403) {
        if (body.contains("just a moment") && body.contains("challenge-platform")) return true
        if (body.contains("checking your browser") && body.contains("cloudflare")) return true
        if (body.contains("cf-browser-verification")) return true
        if (body.contains("checking if the site connection is secure")) return true
        if (body.contains("just a moment")) return true
        return false
    }
    if (body.contains("just a moment") && body.contains("challenge-platform")) return true
    if (body.contains("checking your browser") && body.contains("cloudflare")) return true
    if (body.contains("cf-browser-verification")) return true
    if (body.contains("checking if the site connection is secure")) return true
    return false
}

private fun isChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return CF_CHALLENGE_TITLES.any { lower.contains(it) }
}

internal fun originOf(url: String): String = try {
    val uri = Uri.parse(url)
    "${uri.scheme}://${uri.host}"
} catch (e: Exception) {
    url
}

private fun parseCookieHeader(header: String): MutableMap<String, String> {
    val jar = mutableMapOf<String, String>()
    for (part in header.split(";")) {
        val idx = part.indexOf('=')
        if (idx > 0) {
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isNotEmpty()) jar[name] = value
        }
    }
    return jar
}

private fun buildCookieHeader(jar: Map<String, String>): String =
    jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

private val cfBypassMutex = Mutex()

private val driveSolveAt = ConcurrentHashMap<String, Long>()

private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

@SuppressLint("InflateParams")
private class TMFCFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var cursorView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy { originOf(targetUrl) }

    private fun extractAndFinish() {
        if (resolved.get()) return
        try {
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            val hasCfClearance = cookieStr.contains("cf_clearance")
            if (hasCfClearance) {
                finishSuccess(cookieStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractAndFinish error: ${e.message}")
        }
    }

    private fun finishSuccess(cookieStr: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: ""
        TMFCFStore.save(targetHost, cookieStr, ua)
        try { webView?.destroy() } catch (e: Exception) { Log.e(TAG, "destroy: ${e.message}") }
        try { (webView?.getTag() as? Dialog)?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(true) } catch (e: Exception) { Log.e(TAG, "onFinished: ${e.message}") }
    }

    private fun finishFailure() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(false) } catch (e: Exception) {}
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            extractAndFinish()
            if (!resolved.get()) {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    statusText?.text = "Timed out. Tap Cancel to close."
                    finishFailure()
                } else {
                    statusText?.text = "Waiting for cookies... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (screenW * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val titleView = TextView(activity).apply {
            text = "Cloudflare Bypass"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(titleView)

        val statusView = TextView(activity).apply {
            text = "Loading challenge page..."
            textSize = 12f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        val hintView = TextView(activity).apply {
            text = if (isTv) {
                "Use D-pad to move the cursor, OK/Enter to click the captcha checkbox."
            } else {
                "Solve the CAPTCHA below, then tap Done."
            }
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(hintView)

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (8 * dp).toInt() }
        }
        progressBar = progress
        container.addView(progress)

        val webViewHeight = (screenH * 0.65f).toInt()
        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

        if (isTv) {
            val cursorSize = (22 * dp).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                background = shape
                elevation = 999f
            }
            cursorView = cursor
            webContainer.addView(cursor)

            val pos = CursorPosHolder()
            pos.x = webViewHeight / 2f
            pos.y = webViewHeight / 2f
            cursor.translationX = pos.x - cursorSize / 2f
            cursor.translationY = pos.y - cursorSize / 2f

            webContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    webContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pos.x = webContainer.width / 2f
                    pos.y = webContainer.height / 2f
                    cursor.translationX = pos.x - cursorSize / 2f
                    cursor.translationY = pos.y - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, 0f, step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        clickAtCursor(pos, webView)
                        true
                    }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }

        val doneButton = android.widget.Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractAndFinish()
                if (!resolved.get()) {
                    statusText?.text = "No cookies yet. Solve the CAPTCHA first."
                }
            }
        }
        btnContainer.addView(doneButton)

        val cancelButton = android.widget.Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        }
        btnContainer.addView(cancelButton)
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(false)
            .create()

        webView?.setTag(dialog)

        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (e: Exception) {}
                try { onFinished?.invoke(false) } catch (e: Exception) {}
            }
        }

        dialog?.show()
        dialog?.window?.apply {
            setLayout(dialogW, dialogH)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                setCookie(targetHost, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            flush()
        }
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)

        handler.postDelayed({ finishFailure() }, SOLVER_TIMEOUT_MS)
    }

    private fun moveCursor(
        pos: CursorPosHolder,
        cursorView: View,
        cursorSize: Int,
        container: View,
        dx: Float,
        dy: Float
    ) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(
        pos: CursorPosHolder,
        webView: WebView?
    ) {
        val wv = webView ?: return
        val x = pos.x
        val y = pos.y
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, x, y, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
        } catch (e: Exception) {
            Log.e(TAG, "clickAtCursor: ${e.message}")
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        return WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                allowFileAccess = true
                loadsImagesAutomatically = true
                userAgentString = MOBILE_UA
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    val title = view?.title ?: ""

                    if (isChallengeTitle(title)) {
                        statusText?.text = "Challenge active - solve the CAPTCHA above"
                        extractAndFinish()
                        return
                    }

                    statusText?.text = "Page loaded - checking cookies..."
                    extractAndFinish()

                    url?.let {
                        try {
                            val altHost = originOf(it)
                            if (altHost != targetHost) {
                                val altCookies = CookieManager.getInstance().getCookie(altHost) ?: ""
                                if (altCookies.contains("cf_clearance")) {
                                    if (!resolved.get()) {
                                        resolved.set(true)
                                        handler.removeCallbacksAndMessages(null)
                                        val ua = webView?.settings?.userAgentString ?: ""
                                        TMFCFStore.save(altHost, altCookies, ua)
                                        try { webView?.destroy() } catch (e: Exception) {}
                                        try { dialog?.dismiss() } catch (e: Exception) {}
                                        try { onFinished?.invoke(true) } catch (e: Exception) {}
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "alt host extract: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try { webView?.apply { stopLoading(); destroy() } } catch (e: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (e: Exception) {}
        dialog = null
    }
}

suspend fun showTMFCFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Log.e(TAG, "No activity available to show CF dialog")
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = TMFCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            cfDialog.show(activity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show CF dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}

suspend fun tmfGet(
    url: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val targetHost = originOf(url)

    fun buildCfHeaders(): Map<String, String> {
        val h = headers.toMutableMap()
        if (!h.containsKey("Accept")) {
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        }
        if (!h.containsKey("Accept-Language")) {
            h["Accept-Language"] = "en-US,en;q=0.5"
        }
        h["sec-ch-ua-mobile"] = "?1"
        h["sec-ch-ua-platform"] = "\"Android\""
        val saved = TMFCFStore.cookiesFor(targetHost)
        if (saved != null) {
            h["Cookie"] = saved.cookies
            if (saved.userAgent.isNotBlank()) h["User-Agent"] = saved.userAgent
        }
        if (!h.containsKey("User-Agent")) h["User-Agent"] = MOBILE_UA
        return h
    }

    var response = try {
        app.get(url, headers = buildCfHeaders(), timeout = 30L)
    } catch (e: Exception) {
        Log.e(TAG, "First request failed: ${e.message}")
        throw e
    }

    if (!isCloudflareBlocked(response)) return response

    Log.e(TAG, "Cloudflare blocked (HTTP ${response.code}) - triggering bypass")

    cfBypassMutex.withLock {
        if (TMFCFStore.cookiesFor(targetHost) != null) {
            response = try {
                app.get(url, headers = buildCfHeaders(), timeout = 30L)
            } catch (e: Exception) {
                Log.e(TAG, "Retry with cached cookies failed: ${e.message}")
                throw e
            }
            if (!isCloudflareBlocked(response)) return response
        }

        TMFCFStore.clear(targetHost)
        val bypassSuccess = showTMFCFBypassDialogAndWait(url)

        if (!bypassSuccess) {
            Log.e(TAG, "CF bypass dialog failed/cancelled")
            return@withLock
        }

        for (attempt in 1..2) {
            response = try {
                app.get(url, headers = buildCfHeaders(), timeout = 30L)
            } catch (e: Exception) {
                Log.e(TAG, "Retry $attempt failed: ${e.message}")
                throw e
            }
            if (!isCloudflareBlocked(response)) {
                Log.d(TAG, "Request succeeded after CF bypass (attempt $attempt)")
                return response
            }
            Log.e(TAG, "Still CF-blocked after retry $attempt")
        }
    }

    return response
}

private fun swapDriveHost(url: String): String = when {
    url.contains("nexdrive.fit") -> url.replace("nexdrive.fit", "mobilejsr.rest")
    url.contains("mobilejsr.rest") -> url.replace("mobilejsr.rest", "nexdrive.fit")
    else -> url
}

private class DriveResult(val response: NiceResponse?, val challenged: Boolean)

// the drive pages sit behind cloudflare, okhttp either dies in a redirect
// loop or lands on a challenge page, walking the location headers by hand
// with a cookie jar survives both, the clearance cookie cloudflare hands out
// on one hop has to come back on the next one, a 503 is only a rate limit
// and no captcha in the world fixes that
private suspend fun driveFetchOnce(url: String, referer: String): DriveResult {
    val host = originOf(url)
    val saved = TMFCFStore.cookiesFor(host)
    val ua = saved?.userAgent?.takeIf { it.isNotBlank() } ?: MOBILE_UA
    val jar = saved?.let { parseCookieHeader(it.cookies) } ?: mutableMapOf()

    var current = url
    val seen = mutableSetOf<String>()
    repeat(12) {
        if (!seen.add(current)) {
            TMFCFStore.clear(host)
            return DriveResult(null, true)
        }
        val res = try {
            app.get(
                current,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to referer
                ),
                cookies = jar,
                allowRedirects = false,
                timeout = 20L
            )
        } catch (e: Exception) {
            return DriveResult(null, false)
        }
        jar.putAll(res.cookies)
        val loc = res.headers["location"]?.trim().orEmpty()
        if (loc.isEmpty()) {
            if (res.code == 503) return DriveResult(null, false)
            if (res.code != 200) return DriveResult(null, res.code == 403 || res.code == 429)
            if (isCloudflareBlocked(res)) {
                TMFCFStore.clear(host)
                return DriveResult(null, true)
            }
            if (saved == null && jar.isNotEmpty()) {
                TMFCFStore.save(host, buildCookieHeader(jar), ua)
            }
            return DriveResult(res, false)
        }
        current = when {
            loc.startsWith("http") -> loc
            loc.startsWith("/") -> originOf(current) + loc
            else -> current.trimEnd('/') + "/" + loc
        }
    }
    return DriveResult(null, true)
}

// nexdrive and mobilejsr serve the same drive app, when one host has a bad
// day the other usually still answers
suspend fun tmfDriveGet(url: String, referer: String): NiceResponse? {
    val primary = url.replace("mobilejsr.rest", "nexdrive.fit")
    val mirror = swapDriveHost(primary)
    val host = originOf(primary)

    val first = driveFetchOnce(primary, referer)
    if (first.response != null) return first.response
    val second = driveFetchOnce(mirror, referer)
    if (second.response != null) return second.response

    // only a real cloudflare challenge is worth a captcha, a rate limited
    // or dead drive host fails quietly
    if (!first.challenged && !second.challenged) return null

    val now = System.currentTimeMillis()
    return cfBypassMutex.withLock {
        val saved = TMFCFStore.cookiesFor(host)
        when {
            saved != null && now - saved.savedAt < FRESH_COOKIE_MS -> {
                driveFetchOnce(primary, referer).response
            }
            now - (driveSolveAt[host] ?: 0L) < SOLVE_COOLDOWN_MS -> {
                null
            }
            else -> {
                TMFCFStore.clear(host)
                val solved = showTMFCFBypassDialogAndWait(primary)
                val res = if (solved) driveFetchOnce(primary, referer).response else null
                if (res == null) driveSolveAt[host] = now
                res
            }
        }
    }
}

fun initTMFCFBypass(context: Context) {
    TMFCFStore.init(context)
    Log.d(TAG, "TMF CF bypass initialized (cursor-enabled, TTL=15h)")
}
