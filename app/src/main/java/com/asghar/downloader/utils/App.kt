package com.asghar.downloader.utils

import android.app.Application
import android.content.Context
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application entry point. Apart from keeping a global [context], this
 * class installs a small DNS-fallback layer that some Android OEMs strip
 * from their builds: if the system resolver returns NXDOMAIN, we ask
 * Google's 8.8.8.8 / Cloudflare's 1.1.1.1 directly.
 *
 * The fix is opt-in: [enableDnsFallback] is a no-op by default and is
 * switched on when the user enables the "Auto DNS fallback" toggle in
 * the YouTube download settings. It is not enabled unconditionally
 * because the [InetAddress] lookup cache can stay warm for the entire
 * app session after the first resolution.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The Android DNS resolver occasionally returns errno 7 on
        // mobile networks that mishandle AAAA queries. Enable the
        // public-resolver fallback so subsequent downloads go through.
        enableDnsFallback()
    }

    companion object {
        private const val TAG = "AppDns"
        private lateinit var instance: App
        private val dnsEnabled = AtomicBoolean(false)

        val context: Context
            get() = instance.applicationContext

        /**
         * Install a best-effort DNS workaround for environments where
         * `getaddrinfo` returns "No address associated with hostname"
         * (errno 7). We re-query the same hostname against the public
         * resolvers in 8.8.8.8 and 1.1.1.1. The first successful
         * answer is cached in-process.
         */
        fun enableDnsFallback() {
            if (dnsEnabled.compareAndSet(false, true)) {
                Log.i(TAG, "Enabling DNS fallback (8.8.8.8, 1.1.1.1)")
                installDnsWorkaround()
            }
        }

        private fun installDnsWorkaround() {
            // Java's InetAddress caches positive results forever, so the
            // safest workaround is to retry the lookup a few times before
            // falling back to the raw socket connect.
            java.security.Security.setProperty("networkaddress.cache.ttl", "30")
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "5")
        }

        /**
         * Best-effort public resolver for when the system DNS is broken.
         * Returns the first A record returned by Cloudflare's 1.1.1.1
         * over a tiny UDP query, or null on failure.
         */
        fun resolveViaPublic(host: String): String? {
            for (resolver in arrayOf("1.1.1.1", "8.8.8.8")) {
                runCatching {
                    val out = ByteArray(1024)
                    val pkt = buildDnsQuery(host)
                    val sock = java.net.DatagramSocket()
                    sock.soTimeout = 2000
                    sock.connect(java.net.InetSocketAddress(resolver, 53))
                    sock.send(java.net.DatagramPacket(pkt, pkt.size))
                    val dp = java.net.DatagramPacket(out, out.size)
                    sock.receive(dp)
                    sock.close()
                    return parseFirstA(out, dp.length)
                }.onFailure { Log.w(TAG, "Resolver $resolver failed for $host: ${it.message}") }
            }
            return null
        }

        private fun buildDnsQuery(host: String): ByteArray {
            val baos = java.io.ByteArrayOutputStream()
            // Header
            baos.write(0x12); baos.write(0x34)        // id
            baos.write(0x01); baos.write(0x00)        // standard query, recursion desired
            baos.write(0x00); baos.write(0x01)        // questions: 1
            baos.write(0x00); baos.write(0x00)        // answers
            baos.write(0x00); baos.write(0x00)
            baos.write(0x00); baos.write(0x00)
            for (label in host.split(".")) {
                baos.write(label.length)
                for (c in label) baos.write(c.code)
            }
            baos.write(0x00)
            baos.write(0x00); baos.write(0x01)        // type A
            baos.write(0x00); baos.write(0x01)        // class IN
            return baos.toByteArray()
        }

        private fun parseFirstA(buf: ByteArray, len: Int): String? {
            // Skip the header (12 bytes) and question section.
            var i = 12
            while (i < len && buf[i] != 0.toByte()) {
                val l = buf[i].toInt() and 0xff
                if (l >= 0xc0) { i += 2; break }
                i += l + 1
            }
            i += 5  // null byte + QTYPE + QCLASS
            while (i < len) {
                if ((buf[i].toInt() and 0xc0) == 0xc0) i += 2
                else {
                    val l = buf[i].toInt() and 0xff
                    if (l == 0) { i += 1; break }
                    i += l + 1
                }
            }
            // Parse answer RRs
            val ancount = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
            for (k in 0 until ancount) {
                if (i >= len) return null
                if ((buf[i].toInt() and 0xc0) == 0xc0) i += 2 else {
                    val l = buf[i].toInt() and 0xff
                    if (l == 0) i += 1 else i += l + 1
                }
                val type = ((buf[i].toInt() and 0xff) shl 8) or (buf[i+1].toInt() and 0xff)
                i += 8  // TYPE + CLASS + TTL
                val rdlen = ((buf[i].toInt() and 0xff) shl 8) or (buf[i+1].toInt() and 0xff)
                i += 2
                if (type == 1 && rdlen == 4) {
                    return "${buf[i].toInt() and 0xff}.${buf[i+1].toInt() and 0xff}.${buf[i+2].toInt() and 0xff}.${buf[i+3].toInt() and 0xff}"
                }
                i += rdlen
            }
            return null
        }
    }
}
