package com.unknowndevpn.v2raylibs.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.security.SecureRandom

open class CoreService : VpnService() {

    enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    companion object {
        const val TAG = "VpnLabs"

        const val ACTION_START = "com.iisidev.vpnlabs.action.START"
        const val ACTION_STOP = "com.iisidev.vpnlabs.action.STOP"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_PROFILE_NAME = "profile_name"

        const val SOCKS_PORT = 39271

        private const val VPN4 = "26.26.26.1"
        private const val VPN6 = "fd00::1"
        private const val MTU = 1500

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        val isActive: Boolean get() = _state.value == VpnState.CONNECTED

        var profileName: String? = null
            private set

        @Volatile
        var socksUser: String? = null
            private set

        @Volatile
        var socksPass: String? = null
            private set

        fun generateCreds() {
            val r = SecureRandom()
            val b = ByteArray(16)
            r.nextBytes(b)
            socksUser = b.joinToString("") { "%02x".format(it) }
            r.nextBytes(b)
            socksPass = b.joinToString("") { "%02x".format(it) }
        }

        fun injectSocksAuth(json: String, user: String?, pass: String?): String {
            if (user.isNullOrEmpty()) return json

            val authBlock = """"auth": "password",
      "accounts": [
        {
          "user": "$user",
          "pass": "$pass"
        }
      ],
      "udp": true"""

            return json.replace(
                """"protocol":\s*"socks"\s*,\s*"settings"\s*:\s*\{[^}]*\}""".toRegex(),
                """"protocol": "socks",
      "settings": {
        $authBlock
      }"""
            )
        }

        fun buildDefaultConfig(): String = """{
  "log": {"loglevel":"warning"},
  "inbounds":[{
    "tag":"socks-in","port":$SOCKS_PORT,"listen":"127.0.0.1",
    "protocol":"socks","settings":{"udp":true},
    "sniffing":{"enabled":true,"destOverride":["http","tls"]}
  }],
  "outbounds":[
    {"tag":"direct","protocol":"freedom"},
    {"tag":"block","protocol":"blackhole"}
  ],
  "routing":{"rules":[
    {"type":"field","port":"53","outboundTag":"direct"},
    {"type":"field","outboundTag":"direct"}
  ]
}"""

        fun buildCompleteConfig(userJson: String): String {
            return try {
                val userObj = JSONObject(userJson)
                val hasInbounds = userObj.has("inbounds")
                val hasOutbounds = userObj.has("outbounds")
                val isFullConfig = hasInbounds && hasOutbounds

                if (isFullConfig) {
                    val inbounds = userObj.getJSONArray("inbounds")
                    var hasSocksInbound = false

                    for (i in 0 until inbounds.length()) {
                        val inbound = inbounds.getJSONObject(i)
                        if (inbound.optString("protocol") == "socks" && inbound.optInt("port", -1) == SOCKS_PORT) {
                            hasSocksInbound = true
                            break
                        }
                    }

                    if (!hasSocksInbound) {
                        val socksInbound = JSONObject().apply {
                            put("tag", "socks-in")
                            put("port", SOCKS_PORT)
                            put("listen", "127.0.0.1")
                            put("protocol", "socks")
                            put("settings", JSONObject().apply { put("udp", true) })
                            put("sniffing", JSONObject().apply {
                                put("enabled", true)
                                put("destOverride", JSONArray().apply { put("http"); put("tls") })
                            })
                        }
                        val newInbounds = JSONArray()
                        newInbounds.put(socksInbound)
                        for (i in 0 until inbounds.length()) newInbounds.put(inbounds.get(i))
                        userObj.put("inbounds", newInbounds)
                    }

                    if (!userObj.has("log")) userObj.put("log", JSONObject().apply { put("loglevel", "warning") })
                    userObj.toString(2)

                } else {
                    JSONObject().apply {
                        put("log", JSONObject().apply { put("loglevel", "warning") })
                        val inbounds = JSONArray()
                        inbounds.put(JSONObject().apply {
                            put("tag", "socks-in")
                            put("port", SOCKS_PORT)
                            put("listen", "127.0.0.1")
                            put("protocol", "socks")
                            put("settings", JSONObject().apply { put("udp", true) })
                            put("sniffing", JSONObject().apply {
                                put("enabled", true)
                                put("destOverride", JSONArray().apply { put("http"); put("tls") })
                            })
                        })
                        put("inbounds", inbounds)

                        val outbounds = JSONArray()
                        val userCopy = JSONObject(userJson.toString())
                        if (!userCopy.has("tag")) userCopy.put("tag", "proxy")
                        if (!userCopy.has("protocol")) userCopy.put("protocol", "vless")
                        if (!userCopy.has("settings") || userCopy.isNull("settings")) userCopy.put("settings", JSONObject())
                        outbounds.put(userCopy)

                        outbounds.put(JSONObject().apply { put("tag", "direct"); put("protocol", "freedom") })
                        outbounds.put(JSONObject().apply { put("tag", "block"); put("protocol", "blackhole") })
                        put("outbounds", outbounds)

                        put("routing", JSONObject().apply {
                            put("rules", JSONArray().apply {
                                put(JSONObject().apply { put("type", "field"); put("port", 53); put("outboundTag", "direct") })
                                put(JSONObject().apply { put("type", "field"); put("network", "tcp,udp"); put("outboundTag", "proxy") })
                            })
                        })
                    }.toString(2)
                }

            } catch (e: Exception) {
                """{
  "log": {"loglevel": "warning"},
  "inbounds": [{"tag":"socks-in","port":$SOCKS_PORT,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true},"sniffing":{"enabled":true,"destOverride":["http","tls"]}}],
  "outbounds": [$userJson, {"tag":"direct","protocol":"freedom"}, {"tag":"block","protocol":"blackhole"}],
  "routing": {"rules": [{"type":"field","port":53,"outboundTag":"direct"}, {"type":"field","outboundTag":"proxy"}]}
}""".trimIndent()
            }
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var tunFd: ParcelFileDescriptor? = null
    private val engine: ProxyEngine = XrayManager

    private val conn by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val netCb by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { setUnderlyingNetworks(arrayOf(n)) }
            override fun onLost(n: Network) { setUnderlyingNetworks(null) }
        }
    }

    private val NOTIF_ID = 1001
    private val CHANNEL_ID = "vpnlabs_vpn"

    private var isServiceInitialized = false
    private var isStoppingService = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        try {
            startForeground(NOTIF_ID, notif("Initializing VPN..."))
            isServiceInitialized = true
        } catch (e: Exception) {
        }

        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal()
                stopSelfResult(startId)
                START_NOT_STICKY
            }
            else -> {

                connect(
                    rawJson = intent?.getStringExtra(EXTRA_CONFIG_JSON),
                    name = intent?.getStringExtra(EXTRA_PROFILE_NAME)
                )
                START_STICKY
            }
        }
    }

    private fun connect(
        rawJson: String?,
        name: String?
    ) {
        _state.value = VpnState.CONNECTING

        scope.launch {
            try {
                profileName = name ?: "VPN"

                tunFd = createTun() ?: run {
                    _state.value = VpnState.ERROR
                    updateNotifError("TUN Error")
                    stopSelfSafe()
                    return@launch
                }

                regNetCb()
                generateCreds()

                val baseConfig = buildCompleteConfig(rawJson ?: "")
                val finalJson = injectSocksAuth(baseConfig, socksUser, socksPass)

                val useT2S = Tun2SocksManager.isAvailable()

                if (useT2S) {
                    if (!engine.start(finalJson, this@CoreService)) throw Exception("Xray failed")

                    Thread.sleep(300)

                    Tun2SocksManager.start(
                        applicationContext,
                        tunFd!!.fd,
                        SOCKS_PORT,
                        socksUser,
                        socksPass
                    )
                } else {
                    val ok = if (engine is XrayManager)
                        engine.start(finalJson, this@CoreService, tunFd!!.fd)
                    else
                        engine.start(finalJson, this@CoreService)

                    if (!ok) throw Exception("Engine failed to start")
                }

                updateNotif(profileName!!)
                _state.value = VpnState.CONNECTED

            } catch (e: Exception) {
                _state.value = VpnState.ERROR
                updateNotifError("Error: ${e.message}")
                cleanup()
                stopSelfSafe()
            }
        }
    }

    private fun isPortListening(port: Int): Boolean {
        return try {
            val hexPort = port.toString(16).padStart(4, '0')
            val cmd = "cat /proc/net/tcp | grep ':$hexPort' | head -1"
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    private fun stopVpnInternal() {
        if (isStoppingService) {
            return
        }
        isStoppingService = true

        try {
            _state.value = VpnState.DISCONNECTED
            unregNetCb()
            Tun2SocksManager.stop()

            try { engine.stop() } catch (e: Exception) { }
            try { tunFd?.close() } catch (e: Exception) { }

            tunFd = null
            profileName = null
            socksUser = null
            socksPass = null

        } catch (e: Exception) {
        } finally {
            isStoppingService = false
            isServiceInitialized = false
        }
    }

    private fun cleanup() {
        try {
            tunFd?.close()
        } catch (e: Exception) { }
        tunFd = null
        profileName = null
        _state.value = VpnState.ERROR
    }

    override fun onRevoke() {
        stopVpnInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (isServiceInitialized || _state.value != VpnState.DISCONNECTED) {
            stopVpnInternal()
        }

        job.cancel()

        try { dismissNotifSafe() } catch (e: Exception) { }

        super.onDestroy()
    }

    private fun stopSelfSafe() {
        try {
            if (isServiceInitialized) updateNotif("Stopping...")

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } catch (e: Exception) {
                }
            }, 100)

        } catch (e: Exception) {
        }
    }

    private fun createTun(): ParcelFileDescriptor? = try {
        Builder()
            .setBlocking(false)
            .setSession("VpnLabs-Tunnel")
            .setMtu(MTU)
            .addAddress(VPN4, 30)
            .addRoute("0.0.0.0", 0)
            .addAddress(VPN6, 128)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addDisallowedApplication(packageName)
            .also { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.setMetered(false) }
            .establish()?.also {  }
    } catch (e: Exception) {
        null
    }

    private fun regNetCb() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { conn.unregisterNetworkCallback(netCb) } catch (_: Exception) { }
            try {
                conn.requestNetwork(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    netCb
                )
            } catch (e: Exception) {
            }
        }
    }

    private fun unregNetCb() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { conn.unregisterNetworkCallback(netCb) } catch (_: Exception) { }
        }
    }

    private fun notif(text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_manage)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(name: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notif("▶ $name"))
        } catch (e: Exception) {
        }
    }

    private fun updateNotifError(error: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notif("⚠️ $error"))
        } catch (e: Exception) {
        }
    }

    private fun dismissNotifSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "VpnLabs Core Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "Servicio VPN con tunnels modulares"
                channel.setShowBadge(false)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            } catch (e: Exception) {
            }
        }
    }
}