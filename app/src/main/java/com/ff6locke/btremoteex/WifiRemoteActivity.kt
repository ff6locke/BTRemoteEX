package com.ff6locke.btremoteex

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var target: Spinner
    private lateinit var samsungControls: LinearLayout
    private lateinit var rocktekInfo: TextView
    private lateinit var connectButton: Button

    private fun buttonStyle(button: Button) {
        button.setTextColor(Color.WHITE)
        button.textSize = 17f
        button.typeface = Typeface.DEFAULT_BOLD
        button.gravity = Gravity.CENTER
        button.isAllCaps = false
        button.background = GradientDrawable().apply {
            cornerRadius = 12f
            setColor(Color.rgb(24, 48, 78))
            setStroke(1, Color.rgb(78, 125, 190))
        }
        button.minimumHeight = 56
        button.setPadding(8, 8, 8, 8)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val padSize = dp(if (landscape) 56 else 48)
        val gap = dp(4)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(5, 15, 34))
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "Wi-Fi Remote"
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(30), 1f))
        status = TextView(this).apply {
            text = "Connect both devices to the same Wi-Fi."
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(Color.LTGRAY)
            maxLines = 1
        }
        heading.addView(status, LinearLayout.LayoutParams(0, dp(30), 2f))
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(30)))

        val connectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        target = Spinner(this).apply {
            adapter = ArrayAdapter(this@WifiRemoteActivity, android.R.layout.simple_spinner_item,
                listOf("Samsung TV", "RockTek GX1")).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    disconnect(null)
                    showTarget(position)
                }
            }
        }
        connectionRow.addView(target, LinearLayout.LayoutParams(0, dp(44), 1.1f).apply { rightMargin = gap })
        ipInput = EditText(this).apply {
            hint = "TV IP address"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(20, 36, 58))
                setStroke(dp(1), Color.rgb(78, 125, 190))
            }
        }
        connectionRow.addView(ipInput, LinearLayout.LayoutParams(0, dp(44), 1.7f).apply { rightMargin = gap })
        connectButton = Button(this).apply {
            text = "CONNECT"
            buttonStyle(this)
            textSize = 13f
            setOnClickListener { connectSelected() }
        }
        connectionRow.addView(connectButton, LinearLayout.LayoutParams(0, dp(44), 1.2f))
        root.addView(connectionRow, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(3) })

        samsungControls = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        fun remoteButton(label: String, key: String, accent: Boolean = false): Button =
            Button(this).apply {
                text = label
                textSize = if (label == "OK") 21f else 28f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                minHeight = padSize
                minimumHeight = padSize
                setPadding(dp(2), dp(1), dp(2), dp(1))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (accent) Color.rgb(0, 112, 245) else Color.rgb(35, 65, 105))
                    setStroke(dp(1), Color.rgb(78, 125, 190))
                }
                setOnClickListener { sendSamsungKey(key) }
            }
        val dpad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        topRow.addView(View(this), LinearLayout.LayoutParams(padSize, padSize))
        topRow.addView(remoteButton("▲", "KEY_UP"), LinearLayout.LayoutParams(padSize, padSize).apply { setMargins(gap / 2, 0, gap / 2, 0) })
        topRow.addView(View(this), LinearLayout.LayoutParams(padSize, padSize))
        dpad.addView(topRow)
        val middleRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        middleRow.addView(remoteButton("◀", "KEY_LEFT"), LinearLayout.LayoutParams(padSize, padSize).apply { rightMargin = gap / 2 })
        middleRow.addView(remoteButton("OK", "KEY_ENTER", true), LinearLayout.LayoutParams(padSize, padSize).apply { leftMargin = gap / 2; rightMargin = gap / 2 })
        middleRow.addView(remoteButton("▶", "KEY_RIGHT"), LinearLayout.LayoutParams(padSize, padSize).apply { leftMargin = gap / 2 })
        dpad.addView(middleRow, LinearLayout.LayoutParams(-1, padSize).apply { topMargin = gap / 2; bottomMargin = gap / 2 })
        val bottomRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        bottomRow.addView(View(this), LinearLayout.LayoutParams(padSize, padSize))
        bottomRow.addView(remoteButton("▼", "KEY_DOWN"), LinearLayout.LayoutParams(padSize, padSize).apply { setMargins(gap / 2, 0, gap / 2, 0) })
        bottomRow.addView(View(this), LinearLayout.LayoutParams(padSize, padSize))
        dpad.addView(bottomRow)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        fun remoteRow(vararg entries: Pair<String, String>) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            entries.forEach { (label, key) ->
                row.addView(Button(this).apply {
                    text = label
                    buttonStyle(this)
                    textSize = 12f
                    minimumHeight = dp(38)
                    minHeight = dp(38)
                    setPadding(dp(2), 0, dp(2), 0)
                    setOnClickListener { sendSamsungKey(key) }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            actions.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        }
        remoteRow("BACK" to "KEY_RETURN", "HOME" to "KEY_HOME", "MENU" to "KEY_MENU")
        remoteRow("VOL +" to "KEY_VOLUP", "MUTE" to "KEY_MUTE", "VOL −" to "KEY_VOLDOWN")
        remoteRow("CH +" to "KEY_CHUP", "SOURCE" to "KEY_SOURCE", "CH −" to "KEY_CHDOWN")
        actions.addView(Button(this).apply {
            text = "DISCONNECT"
            buttonStyle(this)
            textSize = 12f
            setOnClickListener { disconnect("Disconnected") }
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })

        if (landscape) {
            samsungControls.addView(dpad, LinearLayout.LayoutParams(dp(padSize / density * 3 + 8), -2).apply { rightMargin = dp(8) })
            samsungControls.addView(actions, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            samsungControls.addView(dpad, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            samsungControls.addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(samsungControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        rocktekInfo = TextView(this).apply {
            text = "RockTek GX1 Wi-Fi control is not available in this test version. Android TV secure pairing must be added first."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        root.addView(rocktekInfo, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(scroll)
        target.setSelection(0)
        showTarget(0)
    }
    private fun showTarget(position: Int) {
        val samsung = position == 0
        samsungControls.visibility = if (samsung) View.VISIBLE else View.GONE
        rocktekInfo.visibility = if (samsung) View.GONE else View.VISIBLE
        connectButton.text = if (samsung) "CONNECT TO SAMSUNG TV" else "ROCKTEK PAIRING NOT AVAILABLE"
        status.text = if (samsung) "Samsung Smart TV selected." else "RockTek GX1 / Android TV selected."
    }

    private fun connectSelected() {
        val ip = ipInput.text.toString().trim()
        if (ip.isBlank()) { status.text = "Enter the device IP address."; return }
        if (target.selectedItemPosition == 1) {
            status.text = "RockTek Wi-Fi pairing is not implemented in this version."
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
                runOnUiThread { status.text = "Samsung link opened. Accept the prompt on your TV if shown." }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    val event = data.optString("event")
                    val newToken = data.optJSONObject("data")?.optString("token")
                    if (!newToken.isNullOrBlank() && newToken != "null") {
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
        if (message != null && ::status.isInitialized) status.text = message
    }

    override fun onDestroy() {
        disconnect(null)
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}