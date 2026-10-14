package org.schabi.newpipe.backup

/**
 * A server address as typed by the user: a host name or IP address, optionally with a scheme, a
 * user name, a port and a path, for example "sftp://me@192.168.1.5:2222/backups".
 */
data class HostAddress(val host: String, val port: Int, val path: String) {
    /** Puts the path of the address in front of [folder]. */
    fun combineWithFolder(folder: String): String {
        if (path.isEmpty()) {
            return folder
        }
        val relative = folder.trim().trim('/')
        return if (relative.isEmpty()) "/$path" else "/$path/$relative"
    }

    companion object {
        @JvmStatic
        fun parse(input: String): HostAddress {
            var rest = input.trim()
            val schemeEnd = rest.indexOf("://")
            if (schemeEnd >= 0) {
                rest = rest.substring(schemeEnd + 3)
            }
            val slash = rest.indexOf('/')
            val path = if (slash >= 0) rest.substring(slash).trim('/') else ""
            val hostAndPort = (if (slash >= 0) rest.substring(0, slash) else rest)
                .substringAfterLast('@')

            if (hostAndPort.startsWith("[")) {
                // An IPv6 address, as in [2001:db8::1]:2222
                val end = hostAndPort.indexOf(']')
                if (end > 0) {
                    val port = hostAndPort.substring(end + 1).removePrefix(":").toIntOrNull()
                    return HostAddress(hostAndPort.substring(1, end), port ?: 0, path)
                }
            }
            if (hostAndPort.count { it == ':' } == 1) {
                val port = hostAndPort.substringAfter(':').toIntOrNull()
                return HostAddress(hostAndPort.substringBefore(':'), port ?: 0, path)
            }
            // A host name, an IPv4 address or an IPv6 address without a port
            return HostAddress(hostAndPort, 0, path)
        }
    }
}
