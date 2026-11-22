package com.banglalens.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var speech: SpeechController
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var tvEmpty: TextView
    private lateinit var resultGroup: View
    private lateinit var tvBengali: TextView
    private lateinit var tvOriginal: TextView

    private val storeListener: () -> Unit = { render() }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, FloatingService::class.java)
                    .setAction(FloatingService.ACTION_START)
                    .putExtra(FloatingService.EXTRA_CODE, res.resultCode)
                    .putExtra(FloatingService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                Toast.makeText(this, R.string.magnifier_started, Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        tvEmpty = findViewById(R.id.tvEmpty)
        resultGroup = findViewById(R.id.resultGroup)
        tvBengali = findViewById(R.id.tvBengali)
        tvOriginal = findViewById(R.id.tvOriginal)

        speech = SpeechController(
            this,
            onChange = { updateSpeechUi() },
            onMessage = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        )

        btnStart.setOnClickListener {
            if (ResultStore.serviceRunning) {
                startService(Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_STOP))
            } else {
                startFlow()
            }
        }

        // Voice starts ONLY when the user presses these buttons.
        findViewById<Button>(R.id.btnSpeaker).setOnClickListener { speech.play(tvBengali.text.toString()) }
        btnPause.setOnClickListener {
            if (speech.state == SpeechController.State.PAUSED) speech.resume() else speech.pause()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { speech.stop() }
        findViewById<Button>(R.id.btnRepeat).setOnClickListener { speech.repeat() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val t = tvBengali.text.toString()
            if (t.isNotBlank()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Bangla Lens", t))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ResultStore.addListener(storeListener)
        render()
    }

    override fun onStop() {
        ResultStore.removeListener(storeListener)
        super.onStop()
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_overlay_title)
                .setMessage(R.string.perm_overlay_msg)
                .setPositiveButton(R.string.go_to_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun render() {
        btnStart.setText(if (ResultStore.serviceRunning) R.string.stop_magnifier else R.string.start_magnifier)
        val r = ResultStore.last
        if (r == null) {
            tvEmpty.visibility = View.VISIBLE
            resultGroup.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            resultGroup.visibility = View.VISIBLE
            tvBengali.text = r.bengali
            tvOriginal.text = r.original
        }
        updateSpeechUi()
    }

    private fun updateSpeechUi() {
        btnPause.setText(
            if (speech.state == SpeechController.State.PAUSED) R.string.btn_resume else R.string.btn_pause
        )
    }
}
