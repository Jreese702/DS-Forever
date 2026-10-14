package com.jrees.wowcompanion

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * "SEND A PROBLEM REPORT" (board f-problem-report): the player's words, a preview of exactly what would be saved (already through
 * [Redact]), then Save (Documents/DS Forever/reports/<time>.zip) or Open GitHub issue (the browser, pre-filled; they attach the file).
 * "Record 5 minutes" starts the detailed log and closes, so the player can reproduce the problem; the bottom screen shows the
 * recording and its Stop, and this screen comes back with Send. Opened on the bottom screen's display (CompanionHost.openReport).
 */
class ProblemReportActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var words: EditText
    private lateinit var preview: TextView
    private lateinit var status: TextView
    private lateinit var record: Button
    private var report: ProblemReport.Report? = null
    private var saved: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, pad / 3, 0, pad / 3)
        }
        col.addView(text("Send a problem report", 22f, bold = true))
        col.addView(text("Say what happened. DS Forever adds its own logs from the last 15 minutes, its versions and your device, with " +
            "logins, BattleTags, character / realm / guild names and chat taken out. Nothing is sent: you choose what to do with it.", 14f))
        words = EditText(this).apply {
            hint = "What happened, and what were you doing?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        }
        col.addView(words)
        record = Button(this)
        col.addView(record)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, on: () -> Unit) = Button(this).apply {
            this.text = label; setOnClickListener { on() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(button("Preview") { make { show(it) } })
        row.addView(button("Save report") { make { r -> save(r) } })
        row.addView(button("Open GitHub issue") { make { r -> save(r)?.let { openIssue(r, it) } } })
        row.addView(button("Close") { finish() })
        col.addView(row)
        status = text("", 14f)
        col.addView(status)
        preview = text("", 11f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(preview)
        setContentView(ScrollView(this).apply { addView(col) })
        tick.run()
    }

    /** the Record button follows the recording: "Record 5 minutes" / "Recording… 3:12 - Stop" */
    private val tick = object : Runnable {
        override fun run() {
            if (DiagLog.recording()) {
                val left = DiagLog.recordLeftMs() / 1000
                record.text = "Recording… %d:%02d left - Stop".format(left / 60, left % 60)
                record.setOnClickListener { DiagLog.stopRecording(); run() }
            } else {
                record.text = "Record 5 minutes first (reproduce the problem, then come back)"
                record.setOnClickListener {
                    DiagLog.startRecording()
                    status.text = "Recording. Reproduce the problem; it stops by itself after 5 minutes. Then send the report."
                    ui.postDelayed({ finish() }, 1500)
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    /** the report as of now, made off the main thread; a new description makes a new one */
    private fun make(then: (ProblemReport.Report) -> Unit) {
        status.text = "Making the report…"
        val description = words.text.toString()
        val ctx = applicationContext
        Thread({
            val r = runCatching { ProblemReport.collect(ctx, description) }
            ui.post {
                r.onSuccess { report = it; saved = null; status.text = ""; then(it) }
                    .onFailure { status.text = "The report could not be made: ${it.message}" }
            }
        }, "problem-report").start()
    }

    private fun show(r: ProblemReport.Report) {
        preview.text = r.preview
        status.text = "This is everything the report holds (${r.files.size} files). Nothing has been saved or sent."
    }

    private fun save(r: ProblemReport.Report): File? {
        show(r)
        val f = saved ?: ProblemReport.save(this, r)
        saved = f
        status.text = if (f != null) "Saved: ${f.path}" else "It could not be saved."
        return f
    }

    private fun openIssue(r: ProblemReport.Report, file: File) {
        val url = ProblemReport.issueUrl(r, words.text.toString(), ProblemReport.facts(this), file.path, Redact.names())
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onSuccess { status.text = "Saved: ${file.path}\nThe browser opens GitHub's new-issue page: attach that file there." }
            .onFailure { status.text = "Saved: ${file.path}\nNo browser opened (${it.message})." }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
        if (isFinishing) ProblemReport.onClosed?.invoke()
    }
}
