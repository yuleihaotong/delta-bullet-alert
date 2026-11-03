package com.deltaforce.alert.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.deltaforce.alert.R
import com.deltaforce.alert.attrColor
import com.deltaforce.alert.databinding.ActivityCallBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** QQ 通话式强提醒界面：全屏 + 循环响铃 + 震动 + 接听/挂断。 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val ringExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(com.deltaforce.alert.ThemeIndex.styleOf(com.deltaforce.alert.data.WatchlistStore.get(this).themeIndex))
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        val title = intent.getStringExtra(RingHelper.EXTRA_TITLE) ?: "交易提醒"
        val text = intent.getStringExtra(RingHelper.EXTRA_TEXT) ?: ""
        val type = intent.getStringExtra(RingHelper.EXTRA_TYPE) ?: "buy"
        binding.callTitle.text = title
        binding.callTitle.setTextColor(
            attrColor(if (type == "sell") R.attr.appSell else R.attr.appBuy)
        )
        binding.callDetail.text = text

        binding.btnAnswer.setOnClickListener { dismiss("已接听") }
        binding.btnDecline.setOnClickListener { dismiss("已忽略") }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss("已忽略")
        })

        startRinging()
    }

    private fun ringtoneUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun requestFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager = am
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {
        }
    }

    private fun startRinging() {
        requestFocus()
        startVibrating()
        val uri = ringtoneUri() ?: return
        // setDataSource/prepare 会读系统铃声，别放主线程（网络/大文件铃声会 ANR）
        try {
            ringExecutor.execute {
                val built = try {
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setDataSource(this@IncomingCallActivity, uri)
                        isLooping = true
                        prepare()
                    }
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (built == null) return@runOnUiThread
                    if (dismissed) {
                        built.release()
                        return@runOnUiThread
                    }
                    player = built
                    try {
                        built.start()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startVibrating() {
        try {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 600, 400, 600, 400, 600), 0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 600, 400, 600, 400, 600), 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun dismiss(reason: String) {
        dismissed = true
        stopRinging()
        RingHelper.notifyPlain(this, "提醒已处理", reason)
        finishAndRemoveTask()
    }

    private fun stopRinging() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
        focusRequest = null
    }

    override fun onDestroy() {
        dismissed = true
        super.onDestroy()
        stopRinging()
        ringExecutor.shutdownNow()
    }
}
