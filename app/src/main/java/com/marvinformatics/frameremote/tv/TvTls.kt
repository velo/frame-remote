package com.marvinformatics.frameremote.tv

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Builds an OkHttp client that tolerates the TV's self-signed certificate
 * for ONE host only. Every request through the returned client must target
 * that LAN IP — the hostname verifier rejects anything else. Global TLS
 * defaults are untouched.
 */
internal object TvTls {
    fun clientFor(ip: String): OkHttpClient {
        val trustTv = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustTv), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustTv)
            .hostnameVerifier { hostname, _ -> hostname == ip }
            .connectTimeout(3, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
