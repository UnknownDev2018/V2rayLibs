```markdown
# UnknownDeVPN

**Simple & beautiful V2Ray VPN client for Android**  
Built with Jetpack Compose + v2raylibs

---

## What v2raylibs needs to work

### 1. Config JSON
You **must** send a valid V2Ray outbound JSON.

**Name of the extra:** `CoreService.EXTRA_CONFIG_JSON`

Example (used in this app):

```json
{
  "tag": "proxy",
  "protocol": "vless",
  "settings": {
    "vnext": [{
      "address": "51.38.231.153",
      "port": 8080,
      "users": [{
        "id": "b7b74f9d-66e6-438b-b494-4efd36ef39b4",
        "encryption": "none",
        "flow": ""
      }]
    }]
  },
  "streamSettings": {
    "network": "xhttp",
    "xhttpSettings": {"path": "/udpguard"}
  }
}
```

You can change the server, port, UUID, etc.

### 2. Start VPN
```kotlin
val intent = Intent(this, CoreService::class.java).apply {
    action = CoreService.ACTION_START
    putExtra(CoreService.EXTRA_CONFIG_JSON, getV2rayConfig())   // your JSON string
    putExtra(CoreService.EXTRA_PROFILE_NAME, "UnknownDeVPN")
}
ContextCompat.startForegroundService(this, intent)
```

### 3. Stop VPN
```kotlin
val intent = Intent(this, CoreService::class.java).apply {
    action = CoreService.ACTION_STOP
}
startService(intent)
```

### 4. Check if VPN is running
```kotlin
val isRunning = CoreService.isActive   // Boolean
```

### 5. VPN Permission (VpnService.prepare)
Always check and request permission before starting:

```kotlin
val intent = VpnService.prepare(this)
if (intent != null) {
    prepareLauncher.launch(intent)   // ActivityResultLauncher
} else {
    startVpnService()
}
```

### 6. Notification Permission (Android 13+)
The app automatically asks for:

```kotlin
Manifest.permission.POST_NOTIFICATIONS
```

This is required to show the VPN notification.

---

## How the app works (simple flow)

1. User taps Power button  
2. Checks notification permission (Android 13+)  
3. Calls `VpnService.prepare()`  
4. Starts `CoreService` with the JSON  
5. Shows "CONNECTING..." → "CONNECTED" + live timer  
6. Tap again to stop

Everything is already implemented in `MainActivity.kt`.

---

`CoreService` must be declared as foreground service (already done in the library).

---

Ready to use. Just replace the JSON with your own server and you're good to go.

Made with ❤️ by UnknownDeVPN
```
