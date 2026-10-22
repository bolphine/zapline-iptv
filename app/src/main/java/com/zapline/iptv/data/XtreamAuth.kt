package com.zapline.iptv.data

import com.zapline.iptv.parse.Xtream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps an Xtream login "warm": many panels only accept stream requests from a client that has signed in
 * recently (from the same IP / player), and refuse with odd codes (406, 458, 460, 461…) otherwise.
 * Also repairs stored stream links when the saved login has changed since they were downloaded.
 */
object XtreamAuth {
    data class Status(val ok: Boolean, val message: String?, val maxConnections: Int = 0, val activeConnections: Int = 0) {
        /** Every connection the account allows is in use (by us or someone else). */
        val full get() = maxConnections > 0 && activeConnections >= maxConnections
    }

    /** The last successful sign-in's reply: connection allowance and use. */
    @Volatile var lastStatus: Status? = null; private set

    /** True when the provider allows only one stream at a time. */
    val singleConnection get() = lastStatus?.maxConnections == 1

    /** Player identities to try when a provider refuses ours. The first is the default. */
    val userAgents = listOf(
        DEFAULT_USER_AGENT,
        "IPTVSmartersPlayer",
        "TiviMate/5.1.6 (Android 13)",
        "okhttp/4.12.0",
    )

    /** HTTP codes providers use to mean "not you / not now". */
    fun isRefusal(code: Int) = code in setOf(401, 403, 405, 406, 407, 429, 509, 511) || code in 451..470

    private val streamUrl = Regex("^(https?://[^/]+(?:/[^/?#]+)*?)/(live|movie|series|timeshift)/([^/]+)/([^/]+)/(.+)$")

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    }

    @Volatile private var lastOk = 0L

    fun creds(p: Prefs): Xtream.Creds? =
        if (p.sourceType == "xtream" && p.xtUser.isNotBlank() && p.xtServer.isNotBlank()) Xtream.Creds(p.xtServer, p.xtUser, p.xtPass, p.xtFormat) else null

    fun isXtreamStream(url: String) = streamUrl.matches(url)

    /** A stored link with an old username/password, rewritten to the current login. */
    fun withCurrentLogin(url: String, p: Prefs): String {
        val c = creds(p) ?: return url
        val m = streamUrl.find(url) ?: return url
        val (base, kind, user, pass, rest) = m.destructured
        if (user == c.user && pass == c.pass) return url
        return "$base/$kind/${c.user}/${c.pass}/$rest"
    }

    /** Player identity that last worked with this provider. */
    fun userAgentFor(p: Prefs, url: String): String? = host(url)?.let { p.userAgentFor(it) }
    fun rememberUserAgent(p: Prefs, url: String, ua: String) { host(url)?.let { p.setUserAgentFor(it, if (ua == DEFAULT_USER_AGENT) null else ua) } }

    private fun host(url: String) = try { java.net.URI(url).host } catch (_: Exception) { null }

    /**
     * Signs in (player_api.php). Blocking: call off the main thread. Skipped if it succeeded in the last
     * few minutes, unless [force].
     */
    fun login(p: Prefs, userAgent: String = DEFAULT_USER_AGENT, force: Boolean = false): Status {
        val c = creds(p) ?: return Status(true, null)
        if (!force && System.currentTimeMillis() - lastOk < 5 * 60_000) return Status(true, null)
        return try {
            val req = Request.Builder().url(Xtream.apiUrl(c, null)).header("User-Agent", userAgent).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return Status(false, "Your provider refused the login (HTTP ${r.code}).")
                val status = parse(r.body?.string().orEmpty())
                if (status.ok) { lastOk = System.currentTimeMillis(); lastStatus = status }
                status
            }
        } catch (e: Exception) {
            Status(false, "Couldn't reach your provider to sign in (${e.message ?: "network error"}).")
        }
    }

    fun parse(json: String): Status {
        val o = try { JSONObject(json.trim()) } catch (_: Exception) { return Status(false, "Your provider sent an unexpected reply to the login.") }
        val u = o.optJSONObject("user_info") ?: return Status(false, "Your provider didn't accept the login.")
        if (u.optString("auth", "1") == "0" || u.optInt("auth", 1) == 0) return Status(false, "Your provider rejected the username or password. Check them in Settings → Edit this playlist.")
        val st = u.optString("status")
        if (st.isNotEmpty() && !st.equals("Active", true)) {
            return Status(false, when (st.lowercase()) {
                "expired" -> "Your subscription has expired."
                "banned" -> "Your account has been banned by the provider."
                "disabled" -> "Your account is disabled."
                else -> "Your account status is “$st”."
            })
        }
        val exp = u.optString("exp_date").toLongOrNull()
        if (exp != null && exp > 0 && exp * 1000 < System.currentTimeMillis()) return Status(false, "Your subscription has expired.")
        return Status(true, null, u.optString("max_connections").toIntOrNull() ?: 0, u.optString("active_cons").toIntOrNull() ?: 0)
    }

    /** A full playlist download is itself a sign-in. */
    fun markSignedIn() { lastOk = System.currentTimeMillis() }

    internal fun resetForTests() { lastOk = 0 }
}
