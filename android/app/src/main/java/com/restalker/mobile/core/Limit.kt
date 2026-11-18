package com.restalker.mobile.core

/** 2.7 (owner, 2026-11-17: "a max amount of 3 purchases for each product ever (with the exception of my master account). The program
 *  should not allow other users to purchase a product more than 3 total times. the user can still watch and contribute to the
 *  crowdsource information, but no purchases"): the limit and who the master (the owner) is - the same as the PC's master.py v138.
 *  The owner's devices are known by a one-way fingerprint (sha256) of their private report code or of the owner's master code - the
 *  codes themselves are not in the app; a phone / tablet linked to the owner's PC is told by it (inside the sealed link). Pure: CoreTests. */
object Limit {
    const val MAX = 3
    private val HASHES = setOf(
        "77f1096b2366d0cc97ad304aeb07cbbe49baf9d5475b594910f73008175c6948",     // the owner's PC (its report code)
        "6168946e644a94ac4d5c14e7becf946436ef2e8e8bc19895e57f662f5259db03",     // the owner's phone (its report code)
        "ade0a35119b780e65be47fcfb31222e9f863eb97d4b9639ef59b646b27d5be62",     // the owner's master code
    )
    fun fp(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.trim().toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun codeOk(code: String): Boolean = code.isNotBlank() && fp(code) in HASHES
    /** This device is the owner's: its report code, the master code typed here, or a linked PC that is the owner's. */
    fun isMaster(s: Settings, pcIsMaster: Boolean): Boolean = codeOk(s.reportTopic) || codeOk(s.masterCode) || pcIsMaster
    /** May this product be bought (again) here? [bought] = its purchases on every linked device. */
    fun mayBuy(bought: Int, master: Boolean): Boolean = master || bought < MAX
    const val WORDS = "Bought $MAX times already - Restalker buys each product at most $MAX times (restocks are for players, not resellers). " +
        "It still watches it and shares what it sees."
}
