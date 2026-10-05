package com.carhud.aaproxy

internal object UpdateVersionPolicy {
    fun shouldNotify(installed: Int, remote: Int, seen: Set<String>, manual: Boolean): Boolean =
        remote > installed && (manual || remote.toString() !in seen)
}
