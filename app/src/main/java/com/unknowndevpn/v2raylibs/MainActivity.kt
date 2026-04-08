package com.unknowndevpn.v2raylibs

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.unknowndevpn.v2raylibs.service.CoreService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var vpnConnected by mutableStateOf(false)
    private var vpnConnecting by mutableStateOf(false)
    private var connectionTime by mutableStateOf("00:00:00")

    private val prepareVpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            showToast("VPN Permission Denied")
            vpnConnecting = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestVpnPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeVpnState()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0F)
                ) {
                    VpnScreen(
                        isConnected = vpnConnected,
                        isConnecting = vpnConnecting,
                        connectionTime = connectionTime,
                        onToggleClick = ::handleVpnToggle
                    )
                }
            }
        }
    }

    private fun handleVpnToggle() {
        if (vpnConnected || vpnConnecting) {
            stopVpn()
        } else {
            checkAndStartVpn()
        }
    }

    private fun checkAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> requestVpnPermission()
                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            prepareVpnLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        vpnConnecting = true
        try {
            val intent = Intent(this, CoreService::class.java).apply {
                action = CoreService.ACTION_START
                putExtra(CoreService.EXTRA_CONFIG_JSON, getV2rayConfig())
                putExtra(CoreService.EXTRA_PROFILE_NAME, "UnknownDeVPN")
            }
            ContextCompat.startForegroundService(this, intent)
            showToast("Starting VPN...")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
            vpnConnecting = false
        }
    }

    private fun stopVpn() {
        try {
            val intent = Intent(this, CoreService::class.java).apply { action = CoreService.ACTION_STOP }
            startService(intent)
            vpnConnected = false
            vpnConnecting = false
            connectionTime = "00:00:00"
            showToast("VPN Stopped")
        } catch (e: Exception) {
            showToast("Error stopping")
        }
    }

    private fun observeVpnState() {
        kotlinx.coroutines.MainScope().launch {
            var seconds = 0
            while (true) {
                delay(1000)
                val wasConnected = vpnConnected
                vpnConnected = CoreService.isActive

                if (vpnConnected && !wasConnected) seconds = 0

                if (vpnConnected) {
                    seconds++
                    val h = seconds / 3600
                    val m = (seconds % 3600) / 60
                    val s = seconds % 60
                    connectionTime = String.format("%02d:%02d:%02d", h, m, s)
                }

                if (!vpnConnected && vpnConnecting) delay(500)
            }
        }
    }

    private fun getV2rayConfig(): String = """
{
  "tag": "proxy",
  "protocol": "vless",
  "settings": {
    "vnext": [{
      "address": "SERVER_IP",
      "port": 8080,
      "users": [{
        "id": "UUID",
        "encryption": "none",
        "flow": ""
      }]
    }]
  },
  "streamSettings": {
    "network": "xhttp",
    "xhttpSettings": {
      "path": "/your-path"
    }
  }
}
    """.trimIndent()

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun VpnScreen(
    isConnected: Boolean,
    isConnecting: Boolean,
    connectionTime: String,
    onToggleClick: () -> Unit
) {
    val primaryGreen = Color(0xFF00E676)

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> primaryGreen
            isConnecting -> Color(0xFFFFB300)
            else -> Color(0xFF37474F)
        },
        animationSpec = tween(400),
        label = "color"
    )

    val statusColor by animateColorAsState(
        targetValue = when {
            isConnected -> primaryGreen
            isConnecting -> Color(0xFFFFB300)
            else -> Color(0xFF78909C)
        },
        animationSpec = tween(400),
        label = "status"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF0A0A0F), Color(0xFF121218), Color(0xFF1A1A25)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "UnknownDeVPN",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Powered by UnknownDeVPN",
                fontSize = 11.sp,
                color = Color.Gray.copy(alpha = 0.5f),
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(56.dp))

            PowerButton(
                isRunning = isConnected || isConnecting,
                color = buttonColor,
                onClick = onToggleClick
            )

            Spacer(modifier = Modifier.height(36.dp))

            Surface(
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.12f),
                modifier = Modifier.padding(horizontal = 28.dp)
            ) {
                Text(
                    text = when {
                        isConnected -> "CONNECTED"
                        isConnecting -> "CONNECTING..."
                        else -> "DISCONNECTED"
                    },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    letterSpacing = 3.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = connectionTime,
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraLight,
                color = if (isConnected) Color.White else Color.Gray.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "CONNECTION TIME",
                fontSize = 10.sp,
                color = Color.Gray.copy(alpha = 0.45f),
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoChip(label = "PROTOCOL", value = "VLESS/XHTTP")
                InfoChip(label = "STATUS", value = if (isConnected) "ACTIVE" else "INACTIVE", highlight = isConnected)
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PowerButton(
    isRunning: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0.82f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size(190.dp * scale)
            .shadow(elevation = 35.dp, shape = CircleShape, ambientColor = color.copy(alpha = 0.65f))
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f), color.copy(alpha = 0.25f))
                ),
                shape = CircleShape
            )
            .border(width = 2.5.dp, color = Color.White.copy(alpha = 0.12f), shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Power else Icons.Default.PowerSettingsNew,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(76.dp)
        )
    }
}

@Composable
private fun InfoChip(label: String, value: String, highlight: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                letterSpacing = 2.5.sp
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                color = if (highlight) primaryGreen() else Color.White,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 1.2.sp
            )
        }
    }
}

private fun primaryGreen(): Color = Color(0xFF00E676)