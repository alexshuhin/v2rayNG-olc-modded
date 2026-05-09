package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Parses the olcrtc URI format defined in olcrtc/docs/uri.md:
 *   olcrtc://<carrier>?<transport>@<roomID>#<key>%<clientID>$<MIMO>
 *
 * Note: the delimiters `?`, `@`, `#`, `%`, `$` are NOT standard URL parts,
 * they are custom field separators. Standard URL parsers will NOT work here.
 *
 * Field mapping into ProfileItem:
 *   carrier   -> network        (wbstream / telemost / jazz)
 *   transport -> headerType     (datachannel / vp8channel)
 *   roomID    -> host
 *   keyHex    -> password
 *   clientID  -> username
 *   MIMO      -> remarks        (display label / comment)
 */
object OlcrtcFmt {

    private const val SCHEME = AppConfig.OLCRTC

    fun parse(str: String): ProfileItem? {
        if (!str.startsWith(SCHEME)) return null

        var rest = str.substring(SCHEME.length)
        if (rest.isEmpty()) return null

        // 1. split MIMO (everything after the LAST `$`) — used as remarks
        val mimo: String
        val dollarIdx = rest.indexOf('$')
        if (dollarIdx >= 0) {
            mimo = rest.substring(dollarIdx + 1)
            rest = rest.substring(0, dollarIdx)
        } else {
            mimo = ""
        }

        // 2. carrier ? rest
        val qIdx = rest.indexOf('?')
        if (qIdx <= 0) return null
        val carrier = rest.substring(0, qIdx)
        rest = rest.substring(qIdx + 1)

        // 3. transport @ rest
        val atIdx = rest.indexOf('@')
        if (atIdx <= 0) return null
        val transport = rest.substring(0, atIdx)
        rest = rest.substring(atIdx + 1)

        // 4. roomID # rest
        val hashIdx = rest.indexOf('#')
        if (hashIdx < 0) return null
        val roomId = rest.substring(0, hashIdx)
        rest = rest.substring(hashIdx + 1)

        // 5. key % clientID
        val pctIdx = rest.indexOf('%')
        if (pctIdx <= 0) return null
        val keyHex = rest.substring(0, pctIdx)
        val clientId = rest.substring(pctIdx + 1)

        if (carrier.isBlank() || transport.isBlank() || keyHex.isBlank() || clientId.isBlank()) {
            return null
        }
        // roomId may legitimately be empty for jazz when "any" is implied — but the URI form always has it explicit

        val config = ProfileItem.create(EConfigType.OLCRTC)
        config.remarks = mimo.trim().ifEmpty { "olcrtc-$carrier" }
        config.network = carrier
        config.headerType = transport
        config.host = roomId
        config.password = keyHex
        config.username = clientId
        // Server/port are not really applicable, but ProfileItem APIs expect non-null;
        // use the carrier name as a placeholder so list views show something useful.
        config.server = carrier
        config.serverPort = "0"
        return config
    }

    fun toUri(config: ProfileItem): String {
        val carrier = config.network.orEmpty()
        val transport = config.headerType.orEmpty()
        val roomId = config.host.orEmpty()
        val keyHex = config.password.orEmpty()
        val clientId = config.username.orEmpty()
        val mimo = config.remarks
        // NOTE: do NOT URL-encode — these are raw fields, not URL components.
        // The spec recommends avoiding the delimiter characters inside field values.
        return buildString {
            append(carrier)
            append('?')
            append(transport)
            append('@')
            append(roomId)
            append('#')
            append(keyHex)
            append('%')
            append(clientId)
            if (mimo.isNotEmpty()) {
                append('$')
                append(mimo)
            }
        }
    }
}
