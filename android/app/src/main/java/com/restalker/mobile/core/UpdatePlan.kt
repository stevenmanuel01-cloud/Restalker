package com.restalker.mobile.core

/** 1.7 (owner 2026-11-16: "Why do i have to manually download everything still?"): the app updates itself. This part decides - from
 *  the release note (mobile/latest.json on the program's GitHub page) - whether there is a newer version, where its file is (only the
 *  program's own GitHub releases), its fingerprint (sha256: the downloaded file must match it), and when it may be installed (never while
 *  a purchase is running). Android itself also refuses a file not signed with the app's own key. */
object UpdatePlan {
    const val NOTE_URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/latest.json"
    /** 2.15 (owner, 2026-11-19: "my linked devices need to be considered the master/beta/administrator versions. When future changes are
     *  made, this should be pushed to my linked devices only ... rolled into a single wider update for all other users"): the owner's
     *  devices also read this one - every new version goes here first; latest.json (everyone) only when the owner says so. */
    const val BETA_URL = "https://raw.githubusercontent.com/stevenmanuel01-cloud/Restalker/main/mobile/beta.json"

    /** The newer of the two offers (the owner's devices: the test version when it is newer than the one for everyone). */
    fun pick(stable: Offer?, beta: Offer?): Offer? = listOfNotNull(stable, beta).maxByOrNull { it.code }
    const val APK_PREFIX = "https://github.com/stevenmanuel01-cloud/Restalker/releases/download/mobile-v"
    const val CHECK_EVERY_MS = 3 * 3_600_000L
    const val RETRY_SOON_MS = 2 * 60_000L

    data class Offer(val version: String, val code: Long, val apk: String, val sha256: String)

    /** -> the newer version, or null (none, older, or the note is not right). */
    fun parse(json: String?, currentCode: Int): Offer? {
        val m = MiniJson.obj(json) ?: return null
        val code = m["code"].asLong() ?: return null
        val v = m["version"].asStr()?.takeIf { Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){0,2}$").matches(it) } ?: return null
        val apk = m["apk"].asStr() ?: return null
        val sha = m["sha256"].asStr()?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return null
        if (code <= currentCode || code > currentCode + 1000) return null
        if (!apk.startsWith(APK_PREFIX) || apk.length > 300 || !apk.endsWith(".apk") || apk.contains("..") || apk.substring(APK_PREFIX.length).contains("?")) return null
        return Offer(v, code, apk, sha)
    }

    /** Never during a checkout (a product being bought, or the engine at a checkout page). */
    fun canInstallNow(products: List<Product>, busyWith: String?): Boolean =
        products.none { it.inProgress } && busyWith?.let { Regex("^(checking out|buy|reading your amazon orders)|checkout", RegexOption.IGNORE_CASE).containsMatchIn(it) } != true
}
