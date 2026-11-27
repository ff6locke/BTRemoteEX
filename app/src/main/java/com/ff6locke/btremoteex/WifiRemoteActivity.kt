package com.ff6locke.btremoteex

import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.net.wifi.WifiManager
import java.net.InetSocketAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        heading.addView(Button(this).apply {
            text = "Switch to Bluetooth"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                disconnect(null)
                startActivity(Intent(this@WifiRemoteActivity, MainActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(dp(150), dp(38)))
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(38)))
        status = TextView(this).apply {
            text = "Connect both devices to the same Wi-Fi."
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(Color.LTGRAY)
            maxLines = 5
            isSingleLine = false
            setTextIsSelectable(true)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(72)))

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
            val deviceNames = listOf("TV", "BOX")
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
        connectionRow.addView(target, LinearLayout.LayoutParams(0, dp(44), 1.25f).apply { rightMargin = gap })
        ipInput = EditText(this).apply {
            hint = if (target.selectedItemPosition == 0) "TV IP address" else "BOX IP address"
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
        connectionRow.addView(ipInput, LinearLayout.LayoutParams(0, dp(44), 1.15f).apply { rightMargin = gap })
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
            text = "SELECT DEVICE  •  TV / BOX"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(6) })
        root.addView(connectionRow, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(2) })
        root.addView(Button(this).apply {
            text = "SCAN AGAIN"
            buttonStyle(this)
            textSize = 13f
            setOnClickListener { startIpDiscovery() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })

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
                if (key == "KEY_UP" || key == "KEY_DOWN" || key == "KEY_LEFT" || key == "KEY_RIGHT" || key == "KEY_VOLUP" || key == "KEY_VOLDOWN") {
                    var longPressStarted = false
                    val longPressAction = Runnable {
                        longPressStarted = true
                        sendSamsungKeyHold(key, true)
                    }
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                longPressStarted = false
                                view.isPressed = true
                                view.postDelayed(longPressAction, 350L)
                                true
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                view.removeCallbacks(longPressAction)
                                view.isPressed = false
                                if (longPressStarted) sendSamsungKeyHold(key, false)
                                else sendSamsungKey(key)
                                true
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                view.removeCallbacks(longPressAction)
                                view.isPressed = false
                                if (longPressStarted) sendSamsungKeyHold(key, false)
                                true
                            }
                            else -> true
                        }
                    }
                } else {
                    setOnClickListener { sendSamsungKey(key) }
                }
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
        // Portrait SNES-style gamepad mode.
        val gamepadPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            visibility = View.GONE
        }
        fun gameButton(label: String, key: String, color: Int, width: Int = 64, height: Int = 64): Button {
            return Button(this).apply {
                text = label; textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(color); setStroke(dp(2), Color.rgb(120, 160, 205)) }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { view.isPressed = true; sendSamsungKeyHold(key, true); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; sendSamsungKeyHold(key, false); true }
                        else -> true
                    }
                }
            }
        }
        val gamepadTop = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val gpLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val gpDpad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        gpDpad.addView(gameButton("▲", "KEY_UP", Color.rgb(40, 70, 105)), LinearLayout.LayoutParams(dp(62), dp(58)))
        val gpLR = LinearLayout(this).apply { gravity = Gravity.CENTER }
        gpLR.addView(gameButton("◀", "KEY_LEFT", Color.rgb(40, 70, 105)), LinearLayout.LayoutParams(dp(58), dp(62)))
        gpLR.addView(View(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        gpLR.addView(gameButton("▶", "KEY_RIGHT", Color.rgb(40, 70, 105)), LinearLayout.LayoutParams(dp(58), dp(62)))
        gpDpad.addView(gpLR)
        gpDpad.addView(gameButton("▼", "KEY_DOWN", Color.rgb(40, 70, 105)), LinearLayout.LayoutParams(dp(62), dp(58)))
        gpLeft.addView(gpDpad)
        gamepadTop.addView(gpLeft, LinearLayout.LayoutParams(0, -2, 1f))
        val gpABXY = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val gpXY = LinearLayout(this).apply { gravity = Gravity.CENTER }
        gpXY.addView(gameButton("Y", "KEY_Y", Color.rgb(255, 211, 48)), LinearLayout.LayoutParams(dp(60), dp(60)).apply { rightMargin = dp(8) })
        gpXY.addView(gameButton("X", "KEY_X", Color.rgb(45, 112, 220)), LinearLayout.LayoutParams(dp(60), dp(60)))
        val gpBA = LinearLayout(this).apply { gravity = Gravity.CENTER }
        gpBA.addView(gameButton("B", "KEY_B", Color.rgb(220, 49, 62)), LinearLayout.LayoutParams(dp(60), dp(60)).apply { rightMargin = dp(8) })
        gpBA.addView(gameButton("A", "KEY_A", Color.rgb(45, 160, 91)), LinearLayout.LayoutParams(dp(60), dp(60)))
        gpABXY.addView(gpXY); gpABXY.addView(gpBA, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        gamepadTop.addView(gpABXY, LinearLayout.LayoutParams(0, -2, 1f))
        val gpShoulders = LinearLayout(this).apply { gravity = Gravity.CENTER }
        gpShoulders.addView(gameButton("L", "KEY_L1", Color.rgb(112, 105, 135), 78, 42), LinearLayout.LayoutParams(dp(94), dp(44)).apply { rightMargin = dp(36) })
        gpShoulders.addView(gameButton("R", "KEY_R1", Color.rgb(112, 105, 135), 78, 42), LinearLayout.LayoutParams(dp(94), dp(44)))
        gamepadPanel.addView(gpShoulders, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        gamepadPanel.addView(gamepadTop, LinearLayout.LayoutParams(-1, -2))
        val gpStartSelect = LinearLayout(this).apply { gravity = Gravity.CENTER }
        gpStartSelect.addView(gameButton("SELECT", "KEY_SELECT", Color.rgb(70, 75, 90), 84, 44), LinearLayout.LayoutParams(dp(92), dp(48)).apply { rightMargin = dp(24) })
        gpStartSelect.addView(gameButton("START", "KEY_START", Color.rgb(70, 75, 90), 84, 44), LinearLayout.LayoutParams(dp(92), dp(48)))
        gamepadPanel.addView(gpStartSelect, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
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
                        try { session.sendText(value); status.text = "Text sent to BOX." }
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
            gamepadPanel.visibility = if (mode == 4) View.VISIBLE else View.GONE
            if (mode == 2) {
                if (gyro == null) { status.text = "This phone has no gyroscope; Air Mouse motion mode is unavailable."; return }
                sensorManager.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_GAME)
            } else sensorManager.unregisterListener(gyroListener)
        }
        listOf("D-PAD", "TOUCHPAD", "AIR MOUSE", "KEYBOARD", "GAMEPAD").forEachIndexed { index, label ->
            modeRow.addView(Button(this).apply {
                text = label; textSize = 10f; isAllCaps = false; setTextColor(Color.WHITE)
                setOnClickListener { selectWifiMode(index) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        root.addView(modeRow, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(touchPanel, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(5) })
        root.addView(gamepadPanel, LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(5) })
        root.addView(airPanel, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(5) })
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        touchPanel.visibility = View.GONE
        airPanel.visibility = View.GONE
        keyboardPanel.visibility = View.GONE

        rocktekInfo = TextView(this).apply {
            text = "BOX uses Android TV secure pairing. Enter its IP address, tap PAIR, then enter the 6-character code shown on your TV."
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
        startIpDiscovery()
    }
    private fun rememberIp(ip: String) {
        if (ip.isBlank()) return
        val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
        val history = (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet()
        val ignored = (prefs.getStringSet("ignored_ips", emptySet()) ?: emptySet()).toMutableSet()
        history.add(ip)
        ignored.remove(ip)
        prefs.edit()
            .putStringSet("known_ips", history)
            .putStringSet("ignored_ips", ignored)
            .putString(if (target.selectedItemPosition == 0) "samsung_ip" else "rocktek_ip", ip)
            .apply()
    }

    private fun startIpDiscovery() {
        ipInput.setText("")
        status.text = "Scanning local Wi-Fi for TV and BOX IP addresses…"
        activityScope.launch {
            val found = withContext(Dispatchers.IO) { discoverLocalDevices() }
            if (found.isNotEmpty()) {
                val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
                val history = (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet()
                history.addAll(found)
                prefs.edit().putStringSet("known_ips", history).apply()
                ipInput.setText(found.firstOrNull().orEmpty())
                status.text = "Detected device IP address(es): ${found.joinToString()}. Tap ▼ to choose."
            } else {
                ipInput.setText("")
                status.text = "No TV/BOX IP detected. IP field cleared; enter it manually if needed."
            }
        }
    }

    private suspend fun discoverLocalDevices(): List<String> = coroutineScope {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return@coroutineScope emptyList()
        val dhcp = wifi.dhcpInfo ?: return@coroutineScope emptyList()
        // WifiInfo/DhcpInfo store IPv4 addresses in little-endian integer order.
        // Convert to network-order octets before calculating the subnet; otherwise
        // the scan probes the wrong addresses (for example 193.168.1.0 instead
        // of 192.168.1.x) and automatic discovery always appears empty.
        fun toNetworkLong(address: Int): Long {
            val a = address and 0xff
            val b = (address ushr 8) and 0xff
            val c = (address ushr 16) and 0xff
            val d = (address ushr 24) and 0xff
            return (a.toLong() shl 24) or (b.toLong() shl 16) or
                (c.toLong() shl 8) or d.toLong()
        }
        val localIp = dhcp.ipAddress
        if (localIp == 0) return@coroutineScope emptyList()
        val localAddress = toNetworkLong(localIp)
        val maskAddress = if (dhcp.netmask != 0) toNetworkLong(dhcp.netmask) else 0xFFFFFF00L
        val networkBase = localAddress and maskAddress
        val hosts = (1L..254L).map { host ->
            val address = networkBase or host
            if (address == localAddress) null else
                "${(address ushr 24) and 0xff}.${(address ushr 16) and 0xff}.${(address ushr 8) and 0xff}.${address and 0xff}"
        }.filterNotNull()
        // Android TV, Samsung TV, and common remote-control service ports.
        val ports = listOf(6466, 6467, 8001, 8002, 8008, 8009, 5555)
        hosts.map { host -> async {
            for (port in ports) {
                try {
                    Socket().use { s -> s.connect(InetSocketAddress(host, port), 160); return@async host }
                } catch (_: Exception) {}
            }
            null
        } }.awaitAll().filterNotNull().distinct().sorted()
    }

    private fun showIpList() {
        val title = if (target.selectedItemPosition == 0) "Saved TV / Device IPs" else "Saved BOX / Device IPs"
        val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
        val ips = (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet()
        prefs.getString(if (target.selectedItemPosition == 0) "samsung_ip" else "rocktek_ip", null)?.let { ips.add(it) }
        ipInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { ips.add(it) }
        val sorted = ips.filter { it.isNotBlank() }.distinct().sorted()
        if (sorted.isEmpty()) {
            status.text = "No saved devices. Enter an IP address or wait for device discovery."
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply {
            addView(list, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("CLOSE", null)
            .create()

        lateinit var bluetoothButton: Button
        bluetoothButton = Button(this).apply {
            text = "SHOW PAIRED BLUETOOTH DEVICES"
            textSize = 12f
            isAllCaps = false
            buttonStyle(this)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
                    status.text = "Allow Nearby devices permission, then open the device list again."
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val paired = try { adapter?.bondedDevices?.toList().orEmpty() } catch (_: SecurityException) { emptyList() }
                val heading = TextView(this@WifiRemoteActivity).apply {
                    text = if (paired.isEmpty()) "No paired Bluetooth devices found." else "PAIRED BLUETOOTH DEVICES (${paired.size})"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.BLACK)
                    setPadding(dp(8), dp(12), dp(8), dp(6))
                }
                list.addView(heading, 0)
                paired.sortedBy { try { it.name ?: it.address } catch (_: SecurityException) { "" } }.forEach { device ->
                    val label = try { device.name?.takeIf { it.isNotBlank() } ?: "Unnamed Bluetooth device" } catch (_: SecurityException) { "Bluetooth device" }
                    val address = try { device.address } catch (_: SecurityException) { "Address unavailable" }
                    val rowText = TextView(this@WifiRemoteActivity).apply {
                        text = "$label  •  $address"
                        textSize = 13f
                        setTextColor(Color.rgb(20, 55, 95))
                        setPadding(dp(8), dp(7), dp(8), dp(7))
                    }
                    list.addView(rowText, 0)
                }
                bluetoothButton.isEnabled = false
                bluetoothButton.text = "BLUETOOTH DEVICES SHOWN"
            }
        }
        list.addView(bluetoothButton, LinearLayout.LayoutParams(-1, dp(44)))
        list.addView(View(this).apply { setBackgroundColor(Color.rgb(55, 70, 90)) },
            LinearLayout.LayoutParams(-1, dp(1)))

        sorted.forEach { ip ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val details = TextView(this).apply {
                text = "Looking up device name…\\n$ip"
                textSize = 14f
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(8), dp(5), dp(5), dp(5))
                setOnClickListener {
                    ipInput.setText(ip)
                    ipInput.setSelection(ipInput.text.length)
                    rememberIp(ip)
                    dialog.dismiss()
                }
            }
            row.addView(details, LinearLayout.LayoutParams(0, dp(58), 1f))
            val delete = Button(this).apply {
                text = "DELETE"
                textSize = 11f
                isAllCaps = false
                buttonStyle(this)
                setOnClickListener {
                    val currentPrefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
                    val history = (currentPrefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet()
                    history.remove(ip)
                    val hidden = (currentPrefs.getStringSet("ignored_ips", emptySet()) ?: emptySet()).toMutableSet()
                    hidden.remove(ip)
                    val editor = currentPrefs.edit()
                        .putStringSet("known_ips", history)
                        .putStringSet("ignored_ips", hidden)
                    if (currentPrefs.getString("samsung_ip", null) == ip) editor.remove("samsung_ip")
                    if (currentPrefs.getString("rocktek_ip", null) == ip) editor.remove("rocktek_ip")
                    editor.apply()
                    if (ipInput.text.toString().trim() == ip) ipInput.setText("")
                    list.removeView(row)
                    status.text = "Removed $ip from the device list."
                    if (list.childCount == 0) dialog.dismiss()
                }
            }
            row.addView(delete, LinearLayout.LayoutParams(dp(82), dp(42)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            list.addView(View(this).apply { setBackgroundColor(Color.rgb(55, 70, 90)) },
                LinearLayout.LayoutParams(-1, dp(1)))

            activityScope.launch {
                val name = withContext(Dispatchers.IO) {
                    try {
                        val host = java.net.InetAddress.getByName(ip).canonicalHostName
                        if (host.isNullOrBlank() || host == ip || host.matches(Regex("""\\d+\\.\\d+\\.\\d+\\.\\d+"""))) null
                        else host.removeSuffix(".").substringBefore(".")
                    } catch (_: Exception) { null }
                }
                if (details.parent != null) {
                    details.text = "${name ?: "Device name unavailable"}\\n$ip"
                }
            }
        }
        dialog.show()
    }

    private fun showTarget(position: Int) {
        val savedIp = getSharedPreferences("wifi_remote", MODE_PRIVATE)
            .getString(if (position == 0) "samsung_ip" else "rocktek_ip", null)
        ipInput.hint = if (position == 0) "TV IP address" else "BOX IP address"
        if (!savedIp.isNullOrBlank()) ipInput.setText(savedIp)
        val samsung = position == 0
        val ip = ipInput.text.toString().trim()
        val paired = !samsung && getSharedPreferences("wifi_remote", MODE_PRIVATE).getBoolean("rocktek_paired_$ip", false)
        samsungControls.visibility = if (samsung || rocktekConnected) View.VISIBLE else View.GONE
        rocktekInfo.visibility = if (samsung) View.GONE else View.VISIBLE
        pairingCode.visibility = View.GONE
        confirmPairButton.visibility = View.GONE
        connectButton.text = if (samsung) "CONNECT TV" else if (paired) "CONNECT BOX" else "PAIR BOX"
        if (!samsung) rocktekInfo.text = if (paired) "BOX is paired. Tap CONNECT to open the remote." else "BOX uses Android TV secure pairing. Enter its IP address, tap PAIR, then enter the 6-character code shown on your TV."
        status.text = if (samsung) "Samsung Smart TV selected." else "BOX / Android TV selected."
    }

    private fun connectSelected() {
        val ip = ipInput.text.toString().trim()
        if (ip.isBlank()) { status.text = "Enter the device IP address."; return }
        rememberIp(ip)
        if (target.selectedItemPosition == 1) {
            val prefs = getSharedPreferences("wifi_remote", MODE_PRIVATE)
            if (prefs.getBoolean("rocktek_paired_$ip", false)) {
                val fingerprint = prefs.getString("rocktek_cert_$ip", null)
                if (fingerprint.isNullOrBlank()) { status.text = "Pairing record missing. Pair the TV again."; return }
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
        status.text = "Starting secure pairing with BOX…"
        activityScope.launch {
            try {
                val started = withContext(Dispatchers.IO) {
                    pairingManager.start(ip, "BTRemoteEX")
                }
                rocktekInfo.text = "Pairing request sent to ${started.serverName ?: "BOX"}. Check the TV screen for its 6-character code."
                pairingCode.visibility = View.VISIBLE
                confirmPairButton.visibility = View.VISIBLE
                status.text = "Enter the code displayed on the BOX."
            } catch (e: Exception) {
                status.text = "Pairing failed. Checking TV Wi-Fi services…"
                val ports = withContext(Dispatchers.IO) { scanRemotePorts(ip) }
                val open = ports.filterValues { it }.keys
                status.text = if (open.isEmpty()) {
                    "No remote ports detected (6467, 6466, 5555, 8008, 8009). USB debugging is not required for this test; The TV may not expose a compatible remote service."
                } else {
                    "TV responds on port(s): ${open.joinToString()}. We can use this result to select the correct Wi-Fi method."
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
                rocktekInfo.text = "BOX securely paired. Connecting remote…"
                status.text = "Pairing successful. Connecting to TV…"
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
        status.text = "Connecting securely to BOX…"
        val session = RemoteSession(ip, fingerprint, object : RemoteSession.Listener {
            override fun onReady(session: RemoteSession) {
                runOnUiThread {
                    rocktekConnected = true
                    samsungControls.visibility = View.VISIBLE
                    rocktekInfo.text = "Connected to BOX. Use the remote buttons below."
                    status.text = "BOX connected."
                }
            }
            override fun onClosed(session: RemoteSession, error: RemoteSession.RemoteException?) {
                runOnUiThread {
                    if (remoteSession === session) {
                        rocktekConnected = false
                        samsungControls.visibility = View.GONE
                        status.text = (error?.message ?: "BOX disconnected.") + " Reconnecting…"
                        // Keep the selected BOX paired and recover automatically after
                        // an unexpected socket close. A deliberate disconnect clears
                        // remoteSession first, so it will not enter this retry path.
                        activityScope.launch {
                            kotlinx.coroutines.delay(2000)
                            if (remoteSession === session && !rocktekConnected) {
                                connectRocktekRemote(ip, fingerprint)
                            }
                        }
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
                    status.text = "TV connection failed: ${e.message ?: "try pairing again"}"
                }
            }
        }
    }

    private fun sendSamsungKeyHold(key: String, pressed: Boolean) {
        if (target.selectedItemPosition == 1) {
            val keyCode = when (key) {
                "KEY_UP" -> 19
                "KEY_DOWN" -> 20
                "KEY_LEFT" -> 21
                "KEY_RIGHT" -> 22
                "KEY_VOLUP" -> 24
                "KEY_VOLDOWN" -> 25
                "KEY_L1" -> 102
                "KEY_R1" -> 103
                else -> return
            }
            val session = remoteSession
            if (session == null || !session.isReady) {
                if (pressed) status.text = "Connect to BOX first."
                return
            }
            // START_LONG makes seek/volume repeat while held; END_LONG stops on release.
            try {
                session.sendKey(
                    keyCode,
                    if (pressed) RemoteSession.Direction.START_LONG else RemoteSession.Direction.END_LONG
                )
            } catch (e: Exception) {
                status.text = "Could not send key: ${e.message}"
            }
            return
        }
        val ws = socket
        if (ws == null) {
            if (pressed) status.text = "Connect to the TV first."
            return
        }
        val payload = JSONObject().put("method", "ms.remote.control").put("params",
            JSONObject().put("Cmd", if (pressed) "Press" else "Release")
                .put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
        if (!ws.send(payload.toString()) && pressed) status.text = "Could not send key. Reconnect to the TV."
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
            if (session == null || !session.isReady) { status.text = "Connect to BOX first."; return }
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