package com.dayoudu.app.data

import java.net.URI

/** A configured HTTPS origin takes precedence in every build, including debug APKs. */
internal fun cloudEndpoint(configuredOrigin: String, debug: Boolean, emulator: Boolean): String {
    val explicit = configuredOrigin.trim().trimEnd('/')
    val origin = if (explicit.isNotEmpty()) explicit else {
        require(debug) { "云服务地址尚未配置，请安装已配置正式服务的版本" }
        if (emulator) "http://10.0.2.2:18080" else "http://127.0.0.1:18080"
    }
    val uri = runCatching { URI(origin) }.getOrElse { throw IllegalArgumentException("应用云服务配置无效") }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.path.isNullOrEmpty() && uri.host != null && uri.port in -1..65535 && uri.port != 0) {
        "应用云服务配置无效"
    }
    require(uri.scheme == "https" || explicit.isEmpty() && debug && uri.scheme == "http" &&
        uri.host in setOf("127.0.0.1", "10.0.2.2")) { "配置的云服务地址必须使用 HTTPS" }
    return origin
}
