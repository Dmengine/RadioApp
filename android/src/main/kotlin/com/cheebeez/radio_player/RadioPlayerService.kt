/*
 *  RadioPlayerService.kt
 *
 *  Created by Ilya Chirkunov <xc@yar.net> on 30.12.2020.
 */

package com.cheebeez.radio_player


import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_MIN
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.ui.PlayerNotificationManager.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.util.*


/** Service for plays streaming audio content using ExoPlayer. */
 class RadioPlayerService : Service(), Player.EventListener,MetadataOutput,
    PlayerNotificationManager.NotificationListener {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "my_radio_ch"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATE_CHANGED = "state_changed"
        const val ACTION_STATE_CHANGED_EXTRA = "state"
        const val ACTION_NEW_METADATA = "matadata_changed"
        const val ACTION_NEW_METADATA_EXTRA = "matadata"
        const val ACTION_NEW_LOADING_DATA = "loading_data_changed"
        const val ACTION_NEW_LOADING_DATA_EXTRA = "loading"
        const val ACTION_NOTIFICATION_DATA = "com.radiofm.freeradio.N"
        const val ACTION_NOTIFICATION_EXTRA = "notification"
    }

    var metadataArtwork: Bitmap? = null
    private var defaultArtwork: Bitmap? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var notificationTitle = ""
    private var isForegroundService = true
    private var metadataList: MutableList<String>? = null
    private var localBinder = LocalBinder()
    private var br : BroadcastReceiver?=null
    private var p:PendingIntent?=null
    private var playintent:PendingIntent?=null
    private var pauseintent:PendingIntent?=null
    private var nextintent:PendingIntent?=null
    private var previousintent:PendingIntent?=null
    private val player: SimpleExoPlayer by lazy {
        SimpleExoPlayer.Builder(this).build()
    }


    public val localBroadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(this)
    }

    inner class LocalBinder : Binder() {
        // Return this instance of RadioPlayerService so clients can call public methods.
        fun getService(): RadioPlayerService = this@RadioPlayerService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return localBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        player.setRepeatMode(Player.REPEAT_MODE_OFF)
        player.addListener(this)
        //player.addMetadataOutput(this)
        // Broadcast receiver for playback state changes, passed to registerReceiver()
        br =MyBroadCastReceiver(localBroadcastManager)
        val filter=IntentFilter(ACTION_NOTIFICATION_DATA)
        registerReceiver(br,filter)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        player.release()
        try{
            unregisterReceiver(br)
            stopForeground(true)
        }catch (e:Exception){
            println(e)
        }

        android.os.Process.killProcess(android.os.Process.myPid());
    }
    fun reset(){
       // playerNotificationManager?.invalidate();
        createNotificationManager()
        player.seekTo(0)
        player.stop()
    }

    fun setMediaItem(streamTitle: String, streamUrl: String) {
        val mediaItems: List<MediaItem> = runBlocking {
                GlobalScope.async {
                    parseUrls(streamUrl).map { MediaItem.fromUri(it) }
                }.await()
            }

        metadataList = null
        defaultArtwork = null
        metadataArtwork = null
        notificationTitle = streamTitle
        //playerNotificationManager?.invalidate();
        createNotificationManager()
        player.stop()
        player.clearMediaItems()
        player.seekTo(0)
        player.addMediaItems(mediaItems)
    }

    fun setDefaultArtwork(image: Bitmap) {
        defaultArtwork = image
        //playerNotificationManager?.invalidate();
        createNotificationManager()
    }

    fun play() {
        player.playWhenReady = true
      //  playerNotificationManager?.invalidate();
        createNotificationManager()
    }

    fun pause() {
        player.playWhenReady = false
       // playerNotificationManager?.invalidate();
        createNotificationManager()
    }
    fun stop(){
        player.playWhenReady = false
        createNotificationManager()
    }


    /** Extract URLs from user link. */
    private fun parseUrls(url: String): List<String> {
        var urls: List<String> = emptyList()

        when (url.substringAfterLast(".")) {
            "pls" -> {
                 urls = URL(url).readText().lines().filter { 
                    it.contains("=http") }.map {
                        it.substringAfter("=")
                    }
            }
            "m3u" -> {
                val content = URL(url).readText().trim()
                 urls = listOf<String>(content)
            }
            else -> {
                urls = listOf<String>(url)
            }
        }

        return urls
    }



    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
            channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    /** Creates a notification manager for background playback. */
    private fun createNotificationManager() {
        try {
            println("xdfxffg$notificationTitle")
            //Notification click intent
            val changePage = Intent("android.intent.action.MAIN")
            changePage.component = ComponentName("com.radiofm.freeradio", "com.radiofm.freeradio.MainActivity")
            if(Build.VERSION.SDK_INT >= 31){
                p = PendingIntent.getActivity(this, 0, changePage, PendingIntent.FLAG_IMMUTABLE);
            }else{
                p = PendingIntent.getActivity(this, 0, changePage, PendingIntent.FLAG_IMMUTABLE);
            }


            //Play broadcast intent
            val play = Intent(ACTION_NOTIFICATION_DATA)
            play.putExtra(ACTION_NOTIFICATION_EXTRA, "PLAY")
            if(Build.VERSION.SDK_INT >= 31){
                playintent = PendingIntent.getBroadcast(this, 10, play, PendingIntent.FLAG_IMMUTABLE)
            }else{
                playintent = PendingIntent.getBroadcast(this, 10, play, PendingIntent.FLAG_IMMUTABLE)
            }
            //Pause broadcast intent
            val pause = Intent(ACTION_NOTIFICATION_DATA)
            pause.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            pause.putExtra(ACTION_NOTIFICATION_EXTRA, "PAUSE")
            if(Build.VERSION.SDK_INT >= 31){
                pauseintent = PendingIntent.getBroadcast(this, 20, pause, PendingIntent.FLAG_IMMUTABLE)
            }else{
                pauseintent = PendingIntent.getBroadcast(this, 20, pause, PendingIntent.FLAG_IMMUTABLE)
            }
            //Next broadcast intent
            val next = Intent(ACTION_NOTIFICATION_DATA)
            next.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            next.putExtra(ACTION_NOTIFICATION_EXTRA, "NEXT")

            if(Build.VERSION.SDK_INT >= 31){
                nextintent = PendingIntent.getBroadcast(this, 30, next, PendingIntent.FLAG_IMMUTABLE)
            }else{
                nextintent = PendingIntent.getBroadcast(this, 30, next, PendingIntent.FLAG_IMMUTABLE)
            }
            //Previous broadcast intent
            val previous = Intent(ACTION_NOTIFICATION_DATA)
            previous.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            previous.putExtra(ACTION_NOTIFICATION_EXTRA, "PREVIOUS")
            if(Build.VERSION.SDK_INT >= 31){
                previousintent = PendingIntent.getBroadcast(this, 40, previous, PendingIntent.FLAG_IMMUTABLE)
            }else{
                previousintent = PendingIntent.getBroadcast(this, 40, previous, PendingIntent.FLAG_IMMUTABLE)
            }
            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel(NOTIFICATION_CHANNEL_ID, "My akromax radio")
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

            val notificationBuilder = NotificationCompat.Builder(this, channelId )
            val notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.exo_notification_small_icon)
                // Add media control buttons that invoke intents in your media service
                .addAction(R.drawable.exo_icon_previous, "Previous", previousintent) // #0
            if (player.playWhenReady) {
                notification.addAction(R.drawable.exo_icon_pause, "Pause", pauseintent) // #1
            } else {
                notification.addAction(R.drawable.exo_icon_play, "Play", playintent) // #1
            }
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(1 /* #1: pause button \*/)
                )
                .setColor(Color.RED)
                .setProgress(100,1,true)
                .setColorized(true)
                .setContentIntent(p)
                .setOngoing(true)
                .addAction(R.drawable.exo_icon_next, "Next", nextintent) // #2
                // Apply the media style template
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentTitle(notificationTitle)
                .setLargeIcon(defaultArtwork)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
            startForeground(NOTIFICATION_ID, notification.build())

        }catch (e:Exception){
            println(e)
        }
    }
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        if(playbackState == Player.STATE_IDLE){
            println("STATE IDLE")
            player.prepare()
        }
        if(playbackState==Player.STATE_BUFFERING){
            println("STATE LOADING")
            val stateIntent = Intent(ACTION_NEW_LOADING_DATA)
            stateIntent.putExtra(ACTION_NEW_LOADING_DATA_EXTRA, "loading")
            stateIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            localBroadcastManager.sendBroadcast(stateIntent)
        }else{
            println("STATE NOT LOADING")
            val stateIntent = Intent(ACTION_NEW_LOADING_DATA)
            stateIntent.putExtra(ACTION_NEW_LOADING_DATA_EXTRA, "not loading")
            stateIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            localBroadcastManager.sendBroadcast(stateIntent)
        }

        val stateIntent = Intent(ACTION_STATE_CHANGED)
        stateIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        stateIntent.putExtra(ACTION_STATE_CHANGED_EXTRA, playWhenReady)
        localBroadcastManager.sendBroadcast(stateIntent)
    }


    override fun onMetadata(metadata: Metadata) {
//        val icyInfo: IcyInfo = metadata[0] as IcyInfo
//        val title: String = icyInfo.title ?: return
//        val cover: String = icyInfo.url ?: ""
//
//        metadataList = title.split(" - ").toMutableList()
//        if (metadataList!!.lastIndex == 0) metadataList!!.add("")
//        metadataList!!.add(cover)
//        playerNotificationManager?.invalidate()
//
//        val metadataIntent = Intent(ACTION_NEW_METADATA)
//        metadataIntent.putStringArrayListExtra(ACTION_NEW_METADATA_EXTRA, metadataList!! as ArrayList<String>)
//        localBroadcastManager.sendBroadcast(metadataIntent)
    }

    fun downloadImage(value: String?): Bitmap? {
        if (value == null) return null
        var bitmap: Bitmap? = null

        try {
            val url: URL = URL(value)
            bitmap = runBlocking { 
                GlobalScope.async { 
                    BitmapFactory.decodeStream(url.openStream())
                }.await()
            }
        } catch (e: Throwable) {
            println(e)
        }

        return bitmap
    }
}

