package com.deltaforce.alert.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.deltaforce.alert.R
import com.deltaforce.alert.databinding.ActivityCallBinding

/** QQ 通话式强提醒界面：全屏 + 循环响铃 + 震动 + 接听/挂断。 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        binding.callTitle.text = title
        binding.callDetail.text = text

        binding.btnAnswer.setOnClickListener { dismiss("已接听") }
        binding.btnDecline.setOnClickListener { dismiss("已忽略") }

        startRinging()
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@IncomingCallActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
    }

    override fun onBackPressed() {
        dismiss("已忽略")
    }
}
