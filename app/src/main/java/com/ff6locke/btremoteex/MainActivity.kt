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
 private lateinit var status:TextView
 private lateinit var speedLabel:TextView
 private lateinit var entry:EditText
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
  speedLabel=TextView(this).apply{text="Mouse speed: 1.4x"};root.addView(speedLabel)
  root.addView(SeekBar(this).apply{max=25;progress=9;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(sb:SeekBar,p:Int,fromUser:Boolean){sensitivity=0.5f+p*0.25f;speedLabel.text="Mouse speed: %.2fx".format(sensitivity)}
   override fun onStartTrackingTouch(sb:SeekBar){}
   override fun onStopTrackingTouch(sb:SeekBar){}
  })})
  val pad=Pad(this).apply{setBackgroundColor(0xFFE7EDF5.toInt());move={x,y->mouse(0,x,y,0)};tap={click(1)}}
  root.addView(pad,LinearLayout.LayoutParams(-1,0,1f))
  // TV-style directional navigation pad. These send standard HID keyboard usages.
  root.addView(TextView(this).apply{text="TV NAVIGATION";textSize=14f;setPadding(0,10,0,4)})
  val dpad=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER}
  fun navButton(label:String,usage:Int,weight:Float=1f):Button =
   Button(this).apply{text=label; textSize=18f; minHeight=52; setOnClickListener{key(0,usage)}}
  dpad.addView(navButton("▲",0x52),LinearLayout.LayoutParams(112,56))
  val middle=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER}
  middle.addView(navButton("◀",0x50),LinearLayout.LayoutParams(0,56,1f))
  middle.addView(navButton("OK",0x28),LinearLayout.LayoutParams(0,56,1f))
  middle.addView(navButton("▶",0x4F),LinearLayout.LayoutParams(0,56,1f))
  dpad.addView(middle,LinearLayout.LayoutParams(300,56))
  dpad.addView(navButton("▼",0x51),LinearLayout.LayoutParams(112,56))
  root.addView(dpad,LinearLayout.LayoutParams(-1,-2))
  val systemKeys=LinearLayout(this).apply{orientation=0}
  systemKeys.addView(Button(this).apply{text="Back";setOnClickListener{key(0,0x29)}},LinearLayout.LayoutParams(0,-2,1f))
  systemKeys.addView(Button(this).apply{text="Home";setOnClickListener{key(0,0x4A)}},LinearLayout.LayoutParams(0,-2,1f))
  systemKeys.addView(Button(this).apply{text="Menu";setOnClickListener{key(0,0x65)}},LinearLayout.LayoutParams(0,-2,1f))
  root.addView(systemKeys)
  val buttons=LinearLayout(this)
  buttons.addView(Button(this).apply{text="Left click";setOnClickListener{click(1)}},LinearLayout.LayoutParams(0,-2,1f))
  buttons.addView(Button(this).apply{text="Right click";setOnClickListener{click(2)}},LinearLayout.LayoutParams(0,-2,1f));root.addView(buttons)
  val scroll=LinearLayout(this)
  scroll.addView(Button(this).apply{text="Scroll ↑";setOnClickListener{mouse(0,0,0,1)}},LinearLayout.LayoutParams(0,-2,1f))
  scroll.addView(Button(this).apply{text="Scroll ↓";setOnClickListener{mouse(0,0,0,-1)}},LinearLayout.LayoutParams(0,-2,1f));root.addView(scroll)
  entry=EditText(this).apply{hint="Type text to send to GX1";setSingleLine(true)};root.addView(entry)
  root.addView(Button(this).apply{text="Send text";setOnClickListener{sendText(entry.text.toString());entry.text.clear()}})
  root.addView(TextView(this).apply{text="Pair/connect from GX1 Bluetooth settings. Touchpad swipe and tap. Text entry supports English, numbers and common symbols.";textSize=14f})
  setContentView(root)
 }
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
     0x95,0x01,0x75,0x08,0x81,0x01,0x19,0x00,0x29,0x65,0x15,0x00,0x25,0x65,0x75,0x08,0x95,0x06,0x81,0x00,0xC0
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
 private fun key(mod:Int,k:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,2,byteArrayOf(mod.toByte(),0,k.toByte(),0,0,0,0,0));handler.postDelayed({try{h.sendReport(d,2,ByteArray(8))}catch(_:Exception){}},35)}catch(_:Exception){}}
 private fun sendText(s:String){if(!registered||connected==null){Toast.makeText(this,"Connect Bluetooth HID to GX1 first",Toast.LENGTH_SHORT).show();return};var t=0L;s.forEach{c->val m=ascii(c);if(m!=null){handler.postDelayed({key(m.first,m.second)},t);t+=85}}}
 private fun ascii(c:Char):Pair<Int,Int>?{val shift=2;return when(c){
  in 'a'..'z'->Pair(0,4+(c-'a'));in 'A'..'Z'->Pair(shift,4+(c-'A'));in '1'..'9'->Pair(0,0x1E+(c-'1'));'0'->Pair(0,0x27);' '->Pair(0,0x2C);'\n','\r'->Pair(0,0x28);'\t'->Pair(0,0x2B)
  '-'->Pair(0,0x2D);'_'->Pair(shift,0x2D);'='->Pair(0,0x2E);'+'->Pair(shift,0x2E);'['->Pair(0,0x2F);'{'->Pair(shift,0x2F);']'->Pair(0,0x30);'}'->Pair(shift,0x30);'\\'->Pair(0,0x31);'|'->Pair(shift,0x31);';'->Pair(0,0x33);':'->Pair(shift,0x33);'\''->Pair(0,0x34);'"'->Pair(shift,0x34);'`'->Pair(0,0x35);'~'->Pair(shift,0x35);','->Pair(0,0x36);'<'->Pair(shift,0x36);'.'->Pair(0,0x37);'>'->Pair(shift,0x37);'/'->Pair(0,0x38);'?'->Pair(shift,0x38)
  '!'->Pair(shift,0x1E);'@'->Pair(shift,0x1F);'#'->Pair(shift,0x20);'$'->Pair(shift,0x21);'%'->Pair(shift,0x22);'^'->Pair(shift,0x23);'&'->Pair(shift,0x24);'*'->Pair(shift,0x25);'('->Pair(shift,0x26);')'->Pair(shift,0x27);else->null}}
 private fun update(){status.text=when{connected!=null->"Connected to "+(connected?.name?:"Bluetooth host");registered->"HID ready — pair/connect from GX1";else->"Bluetooth HID not registered"}}
 inner class Pad(c:Context):View(c){var move:((Int,Int)->Unit)?=null;var tap:(()->Unit)?=null;private var lx=0f;private var ly=0f;private var dragged=false
  override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{lx=e.x;ly=e.y;dragged=false;return true};MotionEvent.ACTION_MOVE->{val dx=((e.x-lx)*sensitivity).roundToInt();val dy=((e.y-ly)*sensitivity).roundToInt();if(dx!=0||dy!=0){dragged=true;move?.invoke(dx,dy)};lx=e.x;ly=e.y;return true};MotionEvent.ACTION_UP->{if(!dragged)tap?.invoke();return true}};return true}}
}