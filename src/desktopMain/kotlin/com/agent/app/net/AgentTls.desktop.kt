package com.agent.app.net

import java.io.ByteArrayInputStream
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

// Desktop actual: the JVM has no network security config, so it installs the
// bundled CA into OkHttp's trust store explicitly — the "Customizing Trusted
// Certificates" recipe from the OkHttp docs: a KeyStore seeded with the system
// CAs plus the private CA → TrustManagerFactory → SSLContext →
// sslSocketFactory(socketFactory, trustManager).

private fun trustManager(): X509TrustManager {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(null, null)

    val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    sysTmf.init(null as KeyStore?)
    sysTmf.trustManagers.filterIsInstance<X509TrustManager>().forEachIndexed { i, tm ->
        tm.acceptedIssuers.forEachIndexed { j, cert -> ks.setCertificateEntry("sys-$i-$j", cert) }
    }

    val cf = java.security.cert.CertificateFactory.getInstance("X.509")
    cf.generateCertificates(ByteArrayInputStream(AGENT_CA_PEM.toByteArray()))
        .forEachIndexed { i, c -> ks.setCertificateEntry("agent-ca-$i", c) }

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}

actual fun buildAgentOkHttpClient(): OkHttpClient {
    val tm = trustManager()
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), null)
    return OkHttpClient.Builder()
        .sslSocketFactory(ctx.socketFactory, tm)
        .build()
}
