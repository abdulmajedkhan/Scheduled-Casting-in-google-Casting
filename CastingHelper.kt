package com.gdelataillade.alarm.casting

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
// Remove the problematic "Runnableimport" line and add these properly:
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


// Enhanced data classes for complete audio state management - PRESERVED
data class CastDevice(val id: String, val name: String)

data class PlaybackState(
        val isPlaying: Boolean,
        val position: Long,
        val duration: Long,
        val volume: Double,
        val isMuted: Boolean,
        val currentMediaUrl: String?,
        val currentTitle: String?,
        val currentArtist: String?,
        val currentAlbum: String?,
        val currentArtworkUrl: String?,
        val playerState: PlayerState,
        val playbackRate: Double,
        val canSeek: Boolean,
        val canPause: Boolean,
        val canPlay: Boolean,
        val canControlVolume: Boolean,
        val isBuffering: Boolean,
        val isLoading: Boolean,
        val hasEnded: Boolean,
        val errorMessage: String?,
        val repeatMode: RepeatMode,
        val shuffleMode: Boolean,
        val streamType: StreamType,
        val contentType: String?,
        val queueItems: List<QueueItem>?,
        val currentQueueItemId: Int?
)

data class QueueItem(
        val itemId: Int,
        val mediaUrl: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val artworkUrl: String?,
        val duration: Long
)

enum class PlayerState {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ENDED,
    ERROR
}

enum class RepeatMode {
    OFF,
    ALL,
    SINGLE,
    ALL_AND_SHUFFLE
}

enum class StreamType {
    BUFFERED,
    LIVE,
    NONE
}

sealed class CastingState {
    object Idle : CastingState()
    object Discovering : CastingState()
    object Connecting : CastingState()
    object Connected : CastingState()
    object Casting : CastingState()
    object Buffering : CastingState()
    object Paused : CastingState()
    object Loading : CastingState()
    data class Error(val message: String) : CastingState()
    data class Ended(val mediaUrl: String) : CastingState()
}

interface CastingStateListener {
    fun onCastingStateChanged(state: CastingState)
    fun onCastStarted()
    fun onCastFailed(error: String)
    fun onCastStopped()
    fun onPlaybackStateUpdated(state: PlaybackState)
}

class CastingHelper
private constructor(private val context: Context, private var channel: MethodChannel? = null) {
    companion object {
        @Volatile private var INSTANCE: CastingHelper? = null
        private const val TAG = "CastingHelper"
        private const val PREFS_NAME = "casting_prefs"
        private const val KEY_LAST_DEVICE_ID = "last_device_id"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"

        // Configuration constants
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val RECONNECT_DELAY_MS = 2000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val PLAYBACK_POLLING_INTERVAL_MS = 500L
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val MEDIA_LOAD_TIMEOUT_MS = 15000L
        private const val PLAYBACK_VERIFICATION_DELAY_MS = 3000L

        fun getInstance(context: Context, channel: MethodChannel? = null): CastingHelper {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE
                                ?: CastingHelper(context.applicationContext, channel).also {
                                    INSTANCE = it
                                }
                    }
                            .apply { if (channel != null) setChannel(channel) }
        }
    }

    // Core Casting components
    private val castContext: CastContext? by lazy {
        try {
            CastContext.getSharedInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CastContext", e)
            null
        }
    }

    private val mediaRouter: MediaRouter by lazy { MediaRouter.getInstance(context) }
    private val mediaRouteSelector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
                .addControlCategory(
                        CastMediaControlIntent.categoryForCast(
                                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                        )
                )
                .build()
    }

    // State management
    private val handler = Handler(Looper.getMainLooper())
    private val availableDevices = CopyOnWriteArrayList<CastDevice>()
    private val deviceListeners = CopyOnWriteArrayList<(List<CastDevice>) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<WeakReference<CastingStateListener>>()
    private var currentState: CastingState = CastingState.Idle
    private val isCastingInProgress = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var currentReconnectRunnable: Runnable? = null
    private var progressUpdateRunnable: Runnable? = null

    // Device discovery stream
    private val _devicesStream = MutableStateFlow<List<Map<String, String>>>(emptyList())
    val devicesStream: StateFlow<List<Map<String, String>>> = _devicesStream.asStateFlow()

    // Complete audio state tracking
    private var currentPlaybackState = createDefaultPlaybackState()

    // Session management
    private var session: CastSession? = null
    private val sessionListener = createSessionListener()

    // Alarm-specific tracking
    private var alarmCastStartTime: Long = 0
    private var currentPollingRunnable: Runnable? = null
    private var currentMediaLoadCallback: RemoteMediaClient.Callback? = null
    private var alarmSequenceCallback: RemoteMediaClient.Callback? = null
    private var alarmCompletionListener: (() -> Unit)? = null
    private var alarmErrorListener: ((String) -> Unit)? = null

    // In-app playback tracking
    private var currentInAppCallback: RemoteMediaClient.Callback? = null
    private var inAppCompletionListener: (() -> Unit)? = null

    private fun createDefaultPlaybackState(): PlaybackState {
        return PlaybackState(
                isPlaying = false,
                position = 0L,
                duration = 0L,
                volume = 1.0,
                isMuted = false,
                currentMediaUrl = null,
                currentTitle = null,
                currentArtist = null,
                currentAlbum = null,
                currentArtworkUrl = null,
                playerState = PlayerState.IDLE,
                playbackRate = 1.0,
                canSeek = false,
                canPause = false,
                canPlay = false,
                canControlVolume = true,
                isBuffering = false,
                isLoading = false,
                hasEnded = false,
                errorMessage = null,
                repeatMode = RepeatMode.OFF,
                shuffleMode = false,
                streamType = StreamType.NONE,
                contentType = null,
                queueItems = null,
                currentQueueItemId = null
        )
    }

    private fun createSessionListener(): SessionManagerListener<CastSession> {
        return object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                this@CastingHelper.session = session
                updateState(CastingState.Connected)
                Log.d(TAG, "Cast session started: $sessionId")
                notifyFlutter("onSessionRestored", null)
                startPlaybackStateUpdates()
                setupDefaultMediaCallbacks()
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                this@CastingHelper.session = session
                updateState(CastingState.Connected)
                Log.d(TAG, "Cast session resumed")
                notifyFlutter("onSessionRestored", null)
                startPlaybackStateUpdates()
                setupDefaultMediaCallbacks()
            }

            override fun onSessionEnded(session: CastSession, error: Int) {
                this@CastingHelper.session = null
                updateState(CastingState.Idle)
                Log.d(TAG, "Cast session ended with error: $error")
                notifyFlutter("onSessionEnded", error)
                stopPlaybackStateUpdates()
                resetPlaybackState()
                cleanupAlarmCasting()
                cleanupInAppCasting()
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                updateState(CastingState.Error("Session start failed: $error"))
                Log.w(TAG, "Session start failed: $error")
                cleanupAlarmCasting()
                cleanupInAppCasting()
            }

            override fun onSessionSuspended(session: CastSession, reason: Int) {
                Log.d(TAG, "Session suspended: $reason")
            }

            override fun onSessionStarting(session: CastSession) {
                updateState(CastingState.Connecting)
            }

            override fun onSessionEnding(session: CastSession) {
                // No state change yet, wait for onSessionEnded
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) {
                updateState(CastingState.Connecting)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                updateState(CastingState.Error("Session resume failed: $error"))
                cleanupAlarmCasting()
                cleanupInAppCasting()
            }
        }
    }

    private fun setupDefaultMediaCallbacks() {
        session?.remoteMediaClient?.registerCallback(
                object : RemoteMediaClient.Callback() {
                    override fun onStatusUpdated() {
                        // Update state for all callbacks
                        updatePlaybackStateFromRemote()
                    }

                    override fun onMetadataUpdated() {
                        // Metadata updates for all
                    }
                }
        )
    }

    // Media router callback for device discovery
    private val mediaRouterCallback =
            object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    Log.d(TAG, "Route added: ${route.name}")
                    updateDeviceList()
                }

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    Log.d(TAG, "Route removed: ${route.name}")
                    updateDeviceList()
                }

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    updateDeviceList()
                }

                override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    Log.d(TAG, "Route selected: ${route.name}")
                    updateState(CastingState.Connecting)
                }

                override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    Log.d(TAG, "Route unselected: ${route.name}")
                    // Don't immediately go to Idle, wait for session callbacks
                }
            }

    init {
        initializeCastFramework()
    }

    private fun initializeCastFramework() {
        try {
            castContext?.sessionManager?.addSessionManagerListener(
                    sessionListener,
                    CastSession::class.java
            )
            startDiscovery()
            Log.d(TAG, "CastingHelper initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Cast framework", e)
            updateState(CastingState.Error("Initialization failed: ${e.message}"))
        }
    }

    // ==================== DEVICE DISCOVERY METHODS ====================

    fun getAvailableDevices(): List<Map<String, String>> {
        return availableDevices.map { device ->
            mapOf("id" to device.id, "name" to device.name, "friendlyName" to device.name)
        }
    }

    private fun updateDeviceList() {
        val castCategory =
                CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )

        val devices =
                mediaRouter
                        .routes
                        .filter { it.supportsControlCategory(castCategory) }
                        .mapNotNull { route ->
                            try {
                                val castDevice =
                                        com.google.android.gms.cast.CastDevice.getFromBundle(
                                                route.extras
                                        )
                                val deviceId =
                                        castDevice?.deviceId ?: route.id.substringAfterLast(":")
                                val deviceName = castDevice?.friendlyName ?: route.name.toString()

                                CastDevice(deviceId, deviceName)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse CastDevice for route ${route.name}", e)
                                null
                            }
                        }
                        .distinctBy { it.id }

        availableDevices.clear()
        availableDevices.addAll(devices)

        // Update the stream
        val deviceMaps =
                devices.map { device ->
                    mapOf("id" to device.id, "name" to device.name, "friendlyName" to device.name)
                }
        _devicesStream.value = deviceMaps

        Log.d(TAG, "Discovered ${availableDevices.size} cast devices")

        // Notify device listeners
        notifyDeviceListeners(availableDevices)

        // Notify Flutter
        notifyFlutter("onDevicesChanged", deviceMaps)
    }

    private fun notifyDeviceListeners(devices: List<CastDevice>) {
        val iterator = deviceListeners.iterator()
        while (iterator.hasNext()) {
            try {
                iterator.next().invoke(devices)
            } catch (e: Exception) {
                Log.w(TAG, "Error in device listener, removing", e)
                iterator.remove()
            }
        }
    }

    fun startDiscovery() {
        Log.d(TAG, "Starting Cast discovery")
        try {
            mediaRouter.addCallback(
                    mediaRouteSelector,
                    mediaRouterCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )
            updateState(CastingState.Discovering)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stopDiscovery() {
        try {
            mediaRouter.removeCallback(mediaRouterCallback)
            Log.d(TAG, "Stopped Cast discovery")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        }
    }

    fun connectToDevice(deviceId: String): Boolean {
        val route = findRouteByDeviceId(deviceId)

        return if (route != null) {
            try {
                mediaRouter.selectRoute(route)
                saveDevice(deviceId, route.name.toString())
                Log.d(TAG, "Connecting to device: ${route.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to device", e)
                updateState(CastingState.Error("Connection failed: ${e.message}"))
                false
            }
        } else {
            Log.w(TAG, "Device not found: $deviceId")
            false
        }
    }

    // ==================== COMPLETE AUDIO STATE MANAGEMENT ====================

    private fun startPlaybackStateUpdates() {
        stopPlaybackStateUpdates()

        progressUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    updatePlaybackStateFromRemote()
                    handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in playback state update", e)
                }
            }
        }

        handler.post(progressUpdateRunnable!!)
        Log.d(TAG, "Started playback state updates")
    }

    private fun stopPlaybackStateUpdates() {
        progressUpdateRunnable?.let {
            handler.removeCallbacks(it)
            progressUpdateRunnable = null
        }
    }

    private fun resetPlaybackState() {
        currentPlaybackState = createDefaultPlaybackState()
        notifyPlaybackStateUpdate()
    }

    private fun updatePlaybackStateFromRemote() {
        try {
            val remoteClient = session?.remoteMediaClient ?: return
            val mediaInfo = remoteClient.mediaInfo
            val mediaStatus = remoteClient.mediaStatus

            val isPlaying = remoteClient.isPlaying
            val position = remoteClient.approximateStreamPosition
            val duration = mediaInfo?.streamDuration ?: 0L

            // Determine player state from media status
            val playerState = determinePlayerState(mediaStatus)

            val newState =
                    createUpdatedPlaybackState(
                            isPlaying = isPlaying,
                            position = position,
                            duration = duration,
                            mediaInfo = mediaInfo,
                            mediaStatus = mediaStatus,
                            playerState = playerState
                    )

            if (newState != currentPlaybackState) {
                currentPlaybackState = newState
                notifyPlaybackStateUpdate()
                updateCastingStateFromPlayerState(playerState)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update playback state", e)
        }
    }

    private fun determinePlayerState(mediaStatus: MediaStatus?): PlayerState {
        return when {
            mediaStatus == null -> PlayerState.IDLE
            mediaStatus.playerState == MediaStatus.PLAYER_STATE_BUFFERING -> PlayerState.BUFFERING
            mediaStatus.playerState == MediaStatus.PLAYER_STATE_PLAYING -> PlayerState.PLAYING
            mediaStatus.playerState == MediaStatus.PLAYER_STATE_PAUSED -> PlayerState.PAUSED
            mediaStatus.playerState == MediaStatus.PLAYER_STATE_IDLE -> {
                when (mediaStatus.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> PlayerState.ENDED
                    else -> PlayerState.IDLE
                }
            }
            else -> PlayerState.IDLE
        }
    }

    private fun createUpdatedPlaybackState(
            isPlaying: Boolean,
            position: Long,
            duration: Long,
            mediaInfo: MediaInfo?,
            mediaStatus: MediaStatus?,
            playerState: PlayerState
    ): PlaybackState {
        return currentPlaybackState.copy(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                volume = session?.volume ?: currentPlaybackState.volume,
                isMuted = session?.isMute ?: currentPlaybackState.isMuted,
                canControlVolume = true,
                currentMediaUrl = mediaInfo?.contentId ?: currentPlaybackState.currentMediaUrl,
                currentTitle = mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)
                                ?: currentPlaybackState.currentTitle,
                currentArtist = mediaInfo?.metadata?.getString(MediaMetadata.KEY_ARTIST)
                                ?: currentPlaybackState.currentArtist,
                currentAlbum = mediaInfo?.metadata?.getString(MediaMetadata.KEY_ALBUM_TITLE)
                                ?: currentPlaybackState.currentAlbum,
                contentType = mediaInfo?.contentType ?: currentPlaybackState.contentType,
                playerState = playerState,
                playbackRate = mediaStatus?.playbackRate ?: 1.0,
                isBuffering = playerState == PlayerState.BUFFERING,
                isLoading = playerState == PlayerState.LOADING,
                hasEnded = playerState == PlayerState.ENDED,
                canSeek = duration > 0,
                canPause = isPlaying,
                canPlay = !isPlaying && duration > 0,
                repeatMode = RepeatMode.OFF,
                shuffleMode = false,
                streamType =
                        when (mediaInfo?.streamType) {
                            MediaInfo.STREAM_TYPE_BUFFERED -> StreamType.BUFFERED
                            MediaInfo.STREAM_TYPE_LIVE -> StreamType.LIVE
                            else -> StreamType.NONE
                        },
                errorMessage = if (playerState == PlayerState.ERROR) "Playback error" else null
        )
    }

    private fun updateCastingStateFromPlayerState(playerState: PlayerState) {
        val newCastingState =
                when (playerState) {
                    PlayerState.LOADING -> CastingState.Loading
                    PlayerState.BUFFERING -> CastingState.Buffering
                    PlayerState.PLAYING -> CastingState.Casting
                    PlayerState.PAUSED -> CastingState.Paused
                    PlayerState.ENDED ->
                            CastingState.Ended(currentPlaybackState.currentMediaUrl ?: "")
                    PlayerState.ERROR -> CastingState.Error("Playback error")
                    PlayerState.IDLE -> {
                        if (isSessionActive()) CastingState.Connected else CastingState.Idle
                    }
                    else -> currentState
                }

        if (newCastingState != currentState) {
            updateState(newCastingState)
        }
    }

    private fun notifyPlaybackStateUpdate() {
        notifyStateListeners { listener -> listener.onPlaybackStateUpdated(currentPlaybackState) }

        notifyFlutter("onPlaybackStateChanged", createPlaybackStateMap())
    }

    private fun createPlaybackStateMap(): Map<String, Any?> {
        return mapOf(
                "isPlaying" to currentPlaybackState.isPlaying,
                "position" to currentPlaybackState.position,
                "duration" to currentPlaybackState.duration,
                "progress" to calculateProgress(),
                "volume" to currentPlaybackState.volume,
                "isMuted" to currentPlaybackState.isMuted,
                "currentMediaUrl" to currentPlaybackState.currentMediaUrl,
                "currentTitle" to currentPlaybackState.currentTitle,
                "currentArtist" to currentPlaybackState.currentArtist,
                "currentAlbum" to currentPlaybackState.currentAlbum,
                "playerState" to currentPlaybackState.playerState.name,
                "isBuffering" to currentPlaybackState.isBuffering,
                "isLoading" to currentPlaybackState.isLoading,
                "hasEnded" to currentPlaybackState.hasEnded,
                "playbackRate" to currentPlaybackState.playbackRate,
                "canSeek" to currentPlaybackState.canSeek,
                "canPause" to currentPlaybackState.canPause,
                "canPlay" to currentPlaybackState.canPlay,
                "repeatMode" to currentPlaybackState.repeatMode.name,
                "shuffleMode" to currentPlaybackState.shuffleMode,
                "streamType" to currentPlaybackState.streamType.name,
                "errorMessage" to currentPlaybackState.errorMessage
        )
    }

    private fun calculateProgress(): Double {
        return if (currentPlaybackState.duration > 0) {
            currentPlaybackState.position.toDouble() / currentPlaybackState.duration.toDouble()
        } else 0.0
    }

    // ==================== IN-APP AUDIO METHODS ====================

    fun playAudio(
            url: String,
            title: String = "Audio",
            artist: String? = null,
            album: String? = null,
            autoPlay: Boolean = true,
            position: Long = 0L,
            onCompletion: (() -> Unit)? = null
    ): Boolean {
        if (!isSessionActive()) {
            Log.w(TAG, "No active session for audio playback")
            return false
        }

        return try {
            val remoteClient = session?.remoteMediaClient
            if (remoteClient == null) {
                Log.w(TAG, "No remote media client available")
                return false
            }

            // Clean up previous callbacks
            cleanupInAppCasting()

            inAppCompletionListener = onCompletion

            val metadata =
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                        putString(MediaMetadata.KEY_TITLE, title)
                        artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
                        album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
                    }

            val mediaInfo =
                    MediaInfo.Builder(url)
                            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setContentType("audio/mp3")
                            .setMetadata(metadata)
                            .build()

            val request =
                    MediaLoadRequestData.Builder()
                            .setMediaInfo(mediaInfo)
                            .setAutoplay(autoPlay)
                            .setCurrentTime(position)
                            .build()

            // Register callback for completion detection
            currentInAppCallback =
                    object : RemoteMediaClient.Callback() {
                        override fun onStatusUpdated() {
                            val mediaStatus = remoteClient.mediaStatus
                            if (mediaStatus?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                                            mediaStatus.idleReason ==
                                                    MediaStatus.IDLE_REASON_FINISHED
                            ) {
                                Log.d(TAG, "In-app audio completed: $title")
                                inAppCompletionListener?.invoke()
                                cleanupInAppCasting()
                            }
                        }
                    }
            remoteClient.registerCallback(currentInAppCallback!!)

            remoteClient.load(request)
            updateState(CastingState.Casting)

            updateCurrentPlaybackState(url, title, artist, album, autoPlay)

            Log.d(TAG, "Playing audio: $title ($url)")
            notifyFlutter(
                    "onAudioStarted",
                    mapOf("title" to title, "url" to url, "artist" to artist, "album" to album)
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            updateState(CastingState.Error("Playback failed: ${e.message}"))
            false
        }
    }

    private fun updateCurrentPlaybackState(
            url: String,
            title: String,
            artist: String?,
            album: String?,
            isPlaying: Boolean
    ) {
        currentPlaybackState =
                currentPlaybackState.copy(
                        currentMediaUrl = url,
                        currentTitle = title,
                        currentArtist = artist,
                        currentAlbum = album,
                        isPlaying = isPlaying
                )
        notifyPlaybackStateUpdate()
    }

    fun pause(): Boolean {
        return try {
            session?.remoteMediaClient?.pause()
            currentPlaybackState = currentPlaybackState.copy(isPlaying = false)
            notifyPlaybackStateUpdate()
            Log.d(TAG, "Playback paused")
            notifyFlutter("onPlaybackPaused", null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause playback", e)
            false
        }
    }

    fun resume(): Boolean {
        return try {
            session?.remoteMediaClient?.play()
            currentPlaybackState = currentPlaybackState.copy(isPlaying = true)
            notifyPlaybackStateUpdate()
            Log.d(TAG, "Playback resumed")
            notifyFlutter("onPlaybackResumed", null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume playback", e)
            false
        }
    }

    fun stop(): Boolean {
        return try {
            session?.remoteMediaClient?.stop()
            isCastingInProgress.set(false)
            updateState(CastingState.Connected)

            currentPlaybackState =
                    currentPlaybackState.copy(
                            isPlaying = false,
                            position = 0L,
                            currentMediaUrl = null,
                            currentTitle = null,
                            currentArtist = null,
                            currentAlbum = null
                    )
            notifyPlaybackStateUpdate()

            cleanupInAppCasting()

            Log.d(TAG, "Playback stopped")
            notifyFlutter("onPlaybackStopped", null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop playback", e)
            false
        }
    }

    fun seekTo(position: Long): Boolean {
        return try {
            session?.remoteMediaClient?.seek(position)
            currentPlaybackState = currentPlaybackState.copy(position = position)
            notifyPlaybackStateUpdate()
            Log.d(TAG, "Seeked to ${position}ms")
            notifyFlutter("onSeeked", position)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seek", e)
            false
        }
    }

    fun seekToPercentage(percentage: Double): Boolean {
        val duration = currentPlaybackState.duration
        if (duration <= 0L) return false

        val position = (duration * percentage.coerceIn(0.0, 1.0)).toLong()
        return seekTo(position)
    }

    fun togglePlayPause(): Boolean {
        return if (isPlaying()) {
            pause()
        } else {
            resume()
        }
    }

    fun isPlaying(): Boolean {
        return try {
            session?.remoteMediaClient?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    fun getCurrentPosition(): Long {
        return try {
            session?.remoteMediaClient?.approximateStreamPosition ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current position", e)
            0L
        }
    }

    fun getDuration(): Long {
        return try {
            session?.remoteMediaClient?.mediaInfo?.streamDuration ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration", e)
            0L
        }
    }

    fun setMuted(muted: Boolean): Boolean {
        return try {
            session?.setMute(muted)
            currentPlaybackState = currentPlaybackState.copy(isMuted = muted)
            notifyPlaybackStateUpdate()
            Log.d(TAG, if (muted) "Muted" else "Unmuted")
            notifyFlutter("onMuteChanged", muted)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mute", e)
            false
        }
    }

    fun toggleMute(): Boolean {
        return setMuted(!currentPlaybackState.isMuted)
    }

    fun setVolume(level: Double) {
        try {
            val clamped = level.coerceIn(0.0, 1.0)
            session?.setVolume(clamped)

            // Save volume for persistence
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("saved_volume", clamped.toFloat())
                    .apply()

            Log.d(TAG, "Volume set to: $clamped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }

    fun getVolume(): Double {
        return try {
            session?.volume
                    ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getFloat("saved_volume", 1.0f)
                            .toDouble()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get volume", e)
            1.0
        }
    }

    fun setRepeatMode(mode: RepeatMode): Boolean {
        return try {
            currentPlaybackState = currentPlaybackState.copy(repeatMode = mode)
            notifyPlaybackStateUpdate()
            Log.d(TAG, "Repeat mode set to: $mode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeat mode", e)
            false
        }
    }

    fun setShuffleMode(shuffle: Boolean): Boolean {
        return try {
            currentPlaybackState = currentPlaybackState.copy(shuffleMode = shuffle)
            notifyPlaybackStateUpdate()
            Log.d(TAG, "Shuffle mode set to: $shuffle")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set shuffle mode", e)
            false
        }
    }

    fun setPlaybackRate(rate: Double): Boolean {
        return try {
            currentPlaybackState = currentPlaybackState.copy(playbackRate = rate.coerceIn(0.5, 2.0))
            notifyPlaybackStateUpdate()
            Log.d(TAG, "Playback rate set to: ${rate}x")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set playback rate", e)
            false
        }
    }

    fun next(): Boolean {
        return try {
            session?.remoteMediaClient?.queueNext(null)
            Log.d(TAG, "Skipped to next item")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to skip to next", e)
            false
        }
    }

    fun previous(): Boolean {
        return try {
            session?.remoteMediaClient?.queuePrev(null)
            Log.d(TAG, "Skipped to previous item")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to skip to previous", e)
            false
        }
    }

    fun getPlaybackState(): PlaybackState {
        return currentPlaybackState
    }

    fun getCompleteAudioState(): Map<String, Any?> {
        return mapOf(
                "isPlaying" to currentPlaybackState.isPlaying,
                "position" to currentPlaybackState.position,
                "duration" to currentPlaybackState.duration,
                "progress" to calculateProgress(),
                "volume" to currentPlaybackState.volume,
                "isMuted" to currentPlaybackState.isMuted,
                "canControlVolume" to currentPlaybackState.canControlVolume,
                "currentMediaUrl" to currentPlaybackState.currentMediaUrl,
                "currentTitle" to currentPlaybackState.currentTitle,
                "currentArtist" to currentPlaybackState.currentArtist,
                "currentAlbum" to currentPlaybackState.currentAlbum,
                "currentArtworkUrl" to currentPlaybackState.currentArtworkUrl,
                "contentType" to currentPlaybackState.contentType,
                "playerState" to currentPlaybackState.playerState.name,
                "isBuffering" to currentPlaybackState.isBuffering,
                "isLoading" to currentPlaybackState.isLoading,
                "hasEnded" to currentPlaybackState.hasEnded,
                "playbackRate" to currentPlaybackState.playbackRate,
                "canSeek" to currentPlaybackState.canSeek,
                "canPause" to currentPlaybackState.canPause,
                "canPlay" to currentPlaybackState.canPlay,
                "repeatMode" to currentPlaybackState.repeatMode.name,
                "shuffleMode" to currentPlaybackState.shuffleMode,
                "streamType" to currentPlaybackState.streamType.name,
                "queueItems" to
                        currentPlaybackState.queueItems?.map { item ->
                            mapOf(
                                    "itemId" to item.itemId,
                                    "mediaUrl" to item.mediaUrl,
                                    "title" to item.title,
                                    "artist" to item.artist,
                                    "album" to item.album,
                                    "artworkUrl" to item.artworkUrl,
                                    "duration" to item.duration
                            )
                        },
                "currentQueueItemId" to currentPlaybackState.currentQueueItemId,
                "errorMessage" to currentPlaybackState.errorMessage,
                "isConnected" to isSessionActive(),
                "isCasting" to isCasting(),
                "castingState" to currentState.toString()
        )
    }

    // ==================== ALARM CASTING METHODS ====================

    /**
     * Primary method for casting alarm audio with proper completion detection This method is
     * specifically designed for AlarmService
     */
    fun castAudioForAlarm(
            url: String,
            title: String,
            volume: Double = 1.0,
            onStarted: (() -> Unit)? = null,
            onFailed: ((String) -> Unit)? = null,
            onCompletion: (() -> Unit)? = null
    ): Boolean {
        Log.d(TAG, "🎯 Starting alarm casting: $title")

        if (!isCastingInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "🎯 Casting already in progress")
            return false
        }

        return try {
            if (!isSessionReadyForAlarm()) {
                Log.w(TAG, "🎯 No reliable Cast session for alarm casting")
                isCastingInProgress.set(false)
                onFailed?.invoke("No Cast session available")
                return false
            }

            val remoteClient = session?.remoteMediaClient
            if (remoteClient == null) {
                Log.w(TAG, "🎯 No remote media client available")
                isCastingInProgress.set(false)
                onFailed?.invoke("No remote media client")
                return false
            }

            // Clean up previous alarm callbacks
            cleanupAlarmCasting()

            // Store completion listener
            alarmCompletionListener = onCompletion

            // Set volume
            setVolume(volume)

            // Create metadata with proper notification info
            val metadata =
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                        putString(MediaMetadata.KEY_TITLE, title)
                        putString(MediaMetadata.KEY_ARTIST, "Alarm")
                        putString(MediaMetadata.KEY_ALBUM_TITLE, "Bayaan App")
                    }

            val mediaInfo =
                    MediaInfo.Builder(url)
                            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setContentType("audio/mpeg")
                            .setMetadata(metadata)
                            .build()

            // Register callback for completion detection
            currentMediaLoadCallback =
                    object : RemoteMediaClient.Callback() {
                        override fun onStatusUpdated() {
                            val mediaStatus = remoteClient.mediaStatus
                            val playerState = mediaStatus?.playerState

                            Log.d(TAG, "🎯 Alarm status: $playerState")

                            when (playerState) {
                                MediaStatus.PLAYER_STATE_PLAYING -> {
                                    Log.d(TAG, "✅ Alarm audio now playing")
                                    updateState(CastingState.Casting)
                                    alarmCastStartTime = System.currentTimeMillis()
                                    onStarted?.invoke()
                                }
                                MediaStatus.PLAYER_STATE_IDLE -> {
                                    if (mediaStatus?.idleReason == MediaStatus.IDLE_REASON_FINISHED
                                    ) {
                                        Log.d(TAG, "✅ Alarm audio completed")
                                        updateState(CastingState.Ended(url))
                                        alarmCompletionListener?.invoke()
                                        cleanupAlarmCasting()
                                    }
                                }
                                MediaStatus.PLAYER_STATE_BUFFERING -> {
                                    updateState(CastingState.Buffering)
                                }
                            }
                        }

                        override fun onMetadataUpdated() {
                            Log.d(TAG, "🎯 Alarm metadata updated - notification should show")
                        }
                    }

            remoteClient.registerCallback(currentMediaLoadCallback!!)

            // Load the media with timeout protection
            remoteClient.load(mediaInfo).setResultCallback { result ->
                if (result.status.isSuccess) {
                    Log.d(TAG, "✅ Alarm cast load successful")

                    // Start playback verification
                    startAlarmPlaybackVerification(url, title, onFailed)
                } else {
                    Log.w(TAG, "⚠️ Alarm cast load failed: ${result.status}")
                    onFailed?.invoke("Load failed: ${result.status}")
                    cleanupAlarmCasting()
                }
            }

            Log.d(TAG, "🎯 Casting alarm audio: $title ($url) at volume $volume")

            // Notify listeners
            notifyStateListeners { it.onCastStarted() }
            notifyFlutter("onAlarmCastStarted", mapOf("title" to title, "url" to url))

            true
        } catch (e: Exception) {
            Log.e(TAG, "🎯 Failed to cast alarm audio", e)
            updateState(CastingState.Error("Casting failed: ${e.message}"))
            onFailed?.invoke(e.message ?: "Unknown error")
            cleanupAlarmCasting()
            false
        }
    }

    /** Cast audio sequence for alarm scenarios with proper completion handling */
    fun castAudioSequenceForAlarm(
            primaryUrl: String,
            secondaryUrl: String?,
            title: String = "Alarm",
            volume: Double = 1.0,
            sequenceGapMs: Long = 500,
            stopAfterSecondary: Boolean = true,
            onStarted: (() -> Unit)? = null,
            onFailed: ((String) -> Unit)? = null,
            onCompletion: (() -> Unit)? = null
    ): Boolean {
        Log.d(TAG, "🎯 Starting alarm sequence casting: $title")

        if (!isCastingInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "🎯 Sequence casting already in progress")
            return false
        }

        return try {
            if (!isSessionReadyForAlarm()) {
                Log.w(TAG, "🎯 No reliable session for alarm sequence")
                isCastingInProgress.set(false)
                onFailed?.invoke("No Cast session available")
                return false
            }

            val remoteClient = session?.remoteMediaClient
            if (remoteClient == null) {
                Log.w(TAG, "🎯 No remote media client available")
                isCastingInProgress.set(false)
                onFailed?.invoke("No remote media client")
                return false
            }

            // Clean up previous callbacks
            cleanupAlarmCasting()

            // Store listeners
            alarmCompletionListener = onCompletion
            alarmErrorListener = onFailed

            // Set volume
            setVolume(volume)

            // Start sequence playback
            playAlarmSequence(
                    remoteClient,
                    primaryUrl,
                    secondaryUrl,
                    title,
                    sequenceGapMs,
                    stopAfterSecondary,
                    onStarted
            )

            Log.d(TAG, "🎯 Started alarm sequence: $title")

            // Notify listeners
            notifyStateListeners { it.onCastStarted() }
            notifyFlutter(
                    "onAlarmCastStarted",
                    mapOf(
                            "title" to title,
                            "url" to primaryUrl,
                            "isSequence" to true,
                            "hasSecondary" to (secondaryUrl != null)
                    )
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "🎯 Failed to cast alarm sequence", e)
            updateState(CastingState.Error("Sequence casting failed: ${e.message}"))
            onFailed?.invoke(e.message ?: "Unknown error")
            cleanupAlarmCasting()
            false
        }
    }

    private fun playAlarmSequence(
            remoteClient: RemoteMediaClient,
            primaryUrl: String,
            secondaryUrl: String?,
            title: String,
            sequenceGapMs: Long,
            stopAfterSecondary: Boolean,
            onStarted: (() -> Unit)?
    ) {
        val primaryMetadata =
                MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(MediaMetadata.KEY_TITLE, "$title - Part 1")
                    putString(MediaMetadata.KEY_ARTIST, "Alarm Sequence")
                }

        val primaryMediaInfo =
                MediaInfo.Builder(primaryUrl)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType("audio/mpeg")
                        .setMetadata(primaryMetadata)
                        .build()

        // Register callback for sequence tracking
        alarmSequenceCallback =
                object : RemoteMediaClient.Callback() {
                    private var primaryCompleted = false
                    private var playingSecondary = false

                    override fun onStatusUpdated() {
                        val mediaStatus = remoteClient.mediaStatus
                        val playerState = mediaStatus?.playerState

                        Log.d(
                                TAG,
                                "🎯 Sequence status: $playerState (primaryCompleted: $primaryCompleted)"
                        )

                        when (playerState) {
                            MediaStatus.PLAYER_STATE_PLAYING -> {
                                if (!primaryCompleted && !playingSecondary) {
                                    // Primary started playing
                                    Log.d(TAG, "✅ Primary alarm audio started")
                                    updateState(CastingState.Casting)
                                    alarmCastStartTime = System.currentTimeMillis()
                                    onStarted?.invoke()
                                } else if (playingSecondary) {
                                    Log.d(TAG, "✅ Secondary alarm audio started")
                                }
                            }
                            MediaStatus.PLAYER_STATE_IDLE -> {
                                if (mediaStatus?.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                                    if (!primaryCompleted) {
                                        // Primary completed
                                        Log.d(TAG, "✅ Primary alarm audio completed")
                                        primaryCompleted = true

                                        if (secondaryUrl != null) {
                                            // Schedule secondary audio
                                            handler.postDelayed(
                                                    {
                                                        Log.d(
                                                                TAG,
                                                                "🎯 Playing secondary alarm audio"
                                                        )
                                                        playingSecondary = true
                                                        playSecondaryAlarmAudio(
                                                                remoteClient,
                                                                secondaryUrl,
                                                                title,
                                                                stopAfterSecondary
                                                        )
                                                    },
                                                    sequenceGapMs
                                            )
                                        } else if (stopAfterSecondary) {
                                            Log.d(TAG, "🎯 No secondary audio, completing sequence")
                                            completeAlarmSequence()
                                        }
                                    } else if (playingSecondary) {
                                        // Secondary completed
                                        Log.d(TAG, "✅ Secondary alarm audio completed")
                                        if (stopAfterSecondary) {
                                            completeAlarmSequence()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

        remoteClient.registerCallback(alarmSequenceCallback!!)

        // Load primary audio
        remoteClient.load(primaryMediaInfo)
    }

    private fun playSecondaryAlarmAudio(
            remoteClient: RemoteMediaClient,
            secondaryUrl: String,
            title: String,
            stopAfterSecondary: Boolean
    ) {
        val secondaryMetadata =
                MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(MediaMetadata.KEY_TITLE, "$title - Part 2")
                    putString(MediaMetadata.KEY_ARTIST, "Alarm Sequence")
                }

        val secondaryMediaInfo =
                MediaInfo.Builder(secondaryUrl)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType("audio/mpeg")
                        .setMetadata(secondaryMetadata)
                        .build()

        remoteClient.load(secondaryMediaInfo)
    }

    private fun completeAlarmSequence() {
        Log.d(TAG, "🎯 Alarm sequence completed")
        alarmCompletionListener?.invoke()
        cleanupAlarmCasting()
    }

    private fun startAlarmPlaybackVerification(
            url: String,
            title: String,
            onFailed: ((String) -> Unit)?
    ) {
        handler.postDelayed(
                {
                    try {
                        val remoteClient = session?.remoteMediaClient
                        val mediaStatus = remoteClient?.mediaStatus

                        if (mediaStatus?.playerState != MediaStatus.PLAYER_STATE_PLAYING &&
                                        mediaStatus?.playerState !=
                                                MediaStatus.PLAYER_STATE_BUFFERING
                        ) {
                            Log.w(
                                    TAG,
                                    "🎯 Alarm playback verification failed - not in playing state"
                            )
                            onFailed?.invoke("Playback verification failed")
                        } else {
                            Log.d(TAG, "✅ Alarm playback verification successful")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "🎯 Alarm playback verification error", e)
                    }
                },
                PLAYBACK_VERIFICATION_DELAY_MS
        )
    }

    // ==================== SESSION MANAGEMENT ====================

    private fun isSessionReadyForAlarm(): Boolean {
        return isSessionActive() &&
                session?.remoteMediaClient != null &&
                currentState == CastingState.Connected
    }

    fun createAlarmCastSession(
            deviceId: String,
            deviceName: String,
            onSessionReady: (Boolean) -> Unit
    ) {
        Log.d(TAG, "Creating alarm-specific cast session for device: $deviceName ($deviceId)")

        disconnectFromDevice()

        handler.postDelayed(
                {
                    val route = findRouteByDeviceId(deviceId)
                    if (route != null) {
                        try {
                            mediaRouter.selectRoute(route)
                            Log.d(TAG, "Alarm session creation initiated")

                            handler.postDelayed(
                                    {
                                        if (isSessionActive()) {
                                            Log.d(TAG, "Alarm cast session created successfully")
                                            onSessionReady(true)
                                        } else {
                                            Log.w(TAG, "Alarm cast session failed to establish")
                                            onSessionReady(false)
                                        }
                                    },
                                    3000
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create alarm cast session", e)
                            onSessionReady(false)
                        }
                    } else {
                        Log.w(TAG, "Device route not found for alarm casting: $deviceId")
                        onSessionReady(false)
                    }
                },
                500
        )
    }

    private fun findRouteByDeviceId(deviceId: String): MediaRouter.RouteInfo? {
        val castCategory =
                CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )

        return mediaRouter.routes.find { route ->
            if (route.supportsControlCategory(castCategory)) {
                try {
                    val castDevice =
                            com.google.android.gms.cast.CastDevice.getFromBundle(route.extras)
                    castDevice?.deviceId == deviceId || route.id == deviceId
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }

    fun stopCastingAlarm() {
        Log.d(TAG, "Stopping alarm casting")

        try {
            // Stop remote playback
            session?.remoteMediaClient?.stop()

            isCastingInProgress.set(false)
            updateState(CastingState.Connected)

            currentPlaybackState =
                    currentPlaybackState.copy(
                            isPlaying = false,
                            position = 0L,
                            currentMediaUrl = null,
                            currentTitle = null,
                            currentArtist = null,
                            currentAlbum = null
                    )
            notifyPlaybackStateUpdate()

            Log.d(TAG, "🎯 Alarm casting stopped")
            notifyStateListeners { it.onCastStopped() }
            notifyFlutter("onAlarmCastStopped", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop alarm casting", e)
        }

        cleanupAlarmCasting()
    }

    private fun cleanupAlarmCasting() {
        isCastingInProgress.set(false)
        stopCurrentPolling()
        alarmCastStartTime = 0
        alarmCompletionListener = null
        alarmErrorListener = null

        // Unregister callbacks
        session?.remoteMediaClient?.let { remoteClient ->
            currentMediaLoadCallback?.let { remoteClient.unregisterCallback(it) }
            alarmSequenceCallback?.let { remoteClient.unregisterCallback(it) }
        }
        currentMediaLoadCallback = null
        alarmSequenceCallback = null
    }

    private fun cleanupInAppCasting() {
        inAppCompletionListener = null
        session?.remoteMediaClient?.let { remoteClient ->
            currentInAppCallback?.let { remoteClient.unregisterCallback(it) }
        }
        currentInAppCallback = null
    }

    private fun stopCurrentPolling() {
        currentPollingRunnable?.let {
            handler.removeCallbacks(it)
            currentPollingRunnable = null
        }
    }

    /** Check if we have a saved device for alarm casting */
    fun hasSavedDeviceForAlarm(): Boolean {
        return getSavedDeviceId()?.isNotEmpty() == true &&
                getSavedDeviceName()?.isNotEmpty() == true
    }

    /** Get saved device info for alarm scenarios */
    fun getSavedDeviceInfo(): Map<String, String>? {
        val deviceId = getSavedDeviceId()
        val deviceName = getSavedDeviceName()

        return if (!deviceId.isNullOrEmpty() && !deviceName.isNullOrEmpty()) {
            mapOf("id" to deviceId, "name" to deviceName)
        } else {
            null
        }
    }

    // ==================== POSITION-SPECIFIC PLAYING ====================

    fun playMediaAtPosition(
            url: String,
            title: String = "Audio",
            artist: String? = null,
            album: String? = null,
            startPositionMs: Long = 0L,
            autoPlay: Boolean = true
    ): Boolean {
        if (!isSessionActive()) {
            Log.w(TAG, "No active session for position-specific playback")
            return false
        }

        return try {
            val remoteClient = session?.remoteMediaClient
            if (remoteClient == null) {
                Log.w(TAG, "No remote media client available")
                return false
            }

            val metadata =
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                        putString(MediaMetadata.KEY_TITLE, title)
                        artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
                        album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
                    }

            val mediaInfo =
                    MediaInfo.Builder(url)
                            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setContentType("audio/mp3")
                            .setMetadata(metadata)
                            .build()

            val request =
                    MediaLoadRequestData.Builder()
                            .setMediaInfo(mediaInfo)
                            .setAutoplay(autoPlay)
                            .setCurrentTime(startPositionMs)
                            .build()

            remoteClient.load(request)
            updateState(CastingState.Casting)

            currentPlaybackState =
                    currentPlaybackState.copy(
                            currentMediaUrl = url,
                            currentTitle = title,
                            currentArtist = artist,
                            currentAlbum = album,
                            isPlaying = autoPlay,
                            position = startPositionMs
                    )
            notifyPlaybackStateUpdate()

            Log.d(TAG, "Playing audio at position: $title ($url) from ${startPositionMs}ms")
            notifyFlutter(
                    "onAudioStartedAtPosition",
                    mapOf(
                            "title" to title,
                            "url" to url,
                            "artist" to artist,
                            "album" to album,
                            "startPosition" to startPositionMs
                    )
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio at position", e)
            updateState(CastingState.Error("Position playback failed: ${e.message}"))
            false
        }
    }

    // ==================== INFRASTRUCTURE METHODS ====================

    private fun updateState(newState: CastingState) {
        val oldState = currentState
        currentState = newState

        Log.d(TAG, "Casting state changed: $oldState -> $newState")

        notifyStateListeners { listener -> listener.onCastingStateChanged(newState) }

        when (newState) {
            is CastingState.Connected -> notifyFlutter("onSessionRestored", null)
            is CastingState.Error -> notifyFlutter("onCastingError", newState.message)
            is CastingState.Idle -> notifyFlutter("onSessionEnded", null)
            else -> {
                /* Ignore other states */
            }
        }
    }

    private fun notifyStateListeners(action: (CastingStateListener) -> Unit) {
        val iterator = stateListeners.iterator()
        while (iterator.hasNext()) {
            val listenerRef = iterator.next()
            val listener = listenerRef.get()
            if (listener != null) {
                try {
                    action.invoke(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in state listener", e)
                }
            } else {
                iterator.remove()
            }
        }
    }

    fun setChannel(methodChannel: MethodChannel?) {
        channel = methodChannel
    }

    fun addStateListener(listener: CastingStateListener) {
        stateListeners.add(WeakReference(listener))
        listener.onCastingStateChanged(currentState)
        listener.onPlaybackStateUpdated(currentPlaybackState)
    }

    fun removeStateListener(listener: CastingStateListener) {
        val iterator = stateListeners.iterator()
        while (iterator.hasNext()) {
            val listenerRef = iterator.next()
            if (listenerRef.get() == listener) {
                iterator.remove()
                break
            }
        }
    }

    fun addDeviceListener(listener: (List<CastDevice>) -> Unit): () -> Unit {
        deviceListeners.add(listener)
        listener.invoke(availableDevices)
        return { deviceListeners.remove(listener) }
    }

    fun stopCasting() {
        try {
            session?.remoteMediaClient?.stop()
            isCastingInProgress.set(false)
            updateState(CastingState.Connected)
            Log.d(TAG, "Stopped casting")

            notifyStateListeners { it.onCastStopped() }
            notifyFlutter("onCastStopped", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop casting", e)
        }
    }

    fun disconnectFromDevice() {
        try {
            mediaRouter.selectRoute(mediaRouter.defaultRoute)
            Log.d(TAG, "Disconnected from Cast device")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect from device", e)
        }
    }

    fun isSessionActive(): Boolean {
        return session?.isConnected == true
    }

    fun isCasting(): Boolean {
        return currentState == CastingState.Casting
    }

    private fun saveDevice(deviceId: String, deviceName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DEVICE_ID, deviceId)
                .putString(KEY_LAST_DEVICE_NAME, deviceName)
                .apply()
        Log.d(TAG, "Saved device: $deviceName ($deviceId)")
    }

    fun getSavedDeviceId(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE_ID, null)
    }

    fun getSavedDeviceName(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE_NAME, null)
    }

    private fun notifyFlutter(method: String, args: Any?) {
        try {
            handler.post { channel?.invokeMethod(method, args) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify Flutter for $method", e)
        }
    }

    fun release() {
        Log.d(TAG, "Releasing CastingHelper")

        // Cancel all pending operations
        handler.removeCallbacksAndMessages(null)
        currentReconnectRunnable = null
        stopPlaybackStateUpdates()
        stopCurrentPolling()

        // Remove listeners
        try {
            castContext?.sessionManager?.removeSessionManagerListener(
                    sessionListener,
                    CastSession::class.java
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error removing session listener", e)
        }

        stopDiscovery()
        deviceListeners.clear()
        stateListeners.clear()
        channel = null

        Log.d(TAG, "CastingHelper released")
    }

    // ==================== ALARM-RELATED METHODS ====================

    fun initializeForAlarm() {
        Log.d(TAG, "🎯 Initializing Cast for alarm scenario")

        try {
            castContext?.sessionManager?.addSessionManagerListener(
                    sessionListener,
                    CastSession::class.java
            )

            startDiscovery()

            Log.d(TAG, "✅ Cast initialized for alarm")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Cast for alarm", e)
        }
    }

    fun connectToSavedDeviceForAlarm(): Boolean {
        Log.d(TAG, "🎯 Attempting to connect to saved device for alarm")

        val savedDeviceId = getSavedDeviceId()
        val savedDeviceName = getSavedDeviceName()

        if (savedDeviceId.isNullOrEmpty() || savedDeviceName.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ No saved device found for alarm")
            return false
        }

        Log.d(TAG, "🎯 Saved device: $savedDeviceName ($savedDeviceId)")

        val availableDevices = getAvailableDevices()
        val deviceExists = availableDevices.any { it["id"] == savedDeviceId }

        if (deviceExists) {
            Log.d(TAG, "✅ Device already discovered, attempting connection")
            return connectToDevice(savedDeviceId)
        } else {
            Log.d(TAG, "⚠️ Device not in discovered list, starting discovery...")

            startDiscovery()

            handler.postDelayed(
                    {
                        val devicesAfterDiscovery = getAvailableDevices()
                        val foundDevice = devicesAfterDiscovery.any { it["id"] == savedDeviceId }

                        if (foundDevice) {
                            Log.d(TAG, "✅ Device found after discovery, connecting...")
                            connectToDevice(savedDeviceId)
                        } else {
                            Log.w(TAG, "❌ Device not found even after discovery")
                        }
                    },
                    3000
            )

            return true
        }
    }

    fun castInBackgroundForAlarm(
            url: String,
            title: String = "Alarm",
            volume: Double = 0.5,
            onSuccess: (() -> Unit)? = null,
            onFailure: (() -> Unit)? = null
    ) {
        Log.d(TAG, "🎯 Starting background casting for alarm")

        initializeForAlarm()

        handler.postDelayed(
                {
                    val connectionAttempted = connectToSavedDeviceForAlarm()

                    if (connectionAttempted) {
                        handler.postDelayed(
                                {
                                    if (isSessionActive()) {
                                        Log.d(TAG, "✅ Session active, casting audio...")
                                        castAudioForAlarm(
                                                url,
                                                title,
                                                volume,
                                                onStarted = onSuccess,
                                                onFailed = { error -> onFailure?.invoke() }
                                        )
                                    } else {
                                        Log.w(TAG, "❌ No active session for casting")
                                        onFailure?.invoke()
                                    }
                                },
                                4000
                        )
                    } else {
                        Log.w(TAG, "❌ Could not attempt connection")
                        onFailure?.invoke()
                    }
                },
                1000
        )
    }

    fun tryRestoreOrCreateSessionForAlarm(onSessionReady: (Boolean) -> Unit) {
        Log.d(TAG, "🎯 Attempting to restore/create session for alarm")

        try {
            val context = castContext
            if (context == null) {
                Log.w(TAG, "❌ CastContext is null")
                onSessionReady(false)
                return
            }

            val sessionManager = context.sessionManager
            val currentSession = sessionManager.currentCastSession

            if (currentSession?.isConnected == true) {
                Log.d(TAG, "✅ Found existing connected session")
                session = currentSession
                updateState(CastingState.Connected)
                onSessionReady(true)
                return
            }

            Log.d(TAG, "🔄 No active session, trying to resume saved session...")

            handler.postDelayed(
                    {
                        val resumedSession = sessionManager.currentCastSession
                        if (resumedSession?.isConnected == true) {
                            Log.d(TAG, "✅ Successfully resumed saved session")
                            session = resumedSession
                            updateState(CastingState.Connected)
                            onSessionReady(true)
                        } else {
                            Log.w(TAG, "⚠️ Could not resume session, need new connection")
                            onSessionReady(false)
                        }
                    },
                    3000
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in session restore: ${e.message}")
            onSessionReady(false)
        }
    }
}
