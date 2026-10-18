package com.example.fallpiano

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.database.Cursor
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import kotlin.math.max

class MainActivity : Activity() {
    private var player: MediaPlayer? = null
    private var songUri: Uri? = null
    private var songName = "No song selected"
    private var bpm = 120
    private var selectedMode = GameMode.TILES
    private var analysis: SongAnalysis? = null
    private var gameView: PianoGameView? = null
    private lateinit var songLabel: TextView
    private lateinit var analysisLabel: TextView
    private lateinit var startButton: Button
    private lateinit var modeTiles: Button
    private lateinit var modeKeys: Button
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var analyzing = false

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showHome() }

    private fun makeRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(26), dp(20), dp(20))
        setBackgroundColor(Color.rgb(9, 12, 26))
    }

    private fun showHome() {
        val root = makeRoot()
        root.addView(text("FALL PIANO", 30f, Color.WHITE, true), matchWrap())
        root.addView(text("Your music. Your rhythm.", 16f, Color.rgb(150, 175, 255)), marginParams(WRAP, WRAP, 0, 8, 0, 22))
        root.addView(button("＋  Choose a song (MP3 / WAV)") { chooseSong() }, fullButton())
        songLabel = text(songName, 15f, Color.LTGRAY)
        root.addView(songLabel, marginParams(WRAP, WRAP, 0, 10, 0, 12))

        root.addView(text("GAME MODE", 14f, Color.rgb(150, 175, 255), true), marginParams(WRAP, WRAP, 0, 8, 0, 6))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        modeTiles = button("4-lane tiles") { selectedMode = GameMode.TILES; updateModeButtons() }
        modeKeys = button("Piano keys") { selectedMode = GameMode.KEYBOARD; updateModeButtons() }
        modes.addView(modeTiles, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(5) })
        modes.addView(modeKeys, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(5) })
        root.addView(modes, fullButton())
        updateModeButtons()

        root.addView(text("Fallback tempo", 17f, Color.WHITE, true), marginParams(WRAP, WRAP, 0, 12, 0, 4))
        val bpmLabel = text("$bpm BPM", 15f, Color.rgb(111, 230, 255), true)
        root.addView(bpmLabel, matchWrap())
        val seek = SeekBar(this).apply {
            max = 180; progress = bpm - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { bpm = p + 60; bpmLabel.text = "$bpm BPM" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seek, fullButton())
        analysisLabel = text("Choose a song to analyze its rhythm offline.", 13f, Color.rgb(180, 185, 205))
        root.addView(analysisLabel, marginParams(WRAP, WRAP, 0, 8, 0, 18))
        startButton = button("▶  Analyze & Play") { startGame() }
        root.addView(startButton, fullButton())
        root.addView(text("Offline audio • Android native • Prototype", 12f, Color.GRAY), marginParams(WRAP, WRAP, 0, 24, 0, 0))
        setContentView(root)
        songLabel.text = songName
    }

    private fun updateModeButtons() {
        if (!::modeTiles.isInitialized) return
        val active = Color.rgb(46, 100, 170); val inactive = Color.rgb(35, 42, 65)
        modeTiles.setBackgroundTintList(android.content.res.ColorStateList.valueOf(if (selectedMode == GameMode.TILES) active else inactive))
        modeKeys.setBackgroundTintList(android.content.res.ColorStateList.valueOf(if (selectedMode == GameMode.KEYBOARD) active else inactive))
    }

    private fun chooseSong() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
        startActivityForResult(i, 100)
    }

    @Deprecated("Legacy callback retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            songUri = uri
            songName = queryName(uri) ?: (uri.lastPathSegment ?: "Selected song")
            analysis = null
            songLabel.text = songName
            analysisLabel.text = "Ready to analyze. Analysis runs on-device."
        }
    }

    private fun queryName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } catch (_: Exception) { null } finally { cursor?.close() }
    }

    private fun startGame() {
        val uri = songUri
        if (uri == null) { Toast.makeText(this, "Choose a song first", Toast.LENGTH_SHORT).show(); return }
        if (analyzing) return
        if (analysis == null) {
            analyzing = true
            startButton.isEnabled = false
            startButton.text = "Analyzing audio…"
            analysisLabel.text = "Scanning audio energy and estimating beats…"
            Thread {
                val result = try { AudioAnalyzer.analyze(contentResolver, uri) } catch (_: Exception) { null }
                ui.post {
                    analyzing = false
                    analysis = result
                    startButton.isEnabled = true
                    startButton.text = "▶  Analyze & Play"
                    if (result == null || result.events.isEmpty()) {
                        analysisLabel.text = "Audio analysis failed for this format. Using a generated rhythm at $bpm BPM."
                        analysis = SongAnalysis(bpm, emptyList(), "fallback")
                    } else {
                        bpm = result.bpm
                        analysisLabel.text = "Detected about ${result.bpm} BPM • ${result.events.size} note events • ${result.difficulty} difficulty"
                    }
                    playNow()
                }
            }.start()
        } else playNow()
    }

    private fun playNow() {
        val uri = songUri ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener {
                    showGame()
                    it.start()
                    gameView?.startClock()
                }
                setOnCompletionListener { gameView?.stopClock(); Toast.makeText(this@MainActivity, "Song finished!", Toast.LENGTH_SHORT).show() }
                prepareAsync()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open this audio file", Toast.LENGTH_LONG).show()
        }
    }

    private fun showGame() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(9, 12, 26)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        top.addView(button("‹ Back") { player?.pause(); player?.seekTo(0); gameView?.stopClock(); showHome() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        val score = text("Score 0", 17f, Color.WHITE, true)
        top.addView(score, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER })
        container.addView(top)
        val song = analysis ?: SongAnalysis(bpm, emptyList(), "generated")
        gameView = PianoGameView(this, selectedMode, song, bpm) { points -> score.text = "Score $points" }
        container.addView(gameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(container)
    }

    override fun onPause() { super.onPause(); player?.pause(); gameView?.stopClock() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(46, 69, 145)))
        setOnClickListener { action() }
    }
    private fun fullButton() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(10) }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun marginParams(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    companion object { private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT }
}

enum class GameMode { TILES, KEYBOARD }
data class NoteEvent(val timeMs: Long, val lane: Int, val strength: Float = 1f)
data class SongAnalysis(val bpm: Int, val events: List<NoteEvent>, val difficulty: String)

private class PianoGameView(
    context: android.content.Context,
    private val mode: GameMode,
    private val analysis: SongAnalysis,
    private val fallbackBpm: Int,
    private val onScore: (Int) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startedAt = 0L
    private var running = false
    private var score = 0
    private var lastHit = -1
    private val lanes = if (mode == GameMode.TILES) 4 else 8
    private val colors = intArrayOf(Color.rgb(76,220,255), Color.rgb(133,112,255), Color.rgb(255,92,177), Color.rgb(111,245,181), Color.rgb(255,190,90), Color.rgb(105,165,255), Color.rgb(255,110,130), Color.rgb(120,245,210))
    private val speed get() = max(240f, height * 0.55f)
    private val targetY get() = height * 0.78f
    private val events: List<NoteEvent> = if (analysis.events.isNotEmpty()) analysis.events else
        (0..300).map { NoteEvent((it * 60000L / fallbackBpm), (it * 7 + 3) % lanes) }

    fun startClock() { startedAt = android.os.SystemClock.uptimeMillis(); running = true; invalidate() }
    fun stopClock() { running = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9,12,26))
        val laneW = width / lanes.toFloat()
        for (i in 0 until lanes) {
            paint.color = if (i % 2 == 0) Color.rgb(17,23,45) else Color.rgb(13,18,37)
            canvas.drawRect(i*laneW, 0f, (i+1)*laneW, height.toFloat(), paint)
            paint.color = Color.rgb(35,43,70); paint.strokeWidth = 1.5f
            canvas.drawLine(i*laneW, 0f, i*laneW, height.toFloat(), paint)
        }
        paint.color = Color.WHITE; paint.textSize = 25f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (mode == GameMode.TILES) "FALLING TILES" else "FALLING PIANO", width/2f, 42f, paint)
        val elapsed = if (running) android.os.SystemClock.uptimeMillis() - startedAt else 0L
        paint.strokeWidth = 4f; paint.color = Color.WHITE
        canvas.drawLine(0f, targetY, width.toFloat(), targetY, paint)
        events.forEachIndexed { idx, event ->
            val y = targetY - ((event.timeMs - elapsed) / 1000f) * speed
            if (y in -100f..height.toFloat()+100f) {
                val lane = event.lane.coerceIn(0, lanes-1)
                val rect = RectF(lane*laneW + 5f, y-64f, (lane+1)*laneW-5f, y)
                paint.color = colors[lane % colors.size]
                canvas.drawRoundRect(rect, 12f, 12f, paint)
            }
        }
        if (mode == GameMode.KEYBOARD) {
            val keyTop = height * 0.87f
            for (i in 0 until lanes) {
                paint.color = if (i in listOf(1,3,6)) Color.rgb(30,34,48) else Color.rgb(225,230,245)
                canvas.drawRoundRect(RectF(i*laneW+2, keyTop, (i+1)*laneW-2, height-5f), 4f, 4f, paint)
            }
        }
        paint.color = Color.WHITE; paint.textSize = 19f
        canvas.drawText("Score $score", width/2f, height-22f, paint)
        if (running) postInvalidateDelayed(16)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val lane = (event.x / (width / lanes.toFloat())).toInt().coerceIn(0, lanes-1)
            val elapsed = if (running) android.os.SystemClock.uptimeMillis() - startedAt else 0L
            val nearest = events.indices.filter { kotlin.math.abs(events[it].timeMs - elapsed) < 230L && events[it].lane == lane }.minByOrNull { kotlin.math.abs(events[it].timeMs - elapsed) }
            if (nearest != null && nearest != lastHit) { score += 100; lastHit = nearest }
            else score = max(0, score - 10)
            onScore(score); invalidate(); return true
        }
        return true
    }
}
