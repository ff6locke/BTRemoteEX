package com.ff6locke.btremoteex
import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
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
 private lateinit var status:TextView
 private lateinit var speedLabel:TextView
 private lateinit var entry:EditText
 private lateinit var modeButton:Button
 private lateinit var airPanel:LinearLayout
 private lateinit var tvPanel:LinearLayout
 private var airMode=false
 private var sensorManager:SensorManager?=null
 private var gyro:Sensor?=null
 private var lastGyroTime=0L
 private val gyroListener=object:android.hardware.SensorEventListener{
  override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
  override fun onSensorChanged(event:android.hardware.SensorEvent){
   if(!airMode||event.sensor.type!=Sensor.TYPE_GYROSCOPE)return
   val now=event.timestamp
   if(lastGyroTime!=0L){
    val dt=((now-lastGyroTime)/1_000_000_000.0).toFloat().coerceIn(0f,0.05f)
    val dx=(-event.values[1]*dt*900f*sensitivity).roundToInt()
    val dy=(event.values[0]*dt*900f*sensitivity).roundToInt()
    if(dx!=0||dy!=0)mouse(0,dx,dy,0)
   }
   lastGyroTime=now
  }
 }
 private var hid:BluetoothHidDevice?=null
 private var connected:BluetoothDevice?=null
 private var registered=false
 private var sensitivity=1.4f
 private val handler=Handler(Looper.getMainLooper())
 private val adapter by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
 private val cb=object:BluetoothHidDevice.Callback(){
  override fun onAppStatusChanged(device:BluetoothDevice?,plugged:Boolean){registered=plugged;runOnUiThread{update()}}
  override fun onConnectionStateChanged(device:BluetoothDevice,state:Int){
   if(state==BluetoothProfile.STATE_CONNECTED)connected=device
   if(state==BluetoothProfile.STATE_DISCONNECTED&&connected==device)connected=null
   runOnUiThread{update()}
  }
 }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ui()}
 private fun ui(){
  val root=LinearLayout(this).apply{orientation=1;setPadding(16,16,16,16)}
  status=TextView(this).apply{text="Ready";textSize=18f};root.addView(status)
  root.addView(Button(this).apply{text="Start Bluetooth HID";setOnClickListener{startHid()}})
  modeButton=Button(this).apply{text="Switch to Air Mouse";setOnClickListener{setMode(!airMode)}}
  root.addView(modeButton)
  speedLabel=TextView(this).apply{text="Control sensitivity: 1.4x"};root.addView(speedLabel)
  root.addView(SeekBar(this).apply{max=25;progress=9;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(sb:SeekBar,p:Int,fromUser:Boolean){sensitivity=0.5f+p*0.25f;speedLabel.text="Control sensitivity: %.2fx".format(sensitivity)}
   override fun onStartTrackingTouch(sb:SeekBar){}
   override fun onStopTrackingTouch(sb:SeekBar){}
  })})
  airPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER}
  airPanel.addView(TextView(this).apply{text="AIR MOUSE MODE";textSize=20f;gravity=android.view.Gravity.CENTER})
  airPanel.addView(TextView(this).apply{text="Move your phone gently to move the pointer. Keep the phone level; use the buttons below to click.";textSize=15f;gravity=android.view.Gravity.CENTER;setPadding(12,18,12,18)})
  val mouseButtons=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER}
  mouseButtons.addView(Button(this).apply{text="Left click";setOnClickListener{click(1)}},LinearLayout.LayoutParams(0,64,1f))
  mouseButtons.addView(Button(this).apply{text="Right click";setOnClickListener{click(2)}},LinearLayout.LayoutParams(0,64,1f))
  airPanel.addView(mouseButtons)
  airPanel.addView(Button(this).apply{text="Re-centre";setOnClickListener{lastGyroTime=0L;Toast.makeText(this@MainActivity,"Air mouse re-centred",Toast.LENGTH_SHORT).show()}})
  root.addView(airPanel,LinearLayout.LayoutParams(-1,0,1f))
  tvPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER}
  tvPanel.addView(TextView(this).apply{text="TV D-PAD MODE";textSize=18f;gravity=android.view.Gravity.CENTER;setPadding(0,8,0,8)})
  fun navButton(label:String,usage:Int):Button=Button(this).apply{text=label;textSize=25f;minHeight=76;setOnClickListener{key(0,usage)}}
  tvPanel.addView(navButton("▲",0x52),LinearLayout.LayoutParams(148,84))
  val middle=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER}
  middle.addView(navButton("◀",0x50),LinearLayout.LayoutParams(0,84,1f))
  middle.addView(navButton("OK",0x28),LinearLayout.LayoutParams(0,84,1f))
  middle.addView(navButton("▶",0x4F),LinearLayout.LayoutParams(0,84,1f))
  tvPanel.addView(middle,LinearLayout.LayoutParams(-1,84))
  tvPanel.addView(navButton("▼",0x51),LinearLayout.LayoutParams(148,84))
  val systemKeys=LinearLayout(this).apply{orientation=0}
  systemKeys.addView(Button(this).apply{text="Back";setOnClickListener{consumer(0x0224)}},LinearLayout.LayoutParams(0,64,1f))
  systemKeys.addView(Button(this).apply{text="Home";setOnClickListener{consumer(0x0223)}},LinearLayout.LayoutParams(0,64,1f))
  systemKeys.addView(Button(this).apply{text="Menu";setOnClickListener{consumer(0x0040)}},LinearLayout.LayoutParams(0,64,1f))
  tvPanel.addView(systemKeys)
  root.addView(tvPanel,LinearLayout.LayoutParams(-1,0,1f))
  entry=EditText(this).apply{hint="Type text to send to GX1";setSingleLine(true)};root.addView(entry)
  root.addView(Button(this).apply{text="Send text";setOnClickListener{sendText(entry.text.toString());entry.text.clear()}})
  root.addView(TextView(this).apply{text="Pair/connect from GX1 Bluetooth settings. TV mode sends D-pad keys; Air Mouse mode uses the phone gyroscope.";textSize=13f})
  sensorManager=getSystemService(Context.SENSOR_SERVICE) as SensorManager
  gyro=sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
  setContentView(root)
  setMode(false)
 }
 private fun setMode(useAirMouse:Boolean){
  airMode=useAirMouse
  airPanel.visibility=if(airMode)View.VISIBLE else View.GONE
  tvPanel.visibility=if(airMode)View.GONE else View.VISIBLE
  modeButton.text=if(airMode)"Switch to TV D-pad" else "Switch to Air Mouse"
  lastGyroTime=0L
  if(airMode){
   val s=sensorManager;val g=gyro
   if(g==null){Toast.makeText(this,"This phone has no gyroscope; Air Mouse unavailable",Toast.LENGTH_LONG).show();airMode=false;airPanel.visibility=View.GONE;tvPanel.visibility=View.VISIBLE;modeButton.text="Switch to Air Mouse";return}
   s?.registerListener(gyroListener,g,SensorManager.SENSOR_DELAY_GAME)
  }else sensorManager?.unregisterListener(gyroListener)
 }
 override fun onPause(){super.onPause();sensorManager?.unregisterListener(gyroListener);lastGyroTime=0L}
 override fun onResume(){super.onResume();if(airMode&&gyro!=null)sensorManager?.registerListener(gyroListener,gyro,SensorManager.SENSOR_DELAY_GAME)}
 private fun startHid(){
  if(Build.VERSION.SDK_INT<28){status.text="Android 9+ required";return}
  if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE),23);return}
  if(!adapter.isEnabled){status.text="Turn on phone Bluetooth first";return}
  try{adapter.getProfileProxy(this,object:BluetoothProfile.ServiceListener{
   override fun onServiceConnected(profile:Int,proxy:BluetoothProfile){
    if(profile!=BluetoothProfile.HID_DEVICE)return
    hid=proxy as BluetoothHidDevice
    // Composite descriptor: report 1 = mouse, report 2 = keyboard.
    val descriptor=intArrayOf(
     0x05,0x01,0x09,0x02,0xA1,0x01,0x09,0x01,0xA1,0x00,0x85,0x01,
     0x05,0x09,0x19,0x01,0x29,0x05,0x15,0x00,0x25,0x01,0x95,0x05,0x75,0x01,0x81,0x02,
     0x95,0x01,0x75,0x03,0x81,0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0,
     0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x02,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
     0x95,0x01,0x75,0x08,0x81,0x01,0x19,0x00,0x29,0x65,0x15,0x00,0x25,0x65,0x75,0x08,0x95,0x06,0x81,0x00,0xC0,
     // Consumer controls: report 3, 16-bit usage (Back, Home, Menu).
     0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x03,0x15,0x00,0x26,0xFF,0x03,0x19,0x00,0x2A,0xFF,0x03,0x75,0x10,0x95,0x01,0x81,0x00,0xC0
    ).map{it.toByte()}.toByteArray()
    val sdp=BluetoothHidDeviceAppSdpSettings("BTRemoteEX","Air Mouse + Keyboard","Android",BluetoothHidDevice.SUBCLASS1_MOUSE,descriptor)
    hid?.registerApp(sdp,null,null,Executor{command->handler.post(command)},cb)
    runOnUiThread{status.text="HID registered. Pair/connect from GX1 Bluetooth settings."}
   }
   override fun onServiceDisconnected(profile:Int){hid=null;registered=false;update()}
  },BluetoothProfile.HID_DEVICE)}catch(e:SecurityException){status.text="Bluetooth permissions denied"}
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==23&&grantResults.isNotEmpty()&&grantResults.all{it==PackageManager.PERMISSION_GRANTED})startHid()else if(requestCode==23)status.text="Bluetooth permissions are needed for HID"}
 private fun mouse(b:Int,x:Int,y:Int,w:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,1,byteArrayOf(b.toByte(),x.coerceIn(-127,127).toByte(),y.coerceIn(-127,127).toByte(),w.toByte()))}catch(_:Exception){}}
 private fun click(b:Int){mouse(b,0,0,0);handler.postDelayed({mouse(0,0,0,0)},50)}
 private fun key(mod:Int,k:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,2,byteArrayOf(mod.toByte(),0,k.toByte(),0,0,0,0,0));handler.postDelayed({try{h.sendReport(d,2,ByteArray(8))}catch(_:Exception){}},120)}catch(_:Exception){}}
 private fun consumer(usage:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,3,byteArrayOf((usage and 0xFF).toByte(),((usage shr 8) and 0xFF).toByte()));handler.postDelayed({try{h.sendReport(d,3,byteArrayOf(0,0))}catch(_:Exception){}},120)}catch(_:Exception){}}
 private fun sendText(s:String){if(!registered||connected==null){Toast.makeText(this,"Connect Bluetooth HID to GX1 first",Toast.LENGTH_SHORT).show();return};var t=0L;s.forEach{c->val m=ascii(c);if(m!=null){handler.postDelayed({key(m.first,m.second)},t);t+=85}}}
 private fun ascii(c:Char):Pair<Int,Int>?{val shift=2;return when(c){
  in 'a'..'z'->Pair(0,4+(c-'a'));in 'A'..'Z'->Pair(shift,4+(c-'A'));in '1'..'9'->Pair(0,0x1E+(c-'1'));'0'->Pair(0,0x27);' '->Pair(0,0x2C);'\n','\r'->Pair(0,0x28);'\t'->Pair(0,0x2B)
  '-'->Pair(0,0x2D);'_'->Pair(shift,0x2D);'='->Pair(0,0x2E);'+'->Pair(shift,0x2E);'['->Pair(0,0x2F);'{'->Pair(shift,0x2F);']'->Pair(0,0x30);'}'->Pair(shift,0x30);'\\'->Pair(0,0x31);'|'->Pair(shift,0x31);';'->Pair(0,0x33);':'->Pair(shift,0x33);'\''->Pair(0,0x34);'"'->Pair(shift,0x34);'`'->Pair(0,0x35);'~'->Pair(shift,0x35);','->Pair(0,0x36);'<'->Pair(shift,0x36);'.'->Pair(0,0x37);'>'->Pair(shift,0x37);'/'->Pair(0,0x38);'?'->Pair(shift,0x38)
  '!'->Pair(shift,0x1E);'@'->Pair(shift,0x1F);'#'->Pair(shift,0x20);'$'->Pair(shift,0x21);'%'->Pair(shift,0x22);'^'->Pair(shift,0x23);'&'->Pair(shift,0x24);'*'->Pair(shift,0x25);'('->Pair(shift,0x26);')'->Pair(shift,0x27);else->null}}
 private fun update(){status.text=when{connected!=null->"Connected to "+(connected?.name?:"Bluetooth host");registered->"HID ready — pair/connect from GX1";else->"Bluetooth HID not registered"}}
 inner class Pad(c:Context):View(c){var move:((Int,Int)->Unit)?=null;var tap:(()->Unit)?=null;private var lx=0f;private var ly=0f;private var dragged=false
  override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{lx=e.x;ly=e.y;dragged=false;return true};MotionEvent.ACTION_MOVE->{val dx=((e.x-lx)*sensitivity).roundToInt();val dy=((e.y-ly)*sensitivity).roundToInt();if(dx!=0||dy!=0){dragged=true;move?.invoke(dx,dy)};lx=e.x;ly=e.y;return true};MotionEvent.ACTION_UP->{if(!dragged)tap?.invoke();return true}};return true}}
}