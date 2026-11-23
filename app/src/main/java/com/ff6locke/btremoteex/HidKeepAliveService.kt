package com.ff6locke.btremoteex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class HidKeepAliveService : Service() {
 companion object {
  const val ACTION_START="com.ff6locke.btremoteex.action.START_HID_KEEPALIVE"
  private const val CHANNEL_ID="bt_hid_connection"
  private const val NOTIFICATION_ID=4101
 }
 override fun onCreate(){
  super.onCreate()
  if(Build.VERSION.SDK_INT>=26){
   val manager=getSystemService(NotificationManager::class.java)
   manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"Bluetooth remote connection",NotificationManager.IMPORTANCE_LOW).apply{description="Keeps the Bluetooth remote connection active while BTRemoteEX is in the background"})
  }
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val notification=if(Build.VERSION.SDK_INT>=26){
   Notification.Builder(this,CHANNEL_ID)
    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
    .setContentTitle("BTRemoteEX is connected")
    .setContentText("Bluetooth remote stays active in the background")
    .setOngoing(true)
    .build()
  }else{
   @Suppress("DEPRECATION")
   Notification.Builder(this)
    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
    .setContentTitle("BTRemoteEX is connected")
    .setContentText("Bluetooth remote stays active in the background")
    .setOngoing(true)
    .build()
  }
  if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
  else startForeground(NOTIFICATION_ID,notification)
  return START_STICKY
 }
 override fun onBind(intent:Intent?):IBinder?=null
}