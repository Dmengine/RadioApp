/*
 *  RadioPlayerService.kt
 *
 *  Created by Ilya Chirkunov <xc@yar.net> on 30.12.2020.
 */

package com.cheebeez.radio_player


import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
import android.content.Intent.getIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
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


/** Service for plays streaming audio content using ExoPlayer. */
 class RadioPlayerService : Service(), Player.EventListener,MetadataOutput,
    PlayerNotificationManager.NotificationListener {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "my_radio_channel_id"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATE_CHANGED = "state_changed"
        const val ACTION_STATE_CHANGED_EXTRA = "state"
        const val ACTION_NEW_METADATA = "matadata_changed"
        const val ACTION_NEW_METADATA_EXTRA = "matadata"
        const val ACTION_NEW_LOADING_DATA = "loading_data_changed"
        const val ACTION_NEW_LOADING_DATA_EXTRA = "loading"
        const val ACTION_NOTIFICATION_DATA = "android.intent.action.MEDIA_BUTTON"
        const val ACTION_NOTIFICATION_EXTRA = "notification"
    }

    var metadataArtwork: Bitmap? = null
    private var defaultArtwork: Bitmap? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var notificationTitle = ""
    private var isForegroundService = false
    private var metadataList: MutableList<String>? = null
    private var localBinder = LocalBinder()
    private var mediaSession:MediaSessionCompat?=null
    private var componentName:ComponentName?=null
    private var br : BroadcastReceiver?=null
    private val player: SimpleExoPlayer by lazy {
        SimpleExoPlayer.Builder(this).build()
    }


    private val localBroadcastManager: LocalBroadcastManager by lazy {
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
        componentName=ComponentName(this,MyBroadCastReceiver::class.java)
        mediaSession =MediaSessionCompat(this,"tag",componentName,null)
        mediaSession?.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
        mediaSession?.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession?.setCallback(MyCallBack())
        mediaSession?.isActive = true
        player.setRepeatMode(Player.REPEAT_MODE_OFF)
        player.addListener(this)
        //player.addMetadataOutput(this)
        // Broadcast receiver for playback state changes, passed to registerReceiver()
        br =MyBroadCastReceiver(localBroadcastManager)
        val filter=IntentFilter("android.intent.action.MEDIA_BUTTON")
        registerReceiver(br,filter)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        player.release()
        mediaSession?.release()
        unregisterReceiver(br)
        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            cancelAll()
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    fun reset(){
        player.stop()
        player.seekTo(0)
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
        createNotificationManager()

        player.stop()
        player.clearMediaItems()
        player.seekTo(0)
        player.addMediaItems(mediaItems)
    }

    fun setDefaultArtwork(image: Bitmap) {
        defaultArtwork = image
        playerNotificationManager?.invalidate()
       createNotificationManager()
    }

    fun play() {
        player.playWhenReady = true
        createNotificationManager()
    }

    fun pause() {
        player.playWhenReady = false
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

    /** Creates a notification manager for background playback. */
    private fun createNotificationManager() {
        try{
//            val mediaDescriptionAdapter = object : MediaDescriptionAdapter {
//                override fun createCurrentContentIntent(player: Player): PendingIntent? {
//
//                    println("INTENT TRIGGERED")
//                    val changePage = Intent("android.intent.action.MAIN")
//                    changePage.component =
//                        ComponentName("com.radiofm.freeradio", "com.radiofm.freeradio.MainActivity")
//                    val p = PendingIntent.getActivity(applicationContext, 0, changePage, 0);
//                    return p;
//                }
//
//                override fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap? {
//                    metadataArtwork = downloadImage(metadataList?.get(2))
//                    if (metadataArtwork != null) callback?.onBitmap(metadataArtwork!!)
//                    return defaultArtwork
//                }
//
//                override fun getCurrentContentTitle(player: Player): String {
//                    return metadataList?.get(0) ?: notificationTitle
//                }
//
//                override fun getCurrentContentText(player: Player): String? {
//                    return metadataList?.get(1) ?: null
//                }
//            }

//            val notificationListener = object : PlayerNotificationManager.NotificationListener {
//                override fun onNotificationPosted(
//                    notificationId: Int,
//                    notification: Notification,
//                    ongoing: Boolean
//                ) {
//                    if (ongoing && !isForegroundService) {
//                        startForeground(notificationId, notification)
//                        isForegroundService = true
//                    }
//                }
//
//                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
//                    stopForeground(true)
//                    isForegroundService = false
//                    stopSelf()
//                }
//            }

            //Notification click intent
            val changePage = Intent("android.intent.action.MAIN")
            changePage.component = ComponentName("com.radiofm.freeradio", "com.radiofm.freeradio.MainActivity")
            val p = PendingIntent.getActivity(applicationContext, 0, changePage, 0);



            //Play broadcast intent
            val play = Intent("android.intent.action.MEDIA_BUTTON")
            play.putExtra(ACTION_NOTIFICATION_EXTRA,"PLAY")
            val playintent = PendingIntent.getBroadcast(this, 10, play,0)

            //Pause broadcast intent
            val pause = Intent("android.intent.action.MEDIA_BUTTON")
            pause.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            pause.putExtra(ACTION_NOTIFICATION_EXTRA,"PAUSE")
            val pauseintent = PendingIntent.getBroadcast(this, 20, pause, 0)

            //Next broadcast intent
            val next = Intent("android.intent.action.MEDIA_BUTTON")
            next.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            next.putExtra(ACTION_NOTIFICATION_EXTRA,"NEXT")
            val nextintent = PendingIntent.getBroadcast(this, 30, next,0)

            //Previous broadcast intent
            val previous = Intent("android.intent.action.MEDIA_BUTTON")
            previous.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            previous.putExtra(ACTION_NOTIFICATION_EXTRA,"PREVIOUS")
            val previousintent = PendingIntent.getBroadcast(this, 40, previous,0)

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                // Show controls on lock screen even when user hides sensitive content.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.exo_styled_controls_play)
                // Add media control buttons that invoke intents in your media service
                .addAction(R.drawable.exo_icon_previous, "Previous", previousintent) // #0
            if(player.playWhenReady){
                notification.addAction(R.drawable.exo_icon_pause, "Pause", pauseintent) // #1
            }else{
                notification.addAction(R.drawable.exo_icon_play, "Play", playintent) // #1
            }
                .setContentIntent(p)
                .addAction(R.drawable.exo_icon_next, "Next", nextintent) // #2
                // Apply the media style template
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0,1,2 /* #1: pause button \*/)
                        .setMediaSession(mediaSession?.sessionToken)
                )
                .setOngoing(true)
                .setContentTitle(notificationTitle)
                .setLargeIcon(defaultArtwork)
            with(NotificationManagerCompat.from(this)) {
                // notificationId is a unique int for each notification that you must define
                notify(NOTIFICATION_ID, notification.build())
            }
        }catch (e: Exception){
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

