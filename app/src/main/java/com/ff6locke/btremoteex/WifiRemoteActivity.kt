package com.ff6locke.btremoteex

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import com.ff6locke.btremoteex.tvremote.PairingManager
import com.ff6locke.btremoteex.tvremote.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiRemoteActivity : Activity() {
    private val client by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), java.security.SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
    private var socket: WebSocket? = null
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var target: Spinner
    private lateinit var samsungControls: LinearLayout
    private lateinit var rocktekInfo: TextView
    private lateinit var connectButton: Button
    private lateinit var pairingCode: EditText
    private lateinit var confirmPairButton: Button
    private val pairingManager = PairingManager()
    private var remoteSession: RemoteSession? = null
    private var rocktekConnected = false
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        val padSize = dp(if (landscape) 72 else 68)
        val gap = dp(4)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(16), dp(8), dp(4))
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
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(20, 36, 58))
                setStroke(dp(1), Color.rgb(78, 125, 190))
            }
            val deviceNames = listOf("TV", "TVBOX")
            adapter = object : ArrayAdapter<String>(this@WifiRemoteActivity, android.R.layout.simple_spinner_item, deviceNames) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val label = (convertView as? TextView) ?: TextView(this@WifiRemoteActivity)
                    label.text = getItem(position)
                    label.setTextColor(Color.WHITE)
                    label.textSize = 14f
                    label.typeface = Typeface.DEFAULT_BOLD
                    label.gravity = Gravity.CENTER_VERTICAL
                    label.setPadding(dp(10), 0, dp(6), 0)
                    label.maxLines = 1
                    return label
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val label = (convertView as? TextView) ?: TextView(this@WifiRemoteActivity)
                    label.text = getItem(position)
                    label.setTextColor(Color.WHITE)
                    label.textSize = 14f
                    label.gravity = Gravity.CENTER_VERTICAL
                    label.setPadding(dp(12), dp(10), dp(12), dp(10))
                    label.setBackgroundColor(Color.rgb(20, 36, 58))
                    return label
                }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    disconnect(null)
                    showTarget(position)
                }
            }
        }
        connectionRow.addView(target, LinearLayout.LayoutParams(0, dp(44), 1.0f).apply { rightMargin = gap })
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
        connectionRow.addView(ipInput, LinearLayout.LayoutParams(0, dp(44), 1.55f).apply { rightMargin = gap })
        val ipListButton = Button(this).apply {
            text = "▼"
            buttonStyle(this)
            textSize = 14f
            setOnClickListener { showIpList() }
        }
        connectionRow.addView(ipListButton, LinearLayout.LayoutParams(dp(42), dp(44)).apply { rightMargin = gap })
        connectButton = Button(this).apply {
            text = "CONNECT"
            buttonStyle(this)
            textSize = 13f
            setOnClickListener { connectSelected() }
        }
        connectionRow.addView(connectButton, LinearLayout.LayoutParams(0, dp(44), 1.05f))
        root.addView(TextView(this).apply {
            text = "SELECT DEVICE  •  TV / TVBOX"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(6) })
        root.addView(connectionRow, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(2) })

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
            samsungControls.addView(dpad, LinearLayout.LayoutParams(padSize * 3 + dp(8), -2).apply { rightMargin = dp(8) })
            samsungControls.addView(actions, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            samsungControls.addView(dpad, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            samsungControls.addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(samsungControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        // Wi-Fi control modes. These controls use the active Samsung/Android TV
        // connection; pointer motion is translated to directional navigation because
        // the Samsung WebSocket and Android TV Remote protocols do not expose HID mouse reports.
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val touchPanel = TextView(this).apply {
            text = "TOUCHPAD\nSwipe to navigate • Tap for OK\nTwo-finger swipe is not available on this TV protocol"
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.rgb(20, 43, 73)); setStroke(dp(2), Color.rgb(78, 125, 190)) }
        }
        var touchDownX = 0f
        var touchDownY = 0f
        touchPanel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchDownX = event.x; touchDownY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (kotlin.math.abs(dx) < dp(24) && kotlin.math.abs(dy) < dp(24)) sendSamsungKey("KEY_ENTER")
                    else if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) sendSamsungKey(if (dx > 0) "KEY_RIGHT" else "KEY_LEFT")
                    else sendSamsungKey(if (dy > 0) "KEY_DOWN" else "KEY_UP")
                    true
                }
                else -> true
            }
        }
        val keyboardPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val keyboardInput = EditText(this).apply {
            hint = "Type text for the connected TV"; setSingleLine(false); minLines = 2
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); textSize = 17f
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(20, 36, 58)); setStroke(dp(1), Color.rgb(78, 125, 190)) }
        }
        keyboardPanel.addView(keyboardInput, LinearLayout.LayoutParams(-1, dp(90)))
        keyboardPanel.addView(Button(this).apply {
            text = "SEND TEXT"; buttonStyle(this); textSize = 14f
            setOnClickListener {
                val value = keyboardInput.text.toString()
                if (value.isNotEmpty()) {
                    val session = remoteSession
                    if (target.selectedItemPosition == 1 && session?.isReady == true) {
                        try { session.sendText(value); status.text = "Text sent to RockTek GX1." }
                        catch (e: Exception) { status.text = "Text input failed: ${e.message}" }
                    } else {
                        status.text = "Samsung TV text entry depends on its on-screen keyboard; use the TV keyboard for text."
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })

        val airPanel = TextView(this).apply {
            text = "AIR MOUSE\nMove your phone to send directional navigation.\nThis is motion navigation, not a free-moving pointer."
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.rgb(20, 43, 73)); setStroke(dp(2), Color.rgb(78, 125, 190)) }
        }
        var lastMotionKeyAt = 0L
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gyroListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                if (airPanel.visibility != View.VISIBLE || !::status.isInitialized) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastMotionKeyAt < 420) return
                val x = event.values[0]; val y = event.values[1]
                val key = if (kotlin.math.abs(x) > kotlin.math.abs(y) && kotlin.math.abs(x) > 1.35f)
                    if (x > 0) "KEY_LEFT" else "KEY_RIGHT"
                else if (kotlin.math.abs(y) > 1.35f) if (y > 0) "KEY_DOWN" else "KEY_UP" else null
                if (key != null) { sendSamsungKey(key); lastMotionKeyAt = now }
            }
        }
        fun selectWifiMode(mode: Int) {
            samsungControls.visibility = if (mode == 0) View.VISIBLE else View.GONE
            touchPanel.visibility = if (mode == 1) View.VISIBLE else View.GONE
            airPanel.visibility = if (mode == 2) View.VISIBLE else View.GONE
            keyboardPanel.visibility = if (mode == 3) View.VISIBLE else View.GONE
            if (mode == 2) {
                if (gyro == null) { status.text = "This phone has no gyroscope; Air Mouse motion mode is unavailable."; return }
                sensorManager.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_GAME)
            } else sensorManager.unregisterListener(gyroListener)
        }
        listOf("D-PAD", "TOUCHPAD", "AIR MOUSE", "KEYBOARD").forEachIndexed { index, label ->
            modeRow.addView(Button(this).apply {
                text = label; textSize = 10f; isAllCaps = false; setTextColor(Color.WHITE)
                setOnClickListener { selectWifiMode(index) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        root.addView(modeRow, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(touchPanel, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(5) })
        root.addView(airPanel, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(5) })
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        touchPanel.visibility = View.GONE
        airPanel.visibility = View.GONE
        keyboardPanel.visibility = View.GONE

        rocktekInfo = TextView(this).apply {
            text = "RockTek GX1 uses Android TV secure pairing. Enter its IP address, tap PAIR, then enter the 6-character code shown on your TV."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        root.addView(rocktekInfo, LinearLayout.LayoutParams(-1, -2))
        pairingCode = EditText(this).apply {
            hint = "6-character TV pairing code"
            setSingleLine(true)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(20, 36, 58))
                setStroke(dp(1), Color.rgb(78, 125, 190))
            }
            visibility = View.GONE
        }
        root.addView(pairingCode, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4) })
        confirmPairButton = Button(this).apply {
            text = "CONFIRM PAIRING CODE"
            buttonStyle(this)
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { finishRocktekPairing() }
        }
        root.addView(confirmPairButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(scroll)
        ipInput.setText(getSharedPreferences("wifi_remote", MODE_PRIVATE).getString("samsung_ip",
            getSharedPreferences("wifi_remote", MODE_PRIVATE).getString("rocktek_ip", "")))
        target.setSelection(0)
        showTarget(0)
    }
    private fun rememberIp(ip: String) {
        if (ip.isBlank()) return
        val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
        val history = (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet()
        history.add(ip)
        prefs.edit()
            .putStringSet("known_ips", history)
            .putString(if (target.selectedItemPosition == 0) "samsung_ip" else "rocktek_ip", ip)
            .apply()
    }

    private fun showIpList() {
        val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
        val ips = (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet()
        prefs.all.keys.forEach { key ->
            when {
                key == "samsung_ip" || key == "rocktek_ip" -> prefs.getString(key, null)?.let { ips.add(it) }
                key.startsWith("samsung_token_") -> ips.add(key.removePrefix("samsung_token_"))
                key.startsWith("rocktek_cert_") -> ips.add(key.removePrefix("rocktek_cert_"))
            }
        }
        if (ipInput.text.toString().trim().isNotEmpty()) ips.add(ipInput.text.toString().trim())
        if (ips.isEmpty()) {
            status.text = "No saved IP addresses yet. Enter an IP and connect first."
            return
        }
        val sorted = ips.filter { it.isNotBlank() }.sorted()
        android.app.AlertDialog.Builder(this)
            .setTitle("Select TV IP")
            .setItems(sorted.toTypedArray()) { _, which ->
                ipInput.setText(sorted[which])
                ipInput.setSelection(ipInput.text.length)
                rememberIp(sorted[which])
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showTarget(position: Int) {
        val savedIp = getSharedPreferences("wifi_remote", MODE_PRIVATE)
            .getString(if (position == 0) "samsung_ip" else "rocktek_ip", null)
        if (!savedIp.isNullOrBlank()) ipInput.setText(savedIp)
        val samsung = position == 0
        val ip = ipInput.text.toString().trim()
        val paired = !samsung && getSharedPreferences("wifi_remote", MODE_PRIVATE).getBoolean("rocktek_paired_$ip", false)
        samsungControls.visibility = if (samsung || rocktekConnected) View.VISIBLE else View.GONE
        rocktekInfo.visibility = if (samsung) View.GONE else View.VISIBLE
        pairingCode.visibility = View.GONE
        confirmPairButton.visibility = View.GONE
        connectButton.text = if (samsung) "CONNECT TV" else if (paired) "CONNECT TVBOX" else "PAIR TVBOX"
        if (!samsung) rocktekInfo.text = if (paired) "RockTek GX1 is paired. Tap CONNECT to open the remote." else "RockTek GX1 uses Android TV secure pairing. Enter its IP address, tap PAIR, then enter the 6-character code shown on your TV."
        status.text = if (samsung) "Samsung Smart TV selected." else "RockTek GX1 / Android TV selected."
    }

    private fun connectSelected() {
        val ip = ipInput.text.toString().trim()
        if (ip.isBlank()) { status.text = "Enter the device IP address."; return }
        rememberIp(ip)
        if (target.selectedItemPosition == 1) {
            val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
            if (prefs.getBoolean("rocktek_paired_$ip", false)) {
                val fingerprint = prefs.getString("rocktek_cert_$ip", null)
                if (fingerprint.isNullOrBlank()) { status.text = "Pairing record missing. Pair the GX1 again."; return }
                connectRocktekRemote(ip, fingerprint)
            } else {
                beginRocktekPairing(ip)
            }
            return
        }
        disconnect(null)
        connectSamsung(ip, secure = true)

    }

    private fun connectSamsung(ip: String, secure: Boolean) {
        val savedToken = getSharedPreferences("wifi_remote", MODE_PRIVATE).getString("samsung_token_$ip", null)
        val appName = android.util.Base64.encodeToString("BTRemoteEX".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val scheme = if (secure) "wss" else "ws"
        val port = if (secure) 8002 else 8001
        val tokenPart = if (savedToken.isNullOrBlank()) "" else "&token=${URLEncoder.encode(savedToken, "UTF-8")}"
        val request = Request.Builder()
            .url("$scheme://$ip:$port/api/v2/channels/samsung.remote.control?name=${URLEncoder.encode(appName, "UTF-8")}$tokenPart")
            .build()
        status.text = if (secure) "Trying secure Samsung connection (8002)…" else "Trying Samsung compatibility connection (8001)…"
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { status.text = "Samsung link opened. Accept the access prompt on your TV if shown." }
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
                        else if (event == "ms.channel.unauthorized") status.text = "TV denied access. Allow BTRemoteEX in the TV device notification/list, then reconnect."
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (secure) {
                    socket = null
                    runOnUiThread { connectSamsung(ip, secure = false) }
                } else {
                    runOnUiThread { status.text = "Samsung connection failed on 8002 and 8001: ${t.message ?: "TV closed the connection"}" }
                    socket = null
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { status.text = "Samsung TV disconnected (code $code)." }
                socket = null
            }
        })
    }

    private fun beginRocktekPairing(ip: String) {
        disconnect(null)
        pairingCode.visibility = View.GONE
        confirmPairButton.visibility = View.GONE
        status.text = "Starting secure pairing with RockTek GX1…"
        activityScope.launch {
            try {
                val started = withContext(Dispatchers.IO) {
                    pairingManager.start(ip, "BTRemoteEX")
                }
                rocktekInfo.text = "Pairing request sent to ${started.serverName ?: "RockTek GX1"}. Check the TV screen for its 6-character code."
                pairingCode.visibility = View.VISIBLE
                confirmPairButton.visibility = View.VISIBLE
                status.text = "Enter the code displayed on the RockTek GX1."
            } catch (e: Exception) {
                status.text = "Pairing failed. Checking GX1 Wi-Fi services…"
                val ports = withContext(Dispatchers.IO) { scanRemotePorts(ip) }
                val open = ports.filterValues { it }.keys
                status.text = if (open.isEmpty()) {
                    "No remote ports detected (6467, 6466, 5555, 8008, 8009). USB debugging is not required for this test; GX1 may not expose a compatible remote service."
                } else {
                    "GX1 responds on port(s): ${open.joinToString()}. We can use this result to select the correct Wi-Fi method."
                }
                rocktekInfo.text = "Pairing error: ${e.message ?: "unknown error"}"
            }
        }
    }

    private fun finishRocktekPairing() {
        val code = pairingCode.text.toString().trim()
        if (code.isBlank()) { status.text = "Enter the 6-character code shown on the TV."; return }
        confirmPairButton.isEnabled = false
        status.text = "Verifying pairing code…"
        activityScope.launch {
            try {
                val paired = withContext(Dispatchers.IO) { pairingManager.finish(code) }
                val ip = ipInput.text.toString().trim()
                getSharedPreferences("wifi_remote", MODE_PRIVATE).edit()
                    .putString("rocktek_ip", ip)
                    .putStringSet("known_ips", ((getSharedPreferences("wifi_remote", MODE_PRIVATE).getStringSet("known_ips", emptySet()) ?: emptySet()) + ip).toMutableSet())
                    .putString("rocktek_cert_${ip}", paired.serverCertFingerprint)
                    .putBoolean("rocktek_paired_${ip}", true)
                    .apply()
                pairingCode.visibility = View.GONE
                confirmPairButton.visibility = View.GONE
                rocktekInfo.text = "RockTek GX1 securely paired. Connecting remote…"
                status.text = "Pairing successful. Connecting to GX1…"
                connectRocktekRemote(ip, paired.serverCertFingerprint)
            } catch (e: Exception) {
                status.text = "Pairing code failed: ${e.message ?: "try again"}"
            } finally {
                confirmPairButton.isEnabled = true
            }
        }
    }

    private fun scanRemotePorts(ip: String): Map<Int, Boolean> {
        val ports = listOf(6467, 6466, 5555, 8008, 8009)
        return ports.associateWith { port ->
            try {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(ip, port), 900)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun connectRocktekRemote(ip: String, fingerprint: String) {
        remoteSession?.close()
        rocktekConnected = false
        samsungControls.visibility = View.GONE
        status.text = "Connecting securely to RockTek GX1…"
        val session = RemoteSession(ip, fingerprint, object : RemoteSession.Listener {
            override fun onReady(session: RemoteSession) {
                runOnUiThread {
                    rocktekConnected = true
                    samsungControls.visibility = View.VISIBLE
                    rocktekInfo.text = "Connected to RockTek GX1. Use the remote buttons below."
                    status.text = "RockTek GX1 connected."
                }
            }
            override fun onClosed(session: RemoteSession, error: RemoteSession.RemoteException?) {
                runOnUiThread {
                    if (remoteSession === session) {
                        rocktekConnected = false
                        samsungControls.visibility = View.GONE
                        status.text = error?.message ?: "RockTek GX1 disconnected."
                    }
                }
            }
            override fun onPowerState(isOn: Boolean) {}
            override fun onVolume(level: Int, max: Int, muted: Boolean) {}
            override fun onCurrentApp(packageName: String) {}
            override fun onDeviceInfo(vendor: String, model: String, appVersion: String) {
                runOnUiThread { rocktekInfo.text = "Connected: $vendor $model".trim() }
            }
            override fun onImeShow(currentText: String) {}
        })
        remoteSession = session
        activityScope.launch {
            try {
                withContext(Dispatchers.IO) { session.connect(activityScope) }
            } catch (e: Exception) {
                runOnUiThread {
                    rocktekConnected = false
                    samsungControls.visibility = View.GONE
                    status.text = "RockTek connection failed: ${e.message ?: "try pairing again"}"
                }
            }
        }
    }

    private fun sendSamsungKey(key: String) {
        if (target.selectedItemPosition == 1) {
            val keyCode = when (key) {
                "KEY_UP" -> 19
                "KEY_DOWN" -> 20
                "KEY_LEFT" -> 21
                "KEY_RIGHT" -> 22
                "KEY_ENTER" -> 66
                "KEY_RETURN" -> 4
                "KEY_HOME" -> 3
                "KEY_MENU" -> 82
                "KEY_VOLUP" -> 24
                "KEY_VOLDOWN" -> 25
                "KEY_MUTE" -> 164
                "KEY_CHUP" -> 166
                "KEY_CHDOWN" -> 167
                "KEY_SOURCE" -> 178
                else -> return
            }
            val session = remoteSession
            if (session == null || !session.isReady) { status.text = "Connect to RockTek GX1 first."; return }
            try { session.sendKey(keyCode, RemoteSession.Direction.SHORT) }
            catch (e: Exception) { status.text = "Could not send key: ${e.message}" }
            return
        }
        val ws = socket
        if (ws == null) { status.text = "Connect to the Samsung TV first."; return }
        val payload = JSONObject().put("method", "ms.remote.control").put("params",
            JSONObject().put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
        if (!ws.send(payload.toString())) status.text = "Could not send key. Reconnect to the TV."
    }

    private fun disconnect(message: String?) {
        socket?.close(1000, "User disconnected")
        socket = null
        remoteSession?.close()
        remoteSession = null
        rocktekConnected = false
        if (message != null && ::status.isInitialized) status.text = message
    }

    override fun onDestroy() {
        disconnect(null)
        pairingManager.cancel()
        activityScope.cancel()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}