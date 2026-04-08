package com.unknowndevpn.v2raylibs.service

import android.content.Context
import hev.htproxy.TProxyService
import java.io.File

object Tun2SocksManager {

    @Volatile private var loaded = false
    @Volatile private var started = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
        }
    }

    fun isAvailable(): Boolean = loaded
    fun isActive(): Boolean = started

    fun start(
        ctx: Context,
        tunFd: Int,
        socksPort: Int = CoreService.SOCKS_PORT,
        user: String? = null,
        pass: String? = null
    ) {
        if (!loaded || started) return

        val yaml = buildString {
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  log-level: warning")
            appendLine("")
            appendLine("tunnel:")
            appendLine("  mtu: 9000")
            appendLine("")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: '127.0.0.1'")
            appendLine("  udp: 'udp'")

            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                appendLine("  username: '${esc(user)}'")
                appendLine("  password: '${esc(pass)}'")
            }
        }

        val configFile = File(ctx.cacheDir, "tun2socks.yml")
        configFile.writeText(yaml)

        try {
            TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            started = true
        } catch (e: Exception) {
        }
    }

    fun stop() {
        if (!started) return
        try {
            TProxyService.TProxyStopService()
            started = false
        } catch (e: Exception) {
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "''")
}