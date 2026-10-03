package com.carhud.aaproxy

import com.carhud.app.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver

class CarMediaBrowserService : MediaBrowserServiceCompat() {

    companion object {
        const val ROOT_ID = "root_carhud"
        const val MEDIA_ID_NOW_PLAYING = "carhud_now_playing"
        const val CHANNEL_ID = "carhud_media_channel"
        const val NOTIFICATION_ID = 1001

        var instance: CarMediaBrowserService? = null
            private set
    }

    var mediaSession: MediaSessionCompat? = null
        private set

    private var currentTitle = "YouTube Music"
    private var currentArtist = "THTV"
    private var isPlayingState = false
    private var logoBitmap: Bitmap? = null
    var currentArtworkBitmap: Bitmap? = null
        private set
    var currentArtworkUrl: String? = null
        private set
    private var preparedSystemVoice: Pair<String?, Bundle?>? = null
    private val systemVoiceSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SystemVoiceModule.PREF_ENABLED) {
            preparedSystemVoice = null
            mediaSession?.setPlaybackState(buildPlaybackState(if (isPlayingState) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, currentPositionSec * 1000L).build())
        }
    }




    override fun onCreate() {
        super.onCreate()
        instance = this
        getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(systemVoiceSettingsListener)

        createNotificationChannel()

        logoBitmap = getBitmapFromVector(R.drawable.ic_carhud_media) ?: getBitmapFromVector(R.drawable.ic_carhud_logo)
        currentTitle = CarMediaManager.currentTitle
        currentArtist = CarMediaManager.currentArtist
        currentArtworkBitmap = CarMediaManager.currentArtworkBitmap
        currentArtworkUrl = CarMediaManager.currentArtworkUrl


        // CRITICAL FIX: Immediately call startForeground to prevent ForegroundServiceDidNotStartInTimeException crash!
        try {
            val initialNotif = buildNotification(isPlaying = false, currentTitle, currentArtist)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Exception) {
            AppCrashHandler.logError(e, "CarMediaBrowserServiceStartForeground")
            e.printStackTrace()
        }

        try {
            val session = MediaSessionCompat(this, "CarHudMediaSession").apply {
                @Suppress("DEPRECATION")
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )

                setCallback(object : MediaSessionCompat.Callback() {
                    private var lastClickTime = 0L
                    private var lastClickKeyCode = -1

                    override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                        val ke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }

                        if (ke != null) {
                            if (ke.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) {
                                lastClickKeyCode = -1
                                if (ke.action == android.view.KeyEvent.ACTION_DOWN) {
                                    CarMediaManager.handleSteeringNext(this@CarMediaBrowserService, ke)
                                }
                                return true // Consume UP too; never fall through to onSkipToNext.
                            }
                            if (ke.action == android.view.KeyEvent.ACTION_DOWN) {
                                val prefs = getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                                val now = System.currentTimeMillis()
                                val speed = prefs.getInt(SettingsActivity.KEY_STEERING_DOUBLE_CLICK_SPEED, 500).toLong()
                                val isSameKey = (lastClickKeyCode == ke.keyCode)
                                val isDoubleClick = isSameKey && (now - lastClickTime < speed)
                                lastClickTime = now
                                lastClickKeyCode = ke.keyCode

                                if (isDoubleClick) {
                                    if ((ke.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || ke.keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK) && prefs.getBoolean(SettingsActivity.KEY_STEERING_DOUBLE_PLAY_VOICE, false)) {
                                        CarMediaManager.startGlobalVoiceSearch(this@CarMediaBrowserService)
                                        return true
                                    }
                                }

                                when (ke.keyCode) {
                                    android.view.KeyEvent.KEYCODE_VOICE_ASSIST,
                                    android.view.KeyEvent.KEYCODE_SEARCH,
                                    android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                                        if (prefs.getBoolean(SettingsActivity.KEY_STEERING_VOICE_ENABLED, true)) {
                                            CarMediaManager.startGlobalVoiceSearch(this@CarMediaBrowserService)
                                            return true
                                        }
                                    }
                                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                        val action = prefs.getString(SettingsActivity.KEY_STEERING_PREV_ACTION, "prev")
                                        if (action == "voice") {
                                            CarMediaManager.startGlobalVoiceSearch(this@CarMediaBrowserService)
                                        } else {
                                            CarMediaManager.playPrevious()
                                        }
                                        return true
                                    }
                                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                        CarMediaManager.togglePlayPause(true)
                                        return true
                                    }
                                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                        CarMediaManager.togglePlayPause(false)
                                        return true
                                    }
                                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        CarMediaManager.togglePlayPause()
                                        return true
                                    }
                                }
                            }
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }

                    override fun onPlay() {
                        preparedSystemVoice?.let { prepared ->
                            preparedSystemVoice = null
                            dispatchSystemVoiceRequest(prepared.first, prepared.second)
                            return
                        }
                        CarMediaManager.acquireWakeLock(this@CarMediaBrowserService)
                        CarMediaManager.togglePlayPause(true)
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }

                    override fun onPause() {
                        preparedSystemVoice = null
                        CarMediaManager.togglePlayPause(false)
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    }

                    override fun onSkipToNext() {
                        CarMediaManager.handleSteeringNext(this@CarMediaBrowserService)
                    }

                    override fun onSkipToPrevious() {
                        val prefs = getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                        val action = prefs.getString(SettingsActivity.KEY_STEERING_PREV_ACTION, "prev")
                        if (action == "voice") {
                            CarMediaManager.startGlobalVoiceSearch(this@CarMediaBrowserService)
                        } else {
                            CarMediaManager.playPrevious()
                        }
                    }

                    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                        preparedSystemVoice = null
                        CarMediaManager.acquireWakeLock(this@CarMediaBrowserService)
                        when (mediaId) {
                            "media_next" -> onSkipToNext()
                            "media_prev" -> onSkipToPrevious()
                            "media_reload" -> {
                                CarMediaManager.reload()
                            }
                            "media_home" -> {
                                CarMediaManager.loadUrl("https://m.youtube.com")
                            }
                            "media_open_carhud" -> {
                                try {
                                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    }
                                    if (launchIntent != null) startActivity(launchIntent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            else -> {
                                CarMediaManager.togglePlayPause(true)
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                            }
                        }
                    }

                    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                        preparedSystemVoice = null
                        dispatchSystemVoiceRequest(query, extras)
                    }

                    override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
                        if (SystemVoiceModule.isEnabled(this@CarMediaBrowserService)) {
                            preparedSystemVoice = query to extras?.let { Bundle(it) }
                        } else {
                            preparedSystemVoice = null
                            reportSystemVoiceError(SystemVoiceModule.Result.DISABLED.message)
                        }
                    }

                    override fun onPrepare() {
                        if (SystemVoiceModule.isEnabled(this@CarMediaBrowserService)) preparedSystemVoice = null to null
                    }

                    override fun onCustomAction(action: String?, extras: Bundle?) {
                        when (action) {
                            "ACTION_PREV" -> onSkipToPrevious()
                            "ACTION_NEXT" -> onSkipToNext()
                            "ACTION_RELOAD" -> CarMediaManager.reload()
                            "ACTION_HOME" -> CarMediaManager.loadUrl("https://m.youtube.com")
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        CarMediaManager.seekTo(pos / 1000)
                        updatePlaybackProgress((pos / 1000).toInt(), currentDurationSec)
                    }
                })

                isActive = true
            }

            mediaSession = session
            sessionToken = session.sessionToken

            val stateBuilder = buildPlaybackState(PlaybackStateCompat.STATE_PAUSED, 0L)
            session.setPlaybackState(stateBuilder.build())
            val metaBuilder = buildMetadata(currentTitle, currentArtist, 0L)
            session.setMetadata(metaBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            val notif = buildNotification(isPlayingState, currentTitle, currentArtist)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    private fun getBitmapFromVector(drawableId: Int): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(this, drawableId) ?: return null
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(96),
                drawable.intrinsicHeight.coerceAtLeast(96),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private var lastState = -1
    private var lastTitle = ""
    private var lastArtist = ""
    private var currentPositionSec = 0
    private var currentDurationSec = 0

    private fun buildPlaybackState(state: Int, positionMs: Long): PlaybackStateCompat.Builder {
        val systemVoiceActions = if (SystemVoiceModule.isEnabled(this)) {
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or PlaybackStateCompat.ACTION_PREPARE
        } else 0L
        val prevCustom = PlaybackStateCompat.CustomAction.Builder(
            "ACTION_PREV", "Previous", android.R.drawable.ic_media_previous
        ).build()
        val nextCustom = PlaybackStateCompat.CustomAction.Builder(
            "ACTION_NEXT", "Next", android.R.drawable.ic_media_next
        ).build()

        val playbackSpeed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f
        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                systemVoiceActions or
                PlaybackStateCompat.ACTION_STOP
            )
            .addCustomAction(prevCustom)
            .addCustomAction(nextCustom)
            .setState(state, if (positionMs >= 0) positionMs else PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, playbackSpeed)
    }

    private fun buildMetadata(title: String, artist: String, durationMs: Long): MediaMetadataCompat.Builder {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "YouTube")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)

        if (durationMs > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        }

        if (currentArtworkBitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtworkBitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtworkBitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, currentArtworkBitmap)
        } else if (logoBitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
        }

        if (!currentArtworkUrl.isNullOrBlank()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentArtworkUrl)
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentArtworkUrl)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, currentArtworkUrl)
        }
        return builder
    }

    fun updateArtwork(bitmap: Bitmap?, url: String?) {
        currentArtworkBitmap = bitmap
        if (!url.isNullOrBlank()) {
            currentArtworkUrl = url
        }
        val session = mediaSession ?: return
        try {
            val metaBuilder = buildMetadata(currentTitle, currentArtist, currentDurationSec * 1000L)
            session.setMetadata(metaBuilder.build())

            val notif = buildNotification(isPlayingState, currentTitle, currentArtist)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notif)

            notifyChildrenChanged(ROOT_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private var lastReportedSec = -1

    fun updatePlaybackProgress(curSec: Int, durSec: Int) {
        val durationChanged = (durSec > 0 && durSec != currentDurationSec)
        currentPositionSec = curSec
        currentDurationSec = durSec
        val session = mediaSession ?: return
        try {
            val shouldSync = durationChanged || lastReportedSec < 0 || Math.abs(curSec - lastReportedSec) >= 5
            if (shouldSync) {
                lastReportedSec = curSec
                val isCurrentlyPlaying = CarMediaManager.isPlaying
                isPlayingState = isCurrentlyPlaying
                val state = if (isCurrentlyPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                val stateBuilder = buildPlaybackState(state, curSec * 1000L)
                session.setPlaybackState(stateBuilder.build())
            }

            if (durationChanged) {
                val metaBuilder = buildMetadata(currentTitle, currentArtist, durSec * 1000L)
                session.setMetadata(metaBuilder.build())
            }
        } catch (e: Exception) {}
    }

    fun updatePlaybackState(state: Int, title: String? = null, artist: String? = null) {
        val newTitle = if (!title.isNullOrBlank()) title else currentTitle
        val newArtist = if (!artist.isNullOrBlank()) artist else currentArtist

        if (lastState == state && lastTitle == newTitle && lastArtist == newArtist) {
            return
        }

        lastState = state
        lastTitle = newTitle
        lastArtist = newArtist
        currentTitle = newTitle
        currentArtist = newArtist
        isPlayingState = (state == PlaybackStateCompat.STATE_PLAYING)

        val session = mediaSession ?: return

        try {
            val stateBuilder = buildPlaybackState(state, currentPositionSec * 1000L)
            session.setPlaybackState(stateBuilder.build())

            val metaBuilder = buildMetadata(currentTitle, currentArtist, currentDurationSec * 1000L)
            session.setMetadata(metaBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val notif = buildNotification(isPlayingState, currentTitle, currentArtist)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            notifyChildrenChanged(ROOT_ID)
        } catch (e: Exception) {}
    }

    private fun buildNotification(isPlaying: Boolean, title: String, artist: String): Notification {
        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Previous",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Next",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bar_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(currentArtworkBitmap ?: logoBitmap)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CARHUD YouTube Media",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Phát nhạc YouTube chạy ngầm trên Android Auto"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val rootExtras = Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putBoolean("android.service.media.extra.RECENT", true)
            putBoolean("android.service.media.extra.OFFLINE", false)
        }
        return BrowserRoot(ROOT_ID, rootExtras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val title = if (currentTitle.isNotBlank()) currentTitle else "YouTube Media (THTV)"
        val artist = if (currentArtist.isNotBlank()) currentArtist else "Chạm để nghe hoặc điều khiển"
        val art = currentArtworkBitmap ?: logoBitmap

        // 1. Current track / Play / Pause
        val descNowPlaying = MediaDescriptionCompat.Builder()
            .setMediaId(MEDIA_ID_NOW_PLAYING)
            .setTitle(if (isPlayingState) "▶ $title" else "⏸ $title")
            .setSubtitle(artist)
            .setDescription("Chạm để Phát / Tạm dừng")
            .setIconBitmap(art)
            .apply {
                if (!currentArtworkUrl.isNullOrBlank()) {
                    setIconUri(android.net.Uri.parse(currentArtworkUrl))
                }
            }
            .build()
        items.add(MediaBrowserCompat.MediaItem(descNowPlaying, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        // 2. Next track
        val descNext = MediaDescriptionCompat.Builder()
            .setMediaId("media_next")
            .setTitle("Bài tiếp theo ⏭")
            .setSubtitle("Chuyển sang video kế tiếp")
            .setIconBitmap(art)
            .build()
        items.add(MediaBrowserCompat.MediaItem(descNext, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        // 3. Previous track
        val descPrev = MediaDescriptionCompat.Builder()
            .setMediaId("media_prev")
            .setTitle("Bài trước đó ⏮")
            .setSubtitle("Quay lại bài trước hoặc trang trước")
            .setIconBitmap(art)
            .build()
        items.add(MediaBrowserCompat.MediaItem(descPrev, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        // 4. Reload
        val descReload = MediaDescriptionCompat.Builder()
            .setMediaId("media_reload")
            .setTitle("Tải lại video 🔄")
            .setSubtitle("Làm mới trang video hiện tại")
            .setIconBitmap(art)
            .build()
        items.add(MediaBrowserCompat.MediaItem(descReload, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        // 5. YouTube Home
        val descHome = MediaDescriptionCompat.Builder()
            .setMediaId("media_home")
            .setTitle("Trang chủ YouTube 🏠")
            .setSubtitle("Mở trang chủ m.youtube.com")
            .setIconBitmap(art)
            .build()
        items.add(MediaBrowserCompat.MediaItem(descHome, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        // 6. Open THTV
        val descOpenCarHUD = MediaDescriptionCompat.Builder()
            .setMediaId("media_open_carhud")
            .setTitle("Mở THTV 🚗")
            .setSubtitle("Chuyển sang buồng lái THTV")
            .setIconBitmap(logoBitmap)
            .build()
        items.add(MediaBrowserCompat.MediaItem(descOpenCarHUD, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        result.sendResult(items)
    }


    override fun onDestroy() {
        getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(systemVoiceSettingsListener)
        preparedSystemVoice = null
        CarMediaManager.cancelPendingSteeringNext()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {}
        CarMediaManager.releaseWakeLock()
        mediaSession?.release()
        instance = null
        super.onDestroy()
    }

    private fun dispatchSystemVoiceRequest(query: String?, extras: Bundle?) {
        val result = SystemVoiceModule.submit(this, query, extras)
        if (result == SystemVoiceModule.Result.ACCEPTED) {
            updatePlaybackState(PlaybackStateCompat.STATE_CONNECTING)
        } else reportSystemVoiceError(result.message)
    }

    internal fun reportSystemVoiceError(message: String) {
        lastState = PlaybackStateCompat.STATE_ERROR
        mediaSession?.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_ERROR, currentPositionSec * 1000L)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message).build())
    }
}
