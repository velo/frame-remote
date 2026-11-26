package com.marvinformatics.frameremote.tv

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Tolerates the TV's self-signed certificate for ONE host only. Every
 * connection made through these helpers must target that LAN IP — the
 * OkHttp hostname verifier and [socketTo] both refuse anything else.
 * Global TLS defaults are untouched.
 */
internal object TvTls {
    private val trustTv = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val factory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustTv), null) }.socketFactory
    }

    fun clientFor(ip: String): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(factory, trustTv)
        .hostnameVerifier { hostname, _ -> hostname == ip }
        .connectTimeout(3, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun socketTo(tvIp: String, ip: String, port: Int, secured: Boolean, timeoutMs: Int): Socket {
        require(ip == tvIp) { "refusing transfer socket to $ip, TV is $tvIp" }
        val raw = Socket().apply {
            soTimeout = timeoutMs
            connect(InetSocketAddress(ip, port), timeoutMs)
        }
        return if (secured) factory.createSocket(raw, ip, port, true) else raw
    }
}
