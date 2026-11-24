package com.ff6locke.btremoteex

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WifiRemoteActivity : Activity() {
    private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private var socket: WebSocket? = null
    private var token: String? = null
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var target: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.rgb(5, 15, 34))
        }
        root.addView(TextView(this).apply {
            text = "Wi-Fi Remote"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(-1)
        }, LinearLayout.LayoutParams(-1, 54))
        status = TextView(this).apply {
            text = "Connect your phone and TV to the same Wi-Fi network."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.LTGRAY)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 54))
        target = Spinner(this).apply {
            adapter = ArrayAdapter(this@WifiRemoteActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Samsung Smart TV", "RockTek GX1 / Android TV"))
        }
        root.addView(target, LinearLayout.LayoutParams(-1, 48))
        ipInput = EditText(this).apply {
            hint = "TV IP address (e.g. 192.168.1.100)"
            setSingleLine(true)
            textSize = 16f
            setTextColor(-1)
            setHintTextColor(android.graphics.Color.LTGRAY)
        }
        root.addView(ipInput, LinearLayout.LayoutParams(-1, 54))
        root.addView(Button(this).apply {
            text = "CONNECT"
            isAllCaps = false
            setOnClickListener { connectSelected() }
        }, LinearLayout.LayoutParams(-1, 48))
        val keys = listOf(
            listOf("POWER" to "KEY_POWER"),
            listOf("▲" to "KEY_UP"),
            listOf("◀" to "KEY_LEFT", "OK" to "KEY_ENTER", "▶" to "KEY_RIGHT"),
            listOf("▼" to "KEY_DOWN"),
            listOf("BACK" to "KEY_RETURN", "HOME" to "KEY_HOME", "MENU" to "KEY_MENU"),
            listOf("VOL +" to "KEY_VOLUP", "MUTE" to "KEY_MUTE", "VOL −" to "KEY_VOLDOWN"),
            listOf("CH +" to "KEY_CHUP", "SOURCE" to "KEY_SOURCE", "CH −" to "KEY_CHDOWN")
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            rowKeys.forEach { (label, key) ->
                row.addView(Button(this).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { sendSamsungKey(key) }
                }, LinearLayout.LayoutParams(0, 48, 1f).apply { setMargins(3, 2, 3, 2) })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, 52))
        }
        root.addView(Button(this).apply {
            text = "DISCONNECT"
            isAllCaps = false
            setOnClickListener { disconnect("Disconnected") }
        }, LinearLayout.LayoutParams(-1, 46))
        setContentView(root)
    }

    private fun connectSelected() {
        val ip = ipInput.text.toString().trim()
        if (ip.isBlank()) { status.text = "Enter the TV's IP address."; return }
        if (target.selectedItemPosition == 1) {
            status.text = "RockTek GX1 selected. Android TV secure pairing is being added separately; this target is not connected yet."
            return
        }
        disconnect(null)
        status.text = "Connecting to Samsung TV… approve the access request on the TV if prompted."
        val name = URLEncoder.encode("BTRemoteEX", "UTF-8")
        val savedToken = getSharedPreferences("wifi_remote", MODE_PRIVATE).getString("samsung_token_$ip", null)
        val scheme = if (savedToken == null) "ws" else "wss"
        val port = if (savedToken == null) 8001 else 8002
        val tokenParam = savedToken ?: ""
        val request = Request.Builder()
            .url("$scheme://$ip:$port/api/v2/channels/samsung.remote.control?name=$name&token=$tokenParam")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { status.text = "Samsung WebSocket connected. Accept the prompt on your TV if it appears." }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    val event = data.optString("event")
                    val newToken = data.optJSONObject("data")?.optString("token")
                    if (!newToken.isNullOrBlank() && newToken != "null") {
                        token = newToken
                        getSharedPreferences("wifi_remote", MODE_PRIVATE).edit().putString("samsung_token_$ip", newToken).apply()
                    }
                    runOnUiThread {
                        if (event == "ms.channel.connect") status.text = "Samsung TV connected."
                        else if (event == "ms.channel.unauthorized") status.text = "TV did not authorize BTRemoteEX. Allow it on the TV and reconnect."
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { status.text = "Samsung connection failed: ${t.message ?: "check TV IP and same Wi-Fi"}" }
                socket = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { status.text = "Samsung TV disconnected." }
                socket = null
            }
        })
    }

    private fun sendSamsungKey(key: String) {
        val ws = socket
        if (ws == null) { status.text = "Connect to the Samsung TV first."; return }
        val payload = JSONObject().put("method", "ms.remote.control").put("params",
            JSONObject().put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
        if (!ws.send(payload.toString())) status.text = "Could not send key. Reconnect to the TV."
    }

    private fun disconnect(message: String?) {
        socket?.close(1000, "User disconnected")
        socket = null
        token = null
        if (message != null && ::status.isInitialized) status.text = message
    }

    override fun onDestroy() {
        disconnect(null)
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
