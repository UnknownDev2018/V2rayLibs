package com.unknowndevpn.v2raylibs.service

import android.content.Context
import android.net.VpnService
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object XrayManager : ProxyEngine {

    private var coreController: CoreController? = null
    private val coreInitialized = AtomicBoolean(false)

    override val name: String = "Xray"
    override val isRunning: Boolean get() = coreController?.isRunning == true

    fun getVersion(): String = try {
        Libv2ray.checkVersionX()
    } catch (e: Exception) {
        "Unknown"
    }

    private fun initCoreEnv(ctx: Context) {
        if (coreInitialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(ctx.applicationContext)
                extractGeoFiles(ctx)
                val assetsPath = ctx.getDir("assets", Context.MODE_PRIVATE).absolutePath
                Libv2ray.initCoreEnv(assetsPath, "")
            } catch (e: Exception) {
                coreInitialized.set(false)
            }
        }
    }

    override fun start(configJson: String, service: VpnService): Boolean {
        return start(configJson, service, 0)
    }

    fun start(configJson: String, service: VpnService, tunFd: Int): Boolean {
        return try {
            stop()
            val ctx = service.applicationContext
            initCoreEnv(ctx)

            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(status: Long, msg: String?): Long = 0L
            }

            val controller = Libv2ray.newCoreController(callback)
            controller.startLoop(configJson, tunFd)

            if (!controller.isRunning) {
                return false
            }

            coreController = controller
            verifySocksAsync(2000)
            true

        } catch (e: Exception) {
            false
        }
    }

    override fun stop() {
        try {
            coreController?.let {
                if (it.isRunning) {
                    it.stopLoop()
                }
            }
            coreController = null
        } catch (e: Exception) {
        }
    }

    private fun verifySocksAsync(delayMs: Long = 2000) {
        Thread {
            try {
                Thread.sleep(delayMs)

                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", CoreService.SOCKS_PORT),
                        3000
                    )
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun extractGeoFiles(ctx: Context) {
        val dir = ctx.getDir("assets", Context.MODE_PRIVATE)
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val dest = File(dir, name)
            if (!dest.exists() || dest.length() < 1024) {
                try {
                    ctx.assets.open(name).use { inp ->
                        FileOutputStream(dest).use { out -> inp.copyTo(out) }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }
}