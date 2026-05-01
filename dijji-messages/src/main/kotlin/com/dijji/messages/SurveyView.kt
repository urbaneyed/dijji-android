package com.dijji.messages

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dijji.sdk.Dijji
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Renders an `in_app_survey` bottom sheet — a multi-question wizard that
 * walks the user through 1..N questions then shows an end screen. Mirrors
 * the Flutter `_SurveyWidget` UX one-for-one (drag handle → progress bar →
 * question label → typed body → Back/Next buttons).
 *
 * Each answer (and the final completion) fires through
 * [MessageHost.onSurveyPost] — which dijji-core wires to Api.postSurvey on
 * init. The callback is fire-and-forget; UI never blocks on the round-trip.
 */
internal object SurveyView {

    private val mainHandler = Handler(Looper.getMainLooper())

    private const val ACCENT_HEX  = "#7C3AED"
    private const val ACCENT_DIM  = "#B17DF1" // 0x7C3AED at ~0.6 alpha mixed on white
    private const val SURFACE_HEX = "#FFFFFF"
    private const val TEXT_HEX    = "#0F172A"
    private const val MUTED_HEX   = "#64748B"
    private const val BORDER_HEX  = "#E2E8F0"
    private const val FILL_HEX    = "#F8FAFC"
    private const val DANGER_HEX  = "#EF4444"
    private const val ACCENT_FILL_HEX = "#F3EBFE" // accent at very low alpha
    private const val YES_GREEN_HEX = "#16A34A"
    private const val YES_FILL_HEX  = "#DCFCE7"
    private const val NO_RED_HEX    = "#DC2626"
    private const val NO_FILL_HEX   = "#FEE2E2"

    fun show(
        activity: Activity,
        messageId: String,
        cfg: Map<String, Any?>,
    ) {
        val responseId = (cfg["response_id"] as? Number)?.toInt() ?: run {
            // Server failed to pre-create response row — drop the survey.
            return
        }
        @Suppress("UNCHECKED_CAST")
        val rawQuestions = cfg["questions"] as? List<Map<String, Any?>>
            ?: emptyList()
        if (rawQuestions.isEmpty()) return

        @Suppress("UNCHECKED_CAST")
        val endScreen = (cfg["end_screen"] as? Map<String, Any?>) ?: emptyMap()

        // Site key is best-effort: cfg may carry it; otherwise dijji-core's
        // Api.postSurvey backfills from DijjiConfig before sending. Either way
        // the renderer never reaches into core internals.
        val siteKey = cfg["site"]?.toString()

        val state = SurveyState(
            activity = activity,
            messageId = messageId,
            responseId = responseId,
            questions = rawQuestions,
            endScreen = endScreen,
            siteKey = siteKey,
        )
        state.present()
    }

    private class SurveyState(
        val activity: Activity,
        val messageId: String,
        val responseId: Int,
        val questions: List<Map<String, Any?>>,
        val endScreen: Map<String, Any?>,
        val siteKey: String?,
    ) {
        private val dp = activity.resources.displayMetrics.density
        private val dialog = BottomSheetDialog(activity)
        private val answers: MutableMap<String, Any?> = mutableMapOf()
        private var step = 0
        private var completed = false

        // Containers we rebuild each step (cheap enough; ≤ a couple dozen views).
        private lateinit var rootColumn: LinearLayout
        private lateinit var progressRow: LinearLayout
        private lateinit var contentScroll: ScrollView
        private lateinit var contentColumn: LinearLayout
        private lateinit var actionRow: LinearLayout
        private lateinit var backBtn: Button
        private lateinit var nextBtn: Button

        fun present() {
            buildChrome()
            renderCurrentStep()
            dialog.setContentView(rootColumn)
            dialog.setOnCancelListener {
                Dijji.trackPushEvent("in_app_dismissed", pushId = messageId)
            }
            dialog.show()
            Dijji.trackPushEvent("in_app_shown", pushId = messageId)
        }

        private fun buildChrome() {
            val padH = (22 * dp).toInt()
            val padTop = (12 * dp).toInt()
            val padBottom = (22 * dp).toInt()

            rootColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padH, padTop, padH, padBottom)
                background = GradientDrawable().apply {
                    val r = 16 * dp
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(Color.parseColor(SURFACE_HEX))
                }
            }

            // Drag handle — 36 x 4 dp pill, grey.
            val handle = View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2 * dp
                    setColor(Color.parseColor(BORDER_HEX))
                }
                val lp = LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt())
                lp.gravity = Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = (14 * dp).toInt()
                layoutParams = lp
            }
            rootColumn.addView(handle)

            progressRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (16 * dp).toInt()
                layoutParams = lp
            }
            rootColumn.addView(progressRow)

            contentScroll = ScrollView(activity).apply {
                isFillViewport = false
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
                layoutParams = lp
            }
            contentColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            contentScroll.addView(contentColumn)
            rootColumn.addView(contentScroll)

            actionRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = (16 * dp).toInt()
                layoutParams = lp
            }
            backBtn = Button(activity).apply {
                text = "Back"
                isAllCaps = false
                setTextColor(Color.parseColor(MUTED_HEX))
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(Color.TRANSPARENT)
                }
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener {
                    if (step > 0) {
                        step--
                        renderCurrentStep()
                    }
                }
            }
            nextBtn = Button(activity).apply {
                isAllCaps = false
                setTextColor(Color.WHITE)
                setPadding((22 * dp).toInt(), (10 * dp).toInt(), (22 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener { onNextClicked() }
            }

            actionRow.addView(backBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            // Spacer
            val spacer = View(activity)
            actionRow.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))
            actionRow.addView(nextBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            rootColumn.addView(actionRow)
        }

        private fun renderCurrentStep() {
            buildProgressBar()
            contentColumn.removeAllViews()
            if (completed || step >= questions.size) {
                renderEndScreen()
                actionRow.visibility = View.GONE
                progressRow.visibility = View.GONE
                return
            }
            val q = questions[step]
            renderQuestionLabel(q)
            renderQuestionBody(q)
            updateActionRow()
        }

        // ───── Progress bar — n weighted segments, accent cascade ─────
        private fun buildProgressBar() {
            progressRow.removeAllViews()
            val total = questions.size
            val accent = Color.parseColor(ACCENT_HEX)
            val accentDim = Color.parseColor(ACCENT_DIM)
            val upcoming = Color.parseColor(BORDER_HEX)
            for (i in 0 until total) {
                val seg = View(activity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 2 * dp
                        setColor(when {
                            i < step  -> accent
                            i == step -> accentDim
                            else      -> upcoming
                        })
                    }
                }
                val lp = LinearLayout.LayoutParams(0, (3 * dp).toInt(), 1f)
                if (i != total - 1) lp.marginEnd = (4 * dp).toInt()
                progressRow.addView(seg, lp)
            }
        }

        // ───── Question label (with optional red asterisk) ─────
        private fun renderQuestionLabel(q: Map<String, Any?>) {
            val label = q["label"]?.toString().orEmpty()
            val required = q["required"] == true
            val tv = TextView(activity).apply {
                setTextColor(Color.parseColor(TEXT_HEX))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = (16 * dp).toInt()
                layoutParams = lp
            }
            if (required && label.isNotEmpty()) {
                val sb = SpannableStringBuilder(label).apply {
                    append(" *")
                    setSpan(
                        ForegroundColorSpan(Color.parseColor(DANGER_HEX)),
                        length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                tv.text = sb
            } else {
                tv.text = label
            }
            contentColumn.addView(tv)
        }

        private fun renderQuestionBody(q: Map<String, Any?>) {
            val type = q["type"]?.toString().orEmpty()
            when (type) {
                "rating"   -> renderRating(q)
                "radio"    -> renderRadio(q)
                "checkbox" -> renderCheckbox(q)
                "yesno"    -> renderYesNo(q)
                else       -> renderText(q) // "text" + fallback
            }
        }

        // ───── rating: 0..N or 1..N pillbuttons; wrapped if N==10 ─────
        private fun renderRating(q: Map<String, Any?>) {
            val qid = q["id"]?.toString().orEmpty()
            val rmax = (q["rating_max"] as? Number)?.toInt() ?: 5
            val start = if (rmax == 10) 0 else 1
            val selected = answers[qid] as? Int

            val rows: List<List<Int>> = if (rmax == 10) {
                // 0..4 row 1, 5..10 row 2 — keeps each cell visually large enough on phones.
                listOf((start..4).toList(), (5..rmax).toList())
            } else {
                listOf((start..rmax).toList())
            }

            for ((idx, values) in rows.withIndex()) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    if (idx > 0) lp.topMargin = (6 * dp).toInt()
                    layoutParams = lp
                }
                for (v in values) {
                    row.addView(makeRatingButton(qid, v, selected == v),
                        LinearLayout.LayoutParams(
                            0,
                            (38 * dp).toInt(),
                            1f,
                        ).apply {
                            if (v != values.first()) marginStart = (6 * dp).toInt()
                        }
                    )
                }
                contentColumn.addView(row)
            }
        }

        private fun makeRatingButton(qid: String, value: Int, selected: Boolean): TextView {
            return TextView(activity).apply {
                text = value.toString()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    if (selected) Color.WHITE else Color.parseColor(TEXT_HEX)
                )
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(if (selected) Color.parseColor(ACCENT_HEX) else Color.parseColor(FILL_HEX))
                    setStroke(
                        (1 * dp).toInt(),
                        if (selected) Color.parseColor(ACCENT_HEX) else Color.parseColor(BORDER_HEX)
                    )
                }
                isClickable = true
                setOnClickListener {
                    answers[qid] = value
                    renderCurrentStep()
                }
            }
        }

        // ───── radio: vertical list, single-select ─────
        private fun renderRadio(q: Map<String, Any?>) {
            val qid = q["id"]?.toString().orEmpty()
            val choices = (q["choices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val selected = answers[qid] as? String
            for (choice in choices) {
                contentColumn.addView(
                    makeChoiceTile(choice, selected == choice, multi = false) {
                        answers[qid] = choice
                        renderCurrentStep()
                    }
                )
            }
        }

        // ───── checkbox: vertical list, multi-select with ✓ marker ─────
        @Suppress("UNCHECKED_CAST")
        private fun renderCheckbox(q: Map<String, Any?>) {
            val qid = q["id"]?.toString().orEmpty()
            val choices = (q["choices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val picked = (answers[qid] as? List<String>)?.toMutableList() ?: mutableListOf()
            for (choice in choices) {
                contentColumn.addView(
                    makeChoiceTile(choice, picked.contains(choice), multi = true) {
                        if (picked.contains(choice)) picked.remove(choice) else picked.add(choice)
                        answers[qid] = picked.toList()
                        renderCurrentStep()
                    }
                )
            }
        }

        private fun makeChoiceTile(
            label: String,
            selected: Boolean,
            multi: Boolean,
            onTap: () -> Unit,
        ): View {
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(if (selected) Color.parseColor(ACCENT_FILL_HEX) else Color.parseColor(FILL_HEX))
                    setStroke(
                        (if (selected) 1.5 * dp else 1 * dp).toInt(),
                        if (selected) Color.parseColor(ACCENT_HEX) else Color.parseColor(BORDER_HEX)
                    )
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = (8 * dp).toInt()
                layoutParams = lp
                isClickable = true
                setOnClickListener { onTap() }
            }
            val labelTv = TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor(TEXT_HEX))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tile.addView(labelTv)
            if (selected) {
                val tick = TextView(activity).apply {
                    text = if (multi) "✓" else "●"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(Color.parseColor(ACCENT_HEX))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                tile.addView(tick)
            }
            return tile
        }

        // ───── yesno: two side-by-side buttons ─────
        private fun renderYesNo(q: Map<String, Any?>) {
            val qid = q["id"]?.toString().orEmpty()
            val current = answers[qid] as? Int
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            row.addView(makeYesNoButton("Yes", selected = current == 1, isYes = true) {
                answers[qid] = 1
                renderCurrentStep()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // gutter
            val gap = View(activity)
            row.addView(gap, LinearLayout.LayoutParams((10 * dp).toInt(), 1))
            row.addView(makeYesNoButton("No", selected = current == 0, isYes = false) {
                answers[qid] = 0
                renderCurrentStep()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            contentColumn.addView(row)
        }

        private fun makeYesNoButton(
            label: String,
            selected: Boolean,
            isYes: Boolean,
            onTap: () -> Unit,
        ): TextView {
            val accentTextColor = Color.parseColor(if (isYes) YES_GREEN_HEX else NO_RED_HEX)
            val accentFill = Color.parseColor(if (isYes) YES_FILL_HEX else NO_FILL_HEX)
            return TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) accentTextColor else Color.parseColor(TEXT_HEX))
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(if (selected) accentFill else Color.parseColor(FILL_HEX))
                    setStroke(
                        (if (selected) 1.5 * dp else 1 * dp).toInt(),
                        if (selected) accentTextColor else Color.parseColor(BORDER_HEX)
                    )
                }
                setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
                isClickable = true
                setOnClickListener { onTap() }
            }
        }

        // ───── text: multiline EditText, max 2000 chars ─────
        private fun renderText(q: Map<String, Any?>) {
            val qid = q["id"]?.toString().orEmpty()
            val initial = (answers[qid] as? String).orEmpty()
            val edit = EditText(activity).apply {
                hint = "Tell us..."
                setHintTextColor(Color.parseColor(MUTED_HEX))
                setTextColor(Color.parseColor(TEXT_HEX))
                inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = false
                maxLines = 4
                minLines = 4
                setLines(4)
                gravity = Gravity.TOP or Gravity.START
                filters = arrayOf(InputFilter.LengthFilter(2000))
                setText(initial)
                setSelection(initial.length)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(Color.parseColor(SURFACE_HEX))
                    setStroke((1 * dp).toInt(), Color.parseColor(BORDER_HEX))
                }
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        // Persist quietly; don't trigger a full rebuild (would steal focus).
                        answers[qid] = s?.toString().orEmpty()
                        updateActionRow()
                    }
                })
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            edit.layoutParams = lp
            contentColumn.addView(edit)
        }

        // ───── End screen ─────
        private fun renderEndScreen() {
            val type = endScreen["type"]?.toString() ?: "thanks"
            val thanksText = endScreen["thanks_text"]?.toString().takeIf { !it.isNullOrBlank() }
                ?: "Thanks for your feedback!"
            val ctaText = endScreen["cta_text"]?.toString().takeIf { !it.isNullOrBlank() }
                ?: "Learn more"
            val ctaUrl = endScreen["cta_url"]?.toString()

            val thanks = TextView(activity).apply {
                text = thanksText
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT_HEX))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = (16 * dp).toInt()
                lp.bottomMargin = (16 * dp).toInt()
                layoutParams = lp
            }
            contentColumn.addView(thanks)

            if (type == "cta" && !ctaUrl.isNullOrBlank()) {
                val cta = TextView(activity).apply {
                    text = ctaText
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = 10 * dp
                        setColor(Color.parseColor(ACCENT_HEX))
                    }
                    setPadding((22 * dp).toInt(), (14 * dp).toInt(), (22 * dp).toInt(), (14 * dp).toInt())
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    lp.gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = lp
                    isClickable = true
                    setOnClickListener {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(ctaUrl)
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            activity.startActivity(intent)
                        }
                        Dijji.trackPushEvent("in_app_clicked", pushId = messageId)
                        dialog.dismiss()
                    }
                }
                contentColumn.addView(cta)
            } else if (type == "thanks" || type == "nothing") {
                // Auto-dismiss after 2.5s — matches Flutter parity.
                mainHandler.postDelayed({
                    runCatching { if (dialog.isShowing) dialog.dismiss() }
                }, 2500L)
            }
        }

        private fun updateActionRow() {
            backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
            val canAdvance = canAdvanceFromCurrent()
            val isLast = step == questions.size - 1
            nextBtn.text = if (isLast) "Submit" else "Next"
            nextBtn.isEnabled = canAdvance
            nextBtn.alpha = if (canAdvance) 1f else 0.45f
            nextBtn.background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(Color.parseColor(ACCENT_HEX))
            }
        }

        private fun canAdvanceFromCurrent(): Boolean {
            if (step >= questions.size) return true
            val q = questions[step]
            if (q["required"] != true) return true
            val v = answers[q["id"]?.toString().orEmpty()] ?: return false
            return when (v) {
                is String -> v.isNotBlank()
                is List<*> -> v.isNotEmpty()
                else -> true
            }
        }

        // ───── Submit answer + advance ─────
        private fun onNextClicked() {
            if (step >= questions.size) return
            if (!canAdvanceFromCurrent()) return
            val q = questions[step]
            val qid = q["id"]?.toString().orEmpty()
            val qtype = q["type"]?.toString().orEmpty()
            val raw = answers[qid]
            val value: Any = when {
                raw is List<*> -> raw.filterIsInstance<String>()
                raw == null    -> ""
                else           -> raw.toString()
            }
            val body = mapOf<String, Any?>(
                "action" to "answer",
                "site" to siteKey,
                "response_id" to responseId,
                "question_id" to qid,
                "question_type" to qtype,
                "value" to value,
            )
            postBody(body)

            step++
            if (step >= questions.size) {
                val completeBody = mapOf<String, Any?>(
                    "action" to "complete",
                    "site" to siteKey,
                    "response_id" to responseId,
                    "end_screen_seen" to 1,
                )
                postBody(completeBody)
                completed = true
            }
            renderCurrentStep()
        }

        private fun postBody(body: Map<String, Any?>) {
            val cb = MessageHost.onSurveyPost ?: return
            // Off the main thread — never block UI on a network round-trip.
            Thread({
                runCatching { cb(body) }
            }, "dijji-survey-post").apply { isDaemon = true }.start()
        }
    }
}
