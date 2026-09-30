package com.laddu100

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse

private const val TAG = "TMF"

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000

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
}

private fun isCloudflareBlocked(response: NiceResponse): Boolean {
    if (response.code == 503) return true
    val body = try {
        response.text.lowercase()
    } catch (e: Exception) {
        ""
    }
    if (body.contains("cf-browser-verification")) return true
    if (body.contains("checking if the site connection is secure")) return true
    if (body.contains("checking your browser") && body.contains("cloudflare")) return true
    if (body.contains("just a moment") &&
        (response.code == 403 || body.contains("challenge-platform"))
    ) return true
    return false
}

private fun originOf(url: String): String = try {
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

private fun buildPageHeaders(
    headers: Map<String, String>,
    saved: TMFCFStore.SavedCookies?
): Map<String, String> {
    val h = headers.toMutableMap()
    if (!h.containsKey("Accept")) {
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
    if (!h.containsKey("Accept-Language")) {
        h["Accept-Language"] = "en-US,en;q=0.5"
    }
    h["sec-ch-ua-mobile"] = "?1"
    h["sec-ch-ua-platform"] = "\"Android\""
    if (saved != null) {
        h["Cookie"] = saved.cookies
        if (saved.userAgent.isNotBlank()) h["User-Agent"] = saved.userAgent
    }
    if (!h.containsKey("User-Agent")) h["User-Agent"] = MOBILE_UA
    return h
}

suspend fun tmfGet(
    url: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val targetHost = originOf(url)
    val saved = TMFCFStore.cookiesFor(targetHost)

    val response = app.get(url, headers = buildPageHeaders(headers, saved), timeout = 30L)
    if (!isCloudflareBlocked(response)) return response

    // a stale clearance cookie gets rejected harder than no cookie at all,
    // drop it and retry clean once before giving up
    if (saved != null) {
        TMFCFStore.clear(targetHost)
        return app.get(url, headers = buildPageHeaders(headers, null), timeout = 30L)
    }
    Log.d(TAG, "cloudflare wall on $targetHost, nothing left to try")
    return response
}

private fun swapDriveHost(url: String): String = when {
    url.contains("nexdrive.fit") -> url.replace("nexdrive.fit", "mobilejsr.rest")
    url.contains("mobilejsr.rest") -> url.replace("mobilejsr.rest", "nexdrive.fit")
    else -> url
}

// the drive pages sit behind cloudflare, okhttp either dies in a redirect
// loop or lands on a challenge page, walking the location headers by hand
// with a cookie jar survives both, the clearance cookie handed out on one
// hop has to come back on the next one, a 503 is just the host rate
// limiting and burning requests on it never helps
private suspend fun driveFetchOnce(url: String, referer: String): NiceResponse? {
    val host = originOf(url)
    val saved = TMFCFStore.cookiesFor(host)
    val ua = saved?.userAgent?.takeIf { it.isNotBlank() } ?: MOBILE_UA
    val jar = saved?.let { parseCookieHeader(it.cookies) } ?: mutableMapOf()

    var current = url
    val seen = mutableSetOf<String>()
    repeat(12) {
        if (!seen.add(current)) {
            TMFCFStore.clear(host)
            return null
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
            Log.d(TAG, "drive fetch failed: ${e.message}")
            return null
        }
        jar.putAll(res.cookies)
        val loc = res.headers["location"]?.trim().orEmpty()
        if (loc.isEmpty()) {
            if (res.code == 503) return null
            if (res.code != 200) return null
            if (isCloudflareBlocked(res)) {
                TMFCFStore.clear(host)
                return null
            }
            if (saved == null && jar.isNotEmpty()) {
                TMFCFStore.save(host, buildCookieHeader(jar), ua)
            }
            return res
        }
        current = when {
            loc.startsWith("http") -> loc
            loc.startsWith("/") -> originOf(current) + loc
            else -> current.trimEnd('/') + "/" + loc
        }
    }
    return null
}

// nexdrive and mobilejsr serve the same drive app, when one host has a bad
// day the other usually still answers
suspend fun tmfDriveGet(url: String, referer: String): NiceResponse? {
    val primary = url.replace("mobilejsr.rest", "nexdrive.fit")
    val mirror = swapDriveHost(primary)
    driveFetchOnce(primary, referer)?.let { return it }
    return driveFetchOnce(mirror, referer)
}
