package com.unknowndevpn.v2raylibs.service

interface ProxyEngine {
    val name: String
    val isRunning: Boolean
    fun start(configJson: String, service: android.net.VpnService): Boolean
    fun stop()
}