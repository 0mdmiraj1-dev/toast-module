package com.s9encryptor.strongtoastextended.app.platform

import java.io.IOException
import java.io.Reader

/** Root startup is fixed code. All variable operation data travels over private stdin. */
internal object RootProtocol {
    const val PREFIX = "S9BOOT1 "
    const val MAX_LINE_CHARS = 8192
    private val noncePattern = Regex("[0-9a-f]{64}")
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validNonce(value: String): Boolean = noncePattern.matches(value)

    fun shellQuote(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid internal path" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun startupScript(dexPath: String, digest: String, directoryId: String): String {
        require(dexPath.startsWith("/")) { "An absolute internal DEX path is required" }
        require(validNonce(digest)) { "Invalid asset digest" }
        require(uuidPattern.matches(directoryId)) { "Invalid staging identifier" }
        val directory = "/data/local/tmp/st-native-$directoryId"
        return """
            set -eu
            umask 077
            st_stage=${shellQuote(directory)}
            /system/bin/toybox mkdir -m 700 "${'$'}st_stage" || exit 71
            trap '/system/bin/toybox rm -f "${'$'}st_stage/bootstrap.dex"; /system/bin/toybox rmdir "${'$'}st_stage" 2>/dev/null || true' EXIT
            trap 'exit 143' HUP INT TERM
            /system/bin/toybox cp ${shellQuote(dexPath)} "${'$'}st_stage/bootstrap.dex"
            /system/bin/toybox chmod 400 "${'$'}st_stage/bootstrap.dex"
            st_digest=${'$'}(/system/bin/toybox sha256sum "${'$'}st_stage/bootstrap.dex")
            st_digest=${'$'}{st_digest%% *}
            [ "${'$'}st_digest" = ${shellQuote(digest)} ] || { echo 'Bootstrap asset checksum mismatch' >&2; exit 74; }
            CLASSPATH="${'$'}st_stage/bootstrap.dex" /system/bin/app_process /system/bin com.s9encryptor.strongtoastextended.bootstrap.NativeBootstrap
        """.trimIndent()
    }

    fun readBoundedLine(reader: Reader): String? {
        val line = StringBuilder(128)
        var count = 0
        while (true) {
            val value = reader.read()
            if (value == -1) return line.toString().takeIf { count > 0 }
            if (value == '\n'.code) return line.toString()
            if (++count > MAX_LINE_CHARS) throw IOException("Root output limit exceeded")
            if (value != '\r'.code) line.append(value.toChar())
        }
    }
}
