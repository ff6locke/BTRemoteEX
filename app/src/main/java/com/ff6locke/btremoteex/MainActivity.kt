package com.ff6locke.btremoteex
import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
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
 private lateinit var touchPanel:LinearLayout
 private var airMode=false
 private var controlMode=0
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
  val density=resources.displayMetrics.density
  fun dp(v:Int)=(v*density).roundToInt()
  val screenDp=(resources.displayMetrics.heightPixels/density).roundToInt()
  val padDp=((screenDp-500)/3).coerceIn(56,88)
  val padSize=dp(padDp)
  val root=LinearLayout(this).apply{orientation=1;setPadding(dp(10),dp(4),dp(10),dp(6));setBackgroundColor(android.graphics.Color.rgb(5,15,34))}
  val title=TextView(this).apply{text="BTRemoteEX";textSize=23f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(2),0,dp(3))}
  root.addView(title,LinearLayout.LayoutParams(-1,dp(34)))
  status=TextView(this).apply{text="Ready";textSize=13f;setTextColor(android.graphics.Color.LTGRAY);gravity=android.view.Gravity.CENTER;setPadding(0,0,0,dp(2))}
  root.addView(status,LinearLayout.LayoutParams(-1,dp(24)))
  root.addView(Button(this).apply{text="Connect Bluetooth HID";textSize=15f;setOnClickListener{startHid()}},LinearLayout.LayoutParams(-1,dp(42)))
  modeButton=Button(this).apply{text="Mode: TV D-pad  •  Tap to change";textSize=16f;setOnClickListener{setMode((controlMode+1)%3)}}
  root.addView(modeButton,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(4);bottomMargin=dp(2)})
  airPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER}
  airPanel.addView(TextView(this).apply{text="AIR MOUSE MODE";textSize=20f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(12),0,dp(12))})
  airPanel.addView(TextView(this).apply{text="Move your phone gently to move the pointer. Keep the phone level, then use the click buttons.";textSize=15f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(dp(10),dp(8),dp(10),dp(16))})
  val mouseButtons=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER}
  mouseButtons.addView(Button(this).apply{text="LEFT CLICK";textSize=15f;setOnClickListener{click(1)}},LinearLayout.LayoutParams(0,dp(62),1f).apply{rightMargin=dp(4)})
  mouseButtons.addView(Button(this).apply{text="RIGHT CLICK";textSize=15f;setOnClickListener{click(2)}},LinearLayout.LayoutParams(0,dp(62),1f).apply{leftMargin=dp(4)})
  airPanel.addView(mouseButtons)
  airPanel.addView(Button(this).apply{text="Re-centre";textSize=15f;setOnClickListener{lastGyroTime=0L;Toast.makeText(this@MainActivity,"Air mouse re-centred",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})
  tvPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER;setPadding(0,dp(2),0,dp(2))}
  tvPanel.addView(TextView(this).apply{text="TV D-PAD MODE";textSize=16f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(2),0,dp(4))},LinearLayout.LayoutParams(-1,dp(26)))
  fun padButton(label:String,usage:Int):Button=Button(this).apply{
   text=label;textSize=if(label=="OK")22f else 34f;setTextColor(android.graphics.Color.WHITE);isAllCaps=false;minHeight=padSize;setPadding(dp(2),dp(2),dp(2),dp(2))
   background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(16).toFloat();setColor(if(label=="OK")android.graphics.Color.rgb(0,112,245) else android.graphics.Color.rgb(35,65,105));setStroke(dp(1),android.graphics.Color.rgb(78,125,190))}
   setOnClickListener{key(0,usage)}
  }
  val topRow=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER}
  topRow.addView(View(this),LinearLayout.LayoutParams(padSize,padSize))
  topRow.addView(padButton("▲",0x52),LinearLayout.LayoutParams(padSize,padSize).apply{leftMargin=dp(3);rightMargin=dp(3)})
  topRow.addView(View(this),LinearLayout.LayoutParams(padSize,padSize))
  tvPanel.addView(topRow)
  val middle=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER}
  middle.addView(padButton("◀",0x50),LinearLayout.LayoutParams(padSize,padSize).apply{rightMargin=dp(3)})
  middle.addView(padButton("OK",0x28),LinearLayout.LayoutParams(padSize,padSize).apply{leftMargin=dp(3);rightMargin=dp(3)})
  middle.addView(padButton("▶",0x4F),LinearLayout.LayoutParams(padSize,padSize).apply{leftMargin=dp(3)})
  tvPanel.addView(middle,LinearLayout.LayoutParams(-1,padSize).apply{topMargin=dp(3);bottomMargin=dp(3)})
  val bottomRow=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER}
  bottomRow.addView(View(this),LinearLayout.LayoutParams(padSize,padSize))
  bottomRow.addView(padButton("▼",0x51),LinearLayout.LayoutParams(padSize,padSize).apply{leftMargin=dp(3);rightMargin=dp(3)})
  bottomRow.addView(View(this),LinearLayout.LayoutParams(padSize,padSize))
  tvPanel.addView(bottomRow)
  val systemKeys=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER}
  fun systemButton(label:String,icon:String,usage:Int)=Button(this).apply{text="$icon  $label";textSize=14f;isAllCaps=false;minHeight=dp(52);setTextColor(android.graphics.Color.WHITE);setOnClickListener{consumer(usage)}}
  systemKeys.addView(systemButton("BACK","↶",0x0224),LinearLayout.LayoutParams(0,dp(54),1f).apply{rightMargin=dp(3)})
  systemKeys.addView(systemButton("HOME","⌂",0x0223),LinearLayout.LayoutParams(0,dp(54),1f).apply{leftMargin=dp(2);rightMargin=dp(2)})
  systemKeys.addView(systemButton("MENU","☰",0x0040),LinearLayout.LayoutParams(0,dp(54),1f).apply{leftMargin=dp(3)})
  tvPanel.addView(systemKeys,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(5)})
  root.addView(tvPanel,LinearLayout.LayoutParams(-1,-2))
  touchPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER;visibility=View.GONE}
  touchPanel.addView(TextView(this).apply{text="MOUSE TOUCHPAD MODE";textSize=19f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(6),0,dp(8))},LinearLayout.LayoutParams(-1,dp(36)))
  touchPanel.addView(TextView(this).apply{text="Slide one finger to move the pointer. Tap to left-click.";textSize=14f;setTextColor(android.graphics.Color.LTGRAY);gravity=android.view.Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(28)))
  val touchSurface=Pad(this).apply{
   setBackground(android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(20).toFloat();setColor(android.graphics.Color.rgb(20,43,73));setStroke(dp(2),android.graphics.Color.rgb(78,125,190))})
   move={dx,dy->mouse(0,dx,dy,0)}
   tap={click(1)}
  }
  touchPanel.addView(touchSurface,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(8);bottomMargin=dp(8)})
  val touchClicks=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER}
  touchClicks.addView(Button(this).apply{text="LEFT CLICK";setOnClickListener{click(1)}},LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(4)})
  touchClicks.addView(Button(this).apply{text="RIGHT CLICK";setOnClickListener{click(2)}},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(4)})
  touchPanel.addView(touchClicks,LinearLayout.LayoutParams(-1,dp(50)))
  root.addView(touchPanel,LinearLayout.LayoutParams(-1,0,1f))
  root.addView(airPanel,LinearLayout.LayoutParams(-1,0,1f))
  entry=EditText(this).apply{
   hint="Type text to send to GX1";setSingleLine(true);textSize=16f
   setTextColor(android.graphics.Color.WHITE);setHintTextColor(android.graphics.Color.LTGRAY)
   setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(65,145,255)))
   inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
  }
  root.addView(entry,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(4)})
  root.addView(Button(this).apply{text="Send text";textSize=15f;setOnClickListener{sendText(entry.text.toString());entry.text.clear()}},LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(3)})
  speedLabel=TextView(this).apply{text="Control sensitivity: 1.4x";textSize=13f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER_VERTICAL}
  root.addView(speedLabel,LinearLayout.LayoutParams(-1,dp(24)).apply{topMargin=dp(2)})
  root.addView(SeekBar(this).apply{max=25;progress=9;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(sb:SeekBar,p:Int,fromUser:Boolean){sensitivity=0.5f+p*0.25f;speedLabel.text="Control sensitivity: %.2fx".format(sensitivity)}
   override fun onStartTrackingTouch(sb:SeekBar){}
   override fun onStopTrackingTouch(sb:SeekBar){}
  })},LinearLayout.LayoutParams(-1,dp(28)))
  root.addView(TextView(this).apply{text="TV mode: D-pad keys  •  Air Mouse: move phone to control pointer";textSize=11f;setTextColor(android.graphics.Color.LTGRAY);gravity=android.view.Gravity.CENTER;setPadding(0,dp(2),0,0)},LinearLayout.LayoutParams(-1,dp(18)))
  sensorManager=getSystemService(Context.SENSOR_SERVICE) as SensorManager
  gyro=sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
  setContentView(root)
  setMode(0)
 }
 private fun setMode(mode:Int){
  controlMode=mode
  airMode=(controlMode==1)
  tvPanel.visibility=if(controlMode==0)View.VISIBLE else View.GONE
  airPanel.visibility=if(controlMode==1)View.VISIBLE else View.GONE
  touchPanel.visibility=if(controlMode==2)View.VISIBLE else View.GONE
  modeButton.text=when(controlMode){0->"Mode: TV D-pad  •  Tap to change";1->"Mode: Air Mouse  •  Tap to change";else->"Mode: Mouse Touchpad  •  Tap to change"}
  lastGyroTime=0L
  if(airMode){
   val s=sensorManager;val g=gyro
   if(g==null){Toast.makeText(this,"This phone has no gyroscope; Air Mouse unavailable",Toast.LENGTH_LONG).show();airMode=false;controlMode=0;airPanel.visibility=View.GONE;tvPanel.visibility=View.VISIBLE;touchPanel.visibility=View.GONE;modeButton.text="Mode: TV D-pad  •  Tap to change";return}
   s?.registerListener(gyroListener,g,SensorManager.SENSOR_DELAY_GAME)
  }else sensorManager?.unregisterListener(gyroListener)
 }
 override fun onPause(){super.onPause();sensorManager?.unregisterListener(gyroListener);lastGyroTime=0L}
 override fun onResume(){super.onResume();if(airMode&&gyro!=null)sensorManager?.registerListener(gyroListener,gyro,SensorManager.SENSOR_DELAY_GAME)}
 private fun startHid(){
  if(Build.VERSION.SDK_INT<28){status.text="Android 9+ required";return}
  if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE),23);return}
  if(!adapter.isEnabled){status.text="Turn on phone Bluetooth first";return}
  try{val keepAlive=Intent(this,HidKeepAliveService::class.java).setAction(HidKeepAliveService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(keepAlive)else startService(keepAlive)}catch(e:Exception){status.text="Could not start Bluetooth background service: "+(e.message?:"unknown error");return}
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