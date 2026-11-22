package com.ff6locke.btremoteex
import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.util.concurrent.Executor
import kotlin.math.roundToInt

class MainActivity : Activity() {
 private lateinit var status: TextView
 private var hid: BluetoothHidDevice? = null
 private var connected: BluetoothDevice? = null
 private var registered = false
 private val handler = Handler(Looper.getMainLooper())
 private val adapter by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
 private val cb = object: BluetoothHidDevice.Callback() {
  override fun onAppStatusChanged(device: BluetoothDevice?, plugged: Boolean) { registered=plugged; runOnUiThread { update() } }
  override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
   if(state==BluetoothProfile.STATE_CONNECTED) connected=device
   if(state==BluetoothProfile.STATE_DISCONNECTED && connected==device) connected=null
   runOnUiThread { update() }
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); ui() }
 private fun ui() {
  val root=LinearLayout(this).apply { orientation=1; setPadding(16,16,16,16) }
  status=TextView(this).apply { text="Ready"; textSize=18f }
  root.addView(status)
  root.addView(Button(this).apply { text="Start Bluetooth Mouse"; setOnClickListener { startHid() } })
  val pad=Pad(this).apply { setBackgroundColor(0xFFE7EDF5.toInt()); move={x,y-> send(0,x,y,0)}; tap={click(1)} }
  root.addView(pad,LinearLayout.LayoutParams(-1,0,1f))
  val row=LinearLayout(this)
  row.addView(Button(this).apply { text="Left click"; setOnClickListener { click(1) } },LinearLayout.LayoutParams(0,-2,1f))
  row.addView(Button(this).apply { text="Right click"; setOnClickListener { click(2) } },LinearLayout.LayoutParams(0,-2,1f))
  root.addView(row)
  val sc=LinearLayout(this)
  sc.addView(Button(this).apply { text="Scroll up"; setOnClickListener { send(0,0,0,1) } },LinearLayout.LayoutParams(0,-2,1f))
  sc.addView(Button(this).apply { text="Scroll down"; setOnClickListener { send(0,0,0,-1) } },LinearLayout.LayoutParams(0,-2,1f))
  root.addView(sc)
  root.addView(TextView(this).apply { text="Pair BTRemoteEX from GX1 Bluetooth settings.\nSwipe to move, tap to click."; textSize=15f })
  setContentView(root)
 }
 private fun startHid() {
  if(Build.VERSION.SDK_INT<28) { status.text="Android 9+ required"; return }
  if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)) {
   requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE),23); return
  }
  if(!adapter.isEnabled) { status.text="Turn on phone Bluetooth first"; return }
  try {
   adapter.getProfileProxy(this,object:BluetoothProfile.ServiceListener {
    override fun onServiceConnected(profile:Int, proxy:BluetoothProfile) {
     if(profile!=BluetoothProfile.HID_DEVICE) return
     hid=proxy as BluetoothHidDevice
     val desc=intArrayOf(0x05,0x01,0x09,0x02,0xA1,0x01,0x09,0x01,0xA1,0x00,0x05,0x09,0x19,0x01,0x29,0x05,0x15,0x00,0x25,0x01,0x95,0x05,0x75,0x01,0x81,0x02,0x95,0x01,0x75,0x03,0x81,0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0).map{it.toByte()}.toByteArray()
     val sdp=BluetoothHidDeviceAppSdpSettings("BTRemoteEX","Air Mouse","Android",BluetoothHidDevice.SUBCLASS1_MOUSE,desc)
     hid?.registerApp(sdp,null,null,Executor { command -> handler.post(command) },cb)
     runOnUiThread { status.text="HID registered. Pair BTRemoteEX from GX1 Bluetooth settings." }
    }
    override fun onServiceDisconnected(profile:Int) { hid=null; registered=false; update() }
   },BluetoothProfile.HID_DEVICE)
  } catch(e:SecurityException) { status.text="Bluetooth permissions denied" }
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==23 && grantResults.isNotEmpty() && grantResults.all{it==PackageManager.PERMISSION_GRANTED}) startHid()
  else if(requestCode==23) status.text="Bluetooth permissions are needed for HID mouse mode"
 }
 private fun send(buttons:Int,x:Int,y:Int,wheel:Int) {
  val h=hid?:return; val d=connected?:return; if(!registered)return
  try { h.sendReport(d,0,byteArrayOf(buttons.toByte(),x.coerceIn(-127,127).toByte(),y.coerceIn(-127,127).toByte(),wheel.toByte())) } catch(_:Exception){}
 }
 private fun click(b:Int){send(b,0,0,0);handler.postDelayed({send(0,0,0,0)},50)}
 private fun update(){status.text=when { connected!=null -> "Connected to " + (connected?.name ?: "Bluetooth host"); registered -> "HID ready — pair from GX1 settings"; else -> "Bluetooth HID not registered" }}
 inner class Pad(c:Context):View(c) {
  var move:((Int,Int)->Unit)?=null; var tap:(()->Unit)?=null; private var lx=0f;private var ly=0f;private var drag=false
  override fun onTouchEvent(e:MotionEvent):Boolean { when(e.action){
   MotionEvent.ACTION_DOWN->{lx=e.x;ly=e.y;drag=false;return true}
   MotionEvent.ACTION_MOVE->{val dx=((e.x-lx)*1.4f).roundToInt();val dy=((e.y-ly)*1.4f).roundToInt();if(dx!=0||dy!=0){drag=true;move?.invoke(dx,dy)};lx=e.x;ly=e.y;return true}
   MotionEvent.ACTION_UP->{if(!drag)tap?.invoke();return true}
  };return true }
 }
}