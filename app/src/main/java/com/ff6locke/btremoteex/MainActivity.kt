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
 private var shiftOn=false
 private var capsOn=false
 private var ctrlOn=false
 private var altOn=false
 private var winOn=false
 private val keyboardKeyButtons=mutableListOf<Pair<Button,String>>()
 private fun shiftedLabel(label:String):String {
  if(label.length==1 && label[0].isLetter())return if(capsOn xor shiftOn)label.uppercase() else label.lowercase()
  if(!shiftOn)return if(label=="~")"`" else label
  return when(label) {
   "1"->"!";"2"->"@";"3"->"#";"4"->"$";"5"->"%";"6"->"^";"7"->"&";"8"->"*";"9"->"("; "0"->")"; "-"->"_"; "="->"+"; "["->"{"; "]"->"}"; "\\"->"|"; ";"->":"; "'"->"\u0022"; ","->"<"; "."->">"; "/"->"?"; "~"->"~"; else->label
  }
 }
 private fun refreshKeyboardLabels(){keyboardKeyButtons.forEach{(button,label)->button.text=shiftedLabel(label)}}

 private lateinit var modeList:LinearLayout
 private lateinit var keyboardPanel:LinearLayout
 private lateinit var contentHost:FrameLayout
 private lateinit var modeButtons:MutableList<Button>
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
    val dx=(-event.values[2]*dt*1100f*sensitivity).roundToInt()
    val dy=(-event.values[0]*dt*900f*sensitivity).roundToInt()
    if(dx!=0||dy!=0)mouse(0,dx,dy,0)
   }
   lastGyroTime=now
  }
 }
 private var hid:BluetoothHidDevice?=null
 private var connected:BluetoothDevice?=null
 private var registered=false
 private var profileRequested=false
 private var discoverabilityRequested=false
 private var discoverabilityActive=false
 private val discoverableRequestCode=24
 private val hidPrefs by lazy { getSharedPreferences("hid_connection",Context.MODE_PRIVATE) }
 private val btStateReceiver=object:android.content.BroadcastReceiver(){
  override fun onReceive(context:Context?,intent:Intent?){
   if(intent?.action!=BluetoothAdapter.ACTION_STATE_CHANGED)return
   when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR)){
    BluetoothAdapter.STATE_OFF->{hid=null;connected=null;registered=false;profileRequested=false;runOnUiThread{status.text="Bluetooth is off — turn it on to reconnect"}}
    BluetoothAdapter.STATE_ON->{discoverabilityRequested=false;discoverabilityActive=false;handler.postDelayed({if(adapter.isEnabled)startHid()},1200)}
   }
  }
 }
 private var sensitivity=1.4f
 private val handler=Handler(Looper.getMainLooper())
 private val adapter by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
 private val cb=object:BluetoothHidDevice.Callback(){
  override fun onAppStatusChanged(device:BluetoothDevice?,plugged:Boolean){registered=plugged;runOnUiThread{status.text=if(plugged)"HID registered — ready to pair" else "Android Bluetooth reports HID is not registered"}}
  override fun onConnectionStateChanged(device:BluetoothDevice,state:Int){
   if(state==BluetoothProfile.STATE_CONNECTED){connected=device;if(device!=null)hidPrefs.edit().putString("host_address",device.address).apply()}
   if(state==BluetoothProfile.STATE_DISCONNECTED&&connected==device)connected=null
   runOnUiThread{update()}
  }
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val filter=android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
  if(Build.VERSION.SDK_INT>=33)registerReceiver(btStateReceiver,filter,Context.RECEIVER_EXPORTED) else registerReceiver(btStateReceiver,filter)
  ui()
  handler.postDelayed({startHid()},500)
 }
 override fun onDestroy(){try{unregisterReceiver(btStateReceiver)}catch(_:Exception){};super.onDestroy()}
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
  root.addView(Button(this).apply{text="Connect Bluetooth HID";textSize=15f;setOnClickListener{retryHid()}},LinearLayout.LayoutParams(-1,dp(42)))
  modeList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(3),0,dp(3))}
  modeButtons=mutableListOf()
  val modeNames=listOf("TV D-pad","Air Mouse","Mouse Touchpad","Keyboard")
  for(rowIndex in 0..1){
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER}
   for(colIndex in 0..1){
    val index=rowIndex*2+colIndex
    val b=Button(this).apply{text=modeNames[index];textSize=13f;isAllCaps=false;minHeight=dp(38);setOnClickListener{setMode(index)}}
    modeButtons.add(b)
    row.addView(b,LinearLayout.LayoutParams(0,dp(40),1f).apply{if(colIndex==0)rightMargin=dp(3) else leftMargin=dp(3)})
   }
   modeList.addView(row,LinearLayout.LayoutParams(-1,dp(42)).apply{if(rowIndex==1)topMargin=dp(2)})
  }
  root.addView(modeList,LinearLayout.LayoutParams(-1,dp(86)).apply{topMargin=dp(2);bottomMargin=dp(2)})
  contentHost=FrameLayout(this)
  airPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER}
  airPanel.addView(TextView(this).apply{text="AIR MOUSE MODE";textSize=20f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(12),0,dp(12))})
  airPanel.addView(TextView(this).apply{text="Move your phone gently to move the pointer. Keep the phone level, then use the click buttons.";textSize=15f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(dp(10),dp(8),dp(10),dp(16))})
  airPanel.addView(TextView(this).apply{text="Tap anywhere: left-click  •  Hold 0.5 sec anywhere: right-click";textSize=13f;setTextColor(android.graphics.Color.LTGRAY);gravity=android.view.Gravity.CENTER;setPadding(dp(8),dp(10),dp(8),dp(10))})
  val airSystemKeys=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER}
  fun airSystemButton(label:String,icon:String,usage:Int)=Button(this).apply{text="$icon  $label";textSize=13f;isAllCaps=false;minHeight=dp(48);setTextColor(android.graphics.Color.WHITE);setOnClickListener{consumer(usage)}}
  airSystemKeys.addView(airSystemButton("BACK","↶",0x0224),LinearLayout.LayoutParams(0,dp(50),1f).apply{rightMargin=dp(3)})
  airSystemKeys.addView(airSystemButton("HOME","⌂",0x0223),LinearLayout.LayoutParams(0,dp(50),1f).apply{leftMargin=dp(2);rightMargin=dp(2)})
  airSystemKeys.addView(airSystemButton("MENU","☰",0x0040),LinearLayout.LayoutParams(0,dp(50),1f).apply{leftMargin=dp(3)})
  airPanel.addView(airSystemKeys,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(8)})
  val recenterButton=Button(this).apply{text="Re-centre";textSize=15f;setOnClickListener{lastGyroTime=0L;Toast.makeText(this@MainActivity,"Air mouse re-centred",Toast.LENGTH_SHORT).show()}}
  airPanel.addView(recenterButton,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(4)})
  val airTextEntry=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
  val airTextInput=EditText(this).apply{hint="Type text to send to GX1";textSize=14f;setSingleLine(true);setTextColor(android.graphics.Color.WHITE);setHintTextColor(android.graphics.Color.LTGRAY);setPadding(dp(10),0,dp(8),0);background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(8).toFloat();setColor(android.graphics.Color.rgb(24,43,67));setStroke(dp(1),android.graphics.Color.rgb(78,125,190))}}
  airTextEntry.addView(airTextInput,LinearLayout.LayoutParams(0,dp(46),1f).apply{rightMargin=dp(5)})
  airTextEntry.addView(Button(this).apply{text="SEND";textSize=13f;setOnClickListener{val value=airTextInput.text.toString();if(value.isNotEmpty()){sendText(value);airTextInput.text.clear();(getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(airTextInput.windowToken,0)}}},LinearLayout.LayoutParams(dp(82),dp(46)))
  airPanel.addView(airTextEntry,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(5)})
  var airRightClickFired=false
  val airRightClickTask=Runnable{airRightClickFired=true;click(2)}
  val airGestureListener=View.OnTouchListener{_,event->when(event.actionMasked){
   MotionEvent.ACTION_DOWN->{airRightClickFired=false;handler.postDelayed(airRightClickTask,500);true}
   MotionEvent.ACTION_UP->{handler.removeCallbacks(airRightClickTask);if(!airRightClickFired)click(1);true}
   MotionEvent.ACTION_CANCEL->{handler.removeCallbacks(airRightClickTask);true}
   else->true
  }}
  fun attachAirGestures(view:View){
   if(view===recenterButton || view is Button || view is EditText)return
   view.isClickable=true
   view.setOnTouchListener(airGestureListener)
   if(view is android.view.ViewGroup){for(i in 0 until view.childCount)attachAirGestures(view.getChildAt(i))}
  }
  attachAirGestures(airPanel)
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
  val tvTextEntry=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
  val tvTextInput=EditText(this).apply{hint="Type text to send to GX1";textSize=14f;setSingleLine(true);setTextColor(android.graphics.Color.WHITE);setHintTextColor(android.graphics.Color.LTGRAY);setPadding(dp(10),0,dp(8),0);background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(8).toFloat();setColor(android.graphics.Color.rgb(24,43,67));setStroke(dp(1),android.graphics.Color.rgb(78,125,190))}}
  tvTextEntry.addView(tvTextInput,LinearLayout.LayoutParams(0,dp(46),1f).apply{rightMargin=dp(5)})
  tvTextEntry.addView(Button(this).apply{text="SEND";textSize=13f;setOnClickListener{val value=tvTextInput.text.toString();if(value.isNotEmpty()){sendText(value);tvTextInput.text.clear();(getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(tvTextInput.windowToken,0)}}},LinearLayout.LayoutParams(dp(82),dp(46)))
  tvPanel.addView(tvTextEntry,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(5)})
  contentHost.addView(tvPanel,FrameLayout.LayoutParams(-1,-1))
  touchPanel=LinearLayout(this).apply{orientation=1;gravity=android.view.Gravity.CENTER;visibility=View.GONE}
  touchPanel.addView(TextView(this).apply{text="MOUSE TOUCHPAD MODE";textSize=19f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(6),0,dp(8))},LinearLayout.LayoutParams(-1,dp(36)))
  touchPanel.addView(TextView(this).apply{text="Slide one finger to move the pointer. Tap to left-click.";textSize=14f;setTextColor(android.graphics.Color.LTGRAY);gravity=android.view.Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(28)))
  val touchSurface=Pad(this).apply{
   setBackground(android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(20).toFloat();setColor(android.graphics.Color.rgb(20,43,73));setStroke(dp(2),android.graphics.Color.rgb(78,125,190))})
   move={dx,dy->mouse(0,dx,dy,0)}
   tap={click(1)}
  }
  touchPanel.addView(touchSurface,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(8);bottomMargin=dp(8)})
  val touchSystemKeys=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER}
  fun touchSystemButton(label:String,icon:String,usage:Int)=Button(this).apply{text="$icon  $label";textSize=13f;isAllCaps=false;minHeight=dp(46);setTextColor(android.graphics.Color.WHITE);setOnClickListener{consumer(usage)}}
  touchSystemKeys.addView(touchSystemButton("BACK","↶",0x0224),LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(3)})
  touchSystemKeys.addView(touchSystemButton("HOME","⌂",0x0223),LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(2);rightMargin=dp(2)})
  touchSystemKeys.addView(touchSystemButton("MENU","☰",0x0040),LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(3)})
  touchPanel.addView(touchSystemKeys,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(4)})
  val touchClicks=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER}
  touchClicks.addView(Button(this).apply{text="LEFT CLICK";setOnClickListener{click(1)}},LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(4)})
  touchClicks.addView(Button(this).apply{text="RIGHT CLICK";setOnClickListener{click(2)}},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(4)})
  touchPanel.addView(touchClicks,LinearLayout.LayoutParams(-1,dp(50)))
  val textEntry=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
  val textInput=EditText(this).apply{hint="Type text to send to GX1";textSize=15f;setSingleLine(true);setTextColor(android.graphics.Color.WHITE);setHintTextColor(android.graphics.Color.LTGRAY);setPadding(dp(10),0,dp(8),0);background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(8).toFloat();setColor(android.graphics.Color.rgb(24,43,67));setStroke(dp(1),android.graphics.Color.rgb(78,125,190))}}
  textEntry.addView(textInput,LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(5)})
  textEntry.addView(Button(this).apply{text="SEND";textSize=13f;setOnClickListener{val value=textInput.text.toString();if(value.isNotEmpty()){sendText(value);textInput.text.clear();(getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(textInput.windowToken,0)}}},LinearLayout.LayoutParams(dp(82),dp(48)))
  touchPanel.addView(textEntry,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(5)})
  contentHost.addView(touchPanel,FrameLayout.LayoutParams(-1,-1))
  contentHost.addView(airPanel,FrameLayout.LayoutParams(-1,-1))
  keyboardPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;visibility=View.GONE}
  keyboardPanel.addView(TextView(this).apply{text="ENGLISH (US) • QWERTY";textSize=15f;setTextColor(android.graphics.Color.WHITE);gravity=android.view.Gravity.CENTER;setPadding(0,dp(2),0,dp(3))},LinearLayout.LayoutParams(-1,dp(25)))
  fun keyButton(label:String, action:()->Unit):Button=Button(this).apply{text=label;textSize=if(label.length>1)10f else 15f;isAllCaps=false;minHeight=dp(36);minimumHeight=dp(36);setPadding(dp(1),0,dp(1),0);setTextColor(android.graphics.Color.WHITE);background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.RECTANGLE;cornerRadius=dp(5).toFloat();setColor(android.graphics.Color.rgb(35,65,105));setStroke(dp(1),android.graphics.Color.rgb(78,125,190))};if(label=="Shift"){setOnClickListener{shiftOn=!shiftOn;refreshKeyboardLabels();Toast.makeText(this@MainActivity,if(shiftOn)"Shift on" else "Shift off",Toast.LENGTH_SHORT).show()}}else setOnClickListener{action()};keyboardKeyButtons.add(this to label);text=shiftedLabel(label)}
  fun addSpecialRow(keys:List<Pair<String,()->Unit>>,height:Int=dp(40)){val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER};keys.forEach{item->row.addView(keyButton(item.first,item.second),LinearLayout.LayoutParams(0,height,1f).apply{leftMargin=dp(1);rightMargin=dp(1)})};keyboardPanel.addView(row,LinearLayout.LayoutParams(-1,height).apply{topMargin=dp(1);bottomMargin=dp(1)})}
  addSpecialRow(listOf("Esc" to {sendKeyboardUsage(0x29)},"F1" to {sendKeyboardUsage(0x3A)},"F2" to {sendKeyboardUsage(0x3B)},"F3" to {sendKeyboardUsage(0x3C)},"F4" to {sendKeyboardUsage(0x3D)},"F5" to {sendKeyboardUsage(0x3E)},"F6" to {sendKeyboardUsage(0x3F)},"F7" to {sendKeyboardUsage(0x40)},"F8" to {sendKeyboardUsage(0x41)}),dp(32))
  addSpecialRow(listOf("~","1","2","3","4","5","6","7","8","9","0","-","=","⌫","Del").map{label->label to {when(label){"⌫"->sendKeyboardUsage(0x2A);"Del"->sendKeyboardUsage(0x4C);else->sendKeyboardChar(if(label=="~"&&!shiftOn)"`" else label)}}})
  addSpecialRow(listOf("Tab","q","w","e","r","t","y","u","i","o","p","[","]","\\").map{label->label to {if(label=="Tab")sendKeyboardUsage(0x2B) else sendKeyboardChar(label)}})
  addSpecialRow(listOf("Caps","a","s","d","f","g","h","j","k","l",";","\'","Enter").map{label->label to {when(label){"Caps"->{capsOn=!capsOn;refreshKeyboardLabels();Toast.makeText(this@MainActivity,if(capsOn)"Caps Lock on" else "Caps Lock off",Toast.LENGTH_SHORT).show()};"Enter"->sendKeyboardUsage(0x28);else->sendKeyboardChar(label)}}})
  addSpecialRow(listOf("Shift","z","x","c","v","b","n","m",",",".","/","Shift").map{label->label to {if(label=="Shift"){}else sendKeyboardChar(label)}})
  val bottomKeyboardRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER};fun addBottomKey(label:String,action:()->Unit,weight:Float=1f){bottomKeyboardRow.addView(keyButton(label,action),LinearLayout.LayoutParams(0,dp(40),weight).apply{leftMargin=dp(1);rightMargin=dp(1)})};addBottomKey("Ctrl",{ctrlOn=!ctrlOn;Toast.makeText(this@MainActivity,if(ctrlOn)"Ctrl held" else "Ctrl released",Toast.LENGTH_SHORT).show()});addBottomKey("Win",{key(0,0xE3)});addBottomKey("Alt",{altOn=!altOn;Toast.makeText(this@MainActivity,if(altOn)"Alt held" else "Alt released",Toast.LENGTH_SHORT).show()});addBottomKey("Space",{sendKeyboardUsage(0x2C)},4f);addBottomKey("Alt",{altOn=!altOn;Toast.makeText(this@MainActivity,if(altOn)"Alt held" else "Alt released",Toast.LENGTH_SHORT).show()});addBottomKey("Ctrl",{ctrlOn=!ctrlOn;Toast.makeText(this@MainActivity,if(ctrlOn)"Ctrl held" else "Ctrl released",Toast.LENGTH_SHORT).show()});keyboardPanel.addView(bottomKeyboardRow,LinearLayout.LayoutParams(-1,dp(40)))
  keyboardPanel.setBackgroundColor(android.graphics.Color.rgb(5,15,34))
  // Add the keyboard panel to the same host as the other mode panels.
  contentHost.addView(keyboardPanel,FrameLayout.LayoutParams(-1,-1))
  // Attach the shared mode container to the root; all four mode panels live inside it.
  root.addView(contentHost,LinearLayout.LayoutParams(-1,0,1f))
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
  controlMode=mode.coerceIn(0,3)
  airMode=(controlMode==1)
  tvPanel.visibility=if(controlMode==0)View.VISIBLE else View.GONE
  airPanel.visibility=if(controlMode==1)View.VISIBLE else View.GONE
  touchPanel.visibility=if(controlMode==2)View.VISIBLE else View.GONE
  keyboardPanel.visibility=if(controlMode==3)View.VISIBLE else View.GONE
  if(controlMode==3){keyboardPanel.visibility=View.VISIBLE;keyboardPanel.requestLayout();contentHost.requestLayout()}
  modeButtons.forEachIndexed{index,b->b.alpha=if(index==controlMode)1f else 0.62f;b.isSelected=index==controlMode}
  lastGyroTime=0L
  if(airMode){
   val s=sensorManager;val g=gyro
   if(g==null){Toast.makeText(this,"This phone has no gyroscope; Air Mouse unavailable",Toast.LENGTH_LONG).show();setMode(0);return}
   s?.registerListener(gyroListener,g,SensorManager.SENSOR_DELAY_GAME)
  }else sensorManager?.unregisterListener(gyroListener)
 }
 override fun onPause(){super.onPause();sensorManager?.unregisterListener(gyroListener);lastGyroTime=0L}
 override fun onResume(){super.onResume();if(airMode&&gyro!=null)sensorManager?.registerListener(gyroListener,gyro,SensorManager.SENSOR_DELAY_GAME)}
 private fun retryHid(){
  if(!adapter.isEnabled){status.text="Turn on phone Bluetooth first";return}
  try{hid?.let{adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,it)}}catch(_:Exception){}
  hid=null
  registered=false
  profileRequested=false
  status.text="Retrying Android Bluetooth HID service…"
  startHid()
 }
 private fun startHid(){
  if(Build.VERSION.SDK_INT<28){status.text="Android 9+ required";return}
  if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE),23);return}
  if(!adapter.isEnabled){status.text="Turn on phone Bluetooth first";return}
  if(!discoverabilityRequested){
   discoverabilityRequested=true
   try{
    val discoverable=Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300)
    startActivityForResult(discoverable,discoverableRequestCode)
   }catch(e:Exception){discoverabilityRequested=false;status.text="Could not enable Bluetooth visibility: "+(e.message?:"unknown error")}
  }
  try{val keepAlive=Intent(this,HidKeepAliveService::class.java).setAction(HidKeepAliveService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(keepAlive)else startService(keepAlive)}catch(e:Exception){status.text="Could not start Bluetooth background service: "+(e.message?:"unknown error");return}
  if(profileRequested)return
  profileRequested=true
  status.text="Waiting for Android Bluetooth HID service…"
  handler.postDelayed({if(profileRequested&&!registered&&hid==null){profileRequested=false;status.text="Android HID service timed out — tap CONNECT BLUETOOTH HID to retry"}},12000)
  try{val proxyRequested=adapter.getProfileProxy(this,object:BluetoothProfile.ServiceListener{
   override fun onServiceConnected(profile:Int,proxy:BluetoothProfile){
    if(profile!=BluetoothProfile.HID_DEVICE){runOnUiThread{status.text="Unexpected Bluetooth profile: $profile"};return}
    hid=proxy as BluetoothHidDevice
    runOnUiThread{status.text="Android HID service connected — registering keyboard and mouse…"}
    // Composite descriptor: report 1 = mouse, report 2 = keyboard.
    val descriptor=intArrayOf(
     0x05,0x01,0x09,0x02,0xA1,0x01,0x09,0x01,0xA1,0x00,0x85,0x01,
     0x05,0x09,0x19,0x01,0x29,0x05,0x15,0x00,0x25,0x01,0x95,0x05,0x75,0x01,0x81,0x02,
     0x95,0x01,0x75,0x03,0x81,0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0,
     0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x02,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
     0x95,0x01,0x75,0x08,0x81,0x01,0x19,0x00,0x29,0xE7,0x15,0x00,0x25,0x65,0x75,0x08,0x95,0x06,0x81,0x00,0xC0,
     // Consumer controls: report 3, 16-bit usage (Back, Home, Menu).
     0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x03,0x15,0x00,0x26,0xFF,0x03,0x19,0x00,0x2A,0xFF,0x03,0x75,0x10,0x95,0x01,0x81,0x00,0xC0
    ).map{it.toByte()}.toByteArray()
    val sdp=BluetoothHidDeviceAppSdpSettings("BTRemoteEX","Air Mouse + Keyboard","Android",BluetoothHidDevice.SUBCLASS1_MOUSE,descriptor)
    try{hid?.unregisterApp()}catch(_:Exception){}
    handler.postDelayed({
     val accepted=try{hid?.registerApp(sdp,null,null,Executor{command->handler.post(command)},cb)?:false}catch(e:Exception){runOnUiThread{status.text="HID registration error: "+(e.message?:"unknown")};false}
     runOnUiThread{if(accepted)status.text="HID request sent — waiting for Android confirmation…" else status.text="Android rejected HID registration request"}
     if(accepted){
      handler.postDelayed({
      try{
       val address=hidPrefs.getString("host_address",null)
       if(address!=null&&adapter.isEnabled){
        val host=adapter.getRemoteDevice(address)
        if(host.bondState==BluetoothDevice.BOND_BONDED)hid?.connect(host)
       }
      }catch(_:Exception){}
      },1800)
     }
    },700)
   }
   override fun onServiceDisconnected(profile:Int){hid=null;registered=false;profileRequested=false;update()}
  },BluetoothProfile.HID_DEVICE);if(!proxyRequested){profileRequested=false;status.text="Android HID service is unavailable on this phone"}}catch(e:SecurityException){profileRequested=false;status.text="Bluetooth permissions denied"}catch(e:Exception){profileRequested=false;status.text="Could not open Android HID service: "+(e.message?:"unknown error")}
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==discoverableRequestCode){discoverabilityActive=resultCode>0;runOnUiThread{status.text=if(discoverabilityActive)"Visible to Bluetooth devices for $resultCode seconds — registering HID…" else "Bluetooth visibility was declined or unavailable — HID registration will continue"}}}
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==23&&grantResults.isNotEmpty()&&grantResults.all{it==PackageManager.PERMISSION_GRANTED})startHid()else if(requestCode==23)status.text="Bluetooth permissions are needed for HID"}
 private fun mouse(b:Int,x:Int,y:Int,w:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,1,byteArrayOf(b.toByte(),x.coerceIn(-127,127).toByte(),y.coerceIn(-127,127).toByte(),w.toByte()))}catch(_:Exception){}}
 private fun click(b:Int){mouse(b,0,0,0);handler.postDelayed({mouse(0,0,0,0)},50)}
 private fun key(mod:Int,k:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,2,byteArrayOf(mod.toByte(),0,k.toByte(),0,0,0,0,0));handler.postDelayed({try{h.sendReport(d,2,ByteArray(8))}catch(_:Exception){}},120)}catch(_:Exception){}}
 private fun consumer(usage:Int){val h=hid?:return;val d=connected?:return;if(!registered)return;try{h.sendReport(d,3,byteArrayOf((usage and 0xFF).toByte(),((usage shr 8) and 0xFF).toByte()));handler.postDelayed({try{h.sendReport(d,3,byteArrayOf(0,0))}catch(_:Exception){}},120)}catch(_:Exception){}}
 private fun sendKeyboardChar(label:String){
  if(!registered||connected==null){Toast.makeText(this,"Connect Bluetooth HID to GX1 first",Toast.LENGTH_SHORT).show();return}
  val c=label.firstOrNull()?:return
  val mapped=ascii(c)?:return
  val shifted=if(c.isLetter())(capsOn xor shiftOn) else (mapped.first!=0||shiftOn)
  val modifiers=(if(shifted)2 else 0) or (if(ctrlOn)1 else 0) or (if(altOn)4 else 0)
  key(modifiers,mapped.second)
 }
 private fun sendKeyboardUsage(usage:Int){if(!registered||connected==null){Toast.makeText(this,"Connect Bluetooth HID to GX1 first",Toast.LENGTH_SHORT).show();return};val modifiers=(if(shiftOn)2 else 0) or (if(ctrlOn)1 else 0) or (if(altOn)4 else 0);key(modifiers,usage)}
 private fun sendText(s:String){if(!registered||connected==null){Toast.makeText(this,"Connect Bluetooth HID to GX1 first",Toast.LENGTH_SHORT).show();return};var t=0L;s.forEach{c->val m=ascii(c);if(m!=null){handler.postDelayed({key(m.first,m.second)},t);t+=85}}}
 private fun ascii(c:Char):Pair<Int,Int>?{val shift=2;return when(c){
  in 'a'..'z'->Pair(0,4+(c-'a'));in 'A'..'Z'->Pair(shift,4+(c-'A'));in '1'..'9'->Pair(0,0x1E+(c-'1'));'0'->Pair(0,0x27);' '->Pair(0,0x2C);'\n','\r'->Pair(0,0x28);'\t'->Pair(0,0x2B)
  '-'->Pair(0,0x2D);'_'->Pair(shift,0x2D);'='->Pair(0,0x2E);'+'->Pair(shift,0x2E);'['->Pair(0,0x2F);'{'->Pair(shift,0x2F);']'->Pair(0,0x30);'}'->Pair(shift,0x30);'\\'->Pair(0,0x31);'|'->Pair(shift,0x31);';'->Pair(0,0x33);':'->Pair(shift,0x33);'\''->Pair(0,0x34);'"'->Pair(shift,0x34);'`'->Pair(0,0x35);'~'->Pair(shift,0x35);','->Pair(0,0x36);'<'->Pair(shift,0x36);'.'->Pair(0,0x37);'>'->Pair(shift,0x37);'/'->Pair(0,0x38);'?'->Pair(shift,0x38)
  '!'->Pair(shift,0x1E);'@'->Pair(shift,0x1F);'#'->Pair(shift,0x20);'$'->Pair(shift,0x21);'%'->Pair(shift,0x22);'^'->Pair(shift,0x23);'&'->Pair(shift,0x24);'*'->Pair(shift,0x25);'('->Pair(shift,0x26);')'->Pair(shift,0x27);else->null}}
 private fun update(){status.text=when{connected!=null->"Connected to "+(connected?.name?:"Bluetooth host");registered->"HID ready — pair/connect from GX1";else->"Bluetooth HID not registered"}}
 inner class Pad(c:Context):View(c){var move:((Int,Int)->Unit)?=null;var tap:(()->Unit)?=null;private var lx=0f;private var ly=0f;private var dragged=false
  override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{lx=e.x;ly=e.y;dragged=false;return true};MotionEvent.ACTION_MOVE->{val dx=((e.x-lx)*sensitivity).roundToInt();val dy=((e.y-ly)*sensitivity).roundToInt();if(dx!=0||dy!=0){dragged=true;move?.invoke(dx,dy)};lx=e.x;ly=e.y;return true};MotionEvent.ACTION_UP->{if(!dragged)tap?.invoke();return true}};return true}}
}