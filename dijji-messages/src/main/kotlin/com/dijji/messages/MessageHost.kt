package com.dijji.messages

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dijji.sdk.Dijji
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Callback signature for the survey renderer to push answers/completes back
 * to dijji-core's Api.postSurvey. dijji-core wires this on init via reflection
 * (see Dijji.init), so dijji-messages stays unaware of OkHttp / Moshi / etc.
 */
public typealias SurveyPostCallback = (Map<String, Any?>) -> Unit

/**
 * In-app message host — renders 8 formats on top of the foreground activity.
 * Universal: works in View apps and Compose apps alike, drawn with plain
 * Android Views + Material Components. No image-loading dependency: a tiny
 * built-in HttpURLConnection-backed loader handles `image_url` fields.
 *
 * Called by the core SDK's InAppHandler when an inbox poll returns an action.
 *
 * Thread-safety: must be invoked on the main thread. InAppHandler posts to
 * the main looper before calling.
 */
public object MessageHost {

    private val imageExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dijji-img-loader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Pluggable POST sink for the in_app_survey renderer. dijji-core wires
     * this in [com.dijji.sdk.Dijji.init] (via reflection on the auto-generated
     * Java setter `setOnSurveyPost`) so SurveyView can fire-and-forget each
     * answer/complete without dijji-messages owning HTTP. Stays null if
     * dijji-core wasn't initialized (then survey posts silently no-op).
     */
    @JvmStatic
    public var onSurveyPost: SurveyPostCallback? = null

    /**
     * Render a message by config. Dispatches on action_type.
     * @param activity Activity whose decor view will host the overlay. Pass the
     *                 current foreground activity via Dijji.currentActivity().
     * @param messageId Opaque ID for outcome reporting (dismissed / clicked).
     * @param actionType One of: in_app_banner | in_app_bottom_sheet | in_app_modal
     *                          | in_app_hero | in_app_nps | in_app_reactions
     *                          | in_app_countdown | in_app_survey
     * @param cfg action_config map (title, body, cta_text, cta_url, image_url, etc.)
     */
    @JvmStatic
    public fun show(
        activity: Activity,
        messageId: String,
        actionType: String,
        cfg: Map<String, Any?>,
    ) {
        when (actionType) {
            "in_app_banner"        -> renderBanner(activity, messageId, cfg)
            "in_app_bottom_sheet"  -> renderBottomSheet(activity, messageId, cfg)
            "in_app_modal"         -> renderModal(activity, messageId, cfg)
            "in_app_hero"          -> renderHero(activity, messageId, cfg)
            "in_app_nps"           -> renderNps(activity, messageId, cfg)
            "in_app_reactions"     -> renderReactions(activity, messageId, cfg)
            "in_app_countdown"     -> renderCountdown(activity, messageId, cfg)
            "in_app_survey"        -> SurveyView.show(activity, messageId, cfg)
            else -> { /* silently ignore unknown; core logs */ }
        }
    }

    // ───── Banner: top or bottom strip, non-blocking ─────

    private fun renderBanner(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val title = cfg["title"]?.toString().orEmpty()
        val body  = cfg["body"]?.toString().orEmpty()
        val ctaText = cfg["cta_text"]?.toString()
        val imageUrl = cfg["image_url"]?.toString()
        val position = (cfg["position"]?.toString() ?: "top").lowercase()
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val dp = activity.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#111118"))
                setStroke((1 * dp).toInt(), accent and 0x40FFFFFF.toInt())
            }
            elevation = 16 * dp
        }

        val accentBar = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams((3 * dp).toInt(), (40 * dp).toInt()).apply {
                marginEnd = (12 * dp).toInt()
            }
            background = ColorDrawable(accent)
        }
        container.addView(accentBar)

        // Optional left thumbnail
        if (!imageUrl.isNullOrBlank()) {
            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                    marginEnd = (10 * dp).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(Color.parseColor("#1a1a24"))
                }
            }
            container.addView(thumb)
            loadImageInto(thumb, imageUrl)
        }

        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (title.isNotBlank()) {
            textCol.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        if (body.isNotBlank()) {
            textCol.addView(TextView(activity).apply {
                text = body
                setTextColor(Color.parseColor("#D1D5DB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        container.addView(textCol)

        val frame = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (position == "bottom") Gravity.BOTTOM else Gravity.TOP
            val margin = (16 * dp).toInt()
            setMargins(margin, margin, margin, margin)
        }

        if (!ctaText.isNullOrBlank()) {
            container.addView(MaterialButton(activity).apply {
                text = ctaText
                setBackgroundColor(accent)
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginStart = (12 * dp).toInt()
                layoutParams = lp
                setOnClickListener {
                    Dijji.trackPushEvent("in_app_clicked", pushId = id)
                    navigateDeepLink(activity, cfg["cta_url"]?.toString())
                    (container.parent as? ViewGroup)?.removeView(container)
                }
            })
        }

        val closeBtn = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#6b7280"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
            setOnClickListener {
                Dijji.trackPushEvent("in_app_dismissed", pushId = id)
                (container.parent as? ViewGroup)?.removeView(container)
            }
        }
        container.addView(closeBtn)

        root.addView(container, frame)
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── Bottom sheet: image + title + body + optional CTA ─────

    private fun renderBottomSheet(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val title = cfg["title"]?.toString().orEmpty()
        val body  = cfg["body"]?.toString().orEmpty()
        val ctaText = cfg["cta_text"]?.toString()
        val ctaUrl  = cfg["cta_url"]?.toString()
        val imageUrl = cfg["image_url"]?.toString()
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val dialog = BottomSheetDialog(activity)
        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                val r = 16 * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#0F0F18"))
            }
        }

        if (!imageUrl.isNullOrBlank()) {
            val imgWrap = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(Color.parseColor("#1a1a24"))
                }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (180 * dp).toInt()
                ).apply { bottomMargin = (16 * dp).toInt() }
            }
            val img = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            imgWrap.addView(img)
            root.addView(imgWrap)
            loadImageInto(img, imageUrl)
        }

        if (title.isNotBlank()) {
            root.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (8 * dp).toInt()
                layoutParams = lp
            })
        }
        if (body.isNotBlank()) {
            root.addView(TextView(activity).apply {
                text = body
                setTextColor(Color.parseColor("#D1D5DB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (20 * dp).toInt()
                layoutParams = lp
            })
        }

        if (!ctaText.isNullOrBlank()) {
            root.addView(MaterialButton(activity).apply {
                text = ctaText
                setBackgroundColor(accent)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()
                )
                cornerRadius = (10 * dp).toInt()
                setOnClickListener {
                    Dijji.trackPushEvent("in_app_clicked", pushId = id)
                    navigateDeepLink(activity, ctaUrl)
                    dialog.dismiss()
                }
            })
        }

        dialog.setContentView(root)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }
        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── Modal: full-screen overlay for critical prompts ─────

    private fun renderModal(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val title = cfg["title"]?.toString().orEmpty()
        val body  = cfg["body"]?.toString().orEmpty()
        val ctaText = cfg["cta_text"]?.toString()
        val ctaUrl  = cfg["cta_url"]?.toString()
        val imageUrl = cfg["image_url"]?.toString()
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val dialog = android.app.Dialog(activity).apply {
            window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000000")))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setCancelable(true)
        }

        val dp = activity.resources.displayMetrics.density
        val pad = (28 * dp).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#0F0F18"))
                setStroke((1 * dp).toInt(), Color.parseColor("#2a2a3a"))
            }
            elevation = 32 * dp
            clipToOutline = true
        }

        if (!imageUrl.isNullOrBlank()) {
            val img = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (180 * dp).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1a1a24"))
                }
            }
            card.addView(img)
            loadImageInto(img, imageUrl)
        }

        val cardInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val close = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#6b7280"))
            setBackgroundColor(Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
            lp.gravity = Gravity.END
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }
        cardInner.addView(close)

        if (title.isNotBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            })
        }
        if (body.isNotBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = body
                setTextColor(Color.parseColor("#D1D5DB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (12 * dp).toInt()
                lp.bottomMargin = (24 * dp).toInt()
                layoutParams = lp
            })
        }

        if (!ctaText.isNullOrBlank()) {
            cardInner.addView(MaterialButton(activity).apply {
                text = ctaText
                setBackgroundColor(accent)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()
                )
                cornerRadius = (10 * dp).toInt()
                setOnClickListener {
                    Dijji.trackPushEvent("in_app_clicked", pushId = id)
                    navigateDeepLink(activity, ctaUrl)
                    dialog.dismiss()
                }
            })
        }

        card.addView(cardInner)
        container.addView(card)
        dialog.setContentView(container)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }
        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── Hero: full-bleed takeover with hero image + dual CTA ─────

    private fun renderHero(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val title = cfg["title"]?.toString().orEmpty()
        val body  = cfg["body"]?.toString().orEmpty()
        val ctaText = cfg["cta_text"]?.toString()
        val ctaUrl  = cfg["cta_url"]?.toString()
        val secondaryCta = cfg["secondary_cta_text"]?.toString()
        val imageUrl = cfg["image_url"]?.toString()
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val dialog = android.app.Dialog(activity).apply {
            window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000000")))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setCancelable(true)
        }

        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(Color.parseColor("#12121C"))
            }
            elevation = 32 * dp
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { /* let card hug content */ }
        }

        // Hero image area with gradient overlay
        val imgFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (260 * dp).toInt()
            )
            background = GradientDrawable().apply {
                setColor(accent and 0x33FFFFFF.toInt())
            }
        }
        if (!imageUrl.isNullOrBlank()) {
            val img = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            imgFrame.addView(img)
            loadImageInto(img, imageUrl)
        }
        // Gradient veil for legibility
        val veil = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#D912121C"))
            )
        }
        imgFrame.addView(veil)

        // Top-right close
        val close = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#59000000"))
            }
            val lp = FrameLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (12 * dp).toInt()
                marginEnd = (12 * dp).toInt()
            }
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }
        imgFrame.addView(close)
        card.addView(imgFrame)

        val cardInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (22 * dp).toInt(), pad, pad)
        }

        if (title.isNotBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.parseColor("#F5F5FA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            })
        }
        if (body.isNotBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = body
                setTextColor(Color.parseColor("#B5B5C8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (10 * dp).toInt()
                lp.bottomMargin = (22 * dp).toInt()
                layoutParams = lp
            })
        }
        if (!ctaText.isNullOrBlank()) {
            cardInner.addView(MaterialButton(activity).apply {
                text = ctaText
                setBackgroundColor(accent)
                setTextColor(Color.WHITE)
                cornerRadius = (10 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
                )
                setOnClickListener {
                    Dijji.trackPushEvent("in_app_clicked", pushId = id)
                    navigateDeepLink(activity, ctaUrl)
                    dialog.dismiss()
                }
            })
        }
        if (!secondaryCta.isNullOrBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = secondaryCta
                setTextColor(Color.parseColor("#7A7A90"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()
                )
                lp.topMargin = (8 * dp).toInt()
                layoutParams = lp
                setOnClickListener {
                    Dijji.trackPushEvent("in_app_clicked", pushId = id, extra = mapOf("choice" to "secondary"))
                    dialog.dismiss()
                }
            })
        }

        card.addView(cardInner)
        container.addView(card)
        dialog.setContentView(container)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }
        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── NPS: 0-10 colour-graded score sheet ─────

    private fun renderNps(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val question = cfg["question"]?.toString() ?: "How likely are you to recommend us?"
        val lowLabel  = cfg["low_label"]?.toString() ?: "Not likely"
        val highLabel = cfg["high_label"]?.toString() ?: "Extremely likely"
        val thanks    = cfg["thanks"]?.toString() ?: "Thanks for the feedback!"
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val dialog = BottomSheetDialog(activity)
        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                val r = 16 * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#0F0F18"))
            }
        }

        // Track whether user has submitted to swap UI to thank-you state
        val contentSwitcher = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Question state
        val questionView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        questionView.addView(TextView(activity).apply {
            text = question
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (18 * dp).toInt()
            layoutParams = lp
        })

        val scoresRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (i in 0..10) {
            val color = when {
                i <= 6 -> 0xFFE57373.toInt()
                i <= 8 -> 0xFFFFB74D.toInt()
                else   -> 0xFF66BB6A.toInt()
            }
            scoresRow.addView(TextView(activity).apply {
                text = i.toString()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f)
                lp.marginStart = if (i == 0) 0 else (3 * dp).toInt()
                layoutParams = lp
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(color and 0x33FFFFFF.toInt())
                    setStroke((1 * dp).toInt(), color)
                }
                setOnClickListener {
                    Dijji.trackPushEvent("__dijji_nps_submitted",
                        pushId = id,
                        extra = mapOf("score" to i))
                    // Swap to thanks
                    contentSwitcher.removeAllViews()
                    val thanksView = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(0, (22 * dp).toInt(), 0, (22 * dp).toInt())
                    }
                    thanksView.addView(TextView(activity).apply {
                        text = "✓"
                        setTextColor(accent)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                        gravity = Gravity.CENTER
                    })
                    thanksView.addView(TextView(activity).apply {
                        text = thanks
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = (12 * dp).toInt()
                        layoutParams = lp
                    })
                    contentSwitcher.addView(thanksView)
                    mainHandler.postDelayed({ runCatching { dialog.dismiss() } }, 1400L)
                }
            })
        }
        questionView.addView(scoresRow)

        val labelsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
        }
        labelsRow.addView(TextView(activity).apply {
            text = lowLabel
            setTextColor(Color.parseColor("#7A7A90"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        labelsRow.addView(TextView(activity).apply {
            text = highLabel
            setTextColor(Color.parseColor("#7A7A90"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        questionView.addView(labelsRow)

        contentSwitcher.addView(questionView)
        root.addView(contentSwitcher)

        dialog.setContentView(root)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }
        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── Reactions: emoji feedback bar ─────

    private fun renderReactions(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val question = cfg["question"]?.toString() ?: "How was that?"
        val thanks   = cfg["thanks"]?.toString() ?: "Thanks 🙏"
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())
        val rawEmojis = cfg["emojis"]
        val emojis: List<String> = when (rawEmojis) {
            is List<*> -> rawEmojis.filterIsInstance<String>().filter { it.isNotEmpty() }
            else -> emptyList()
        }.ifEmpty { listOf("😍", "🙂", "😐", "😕") }

        val dialog = BottomSheetDialog(activity)
        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                val r = 16 * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#0F0F18"))
            }
        }

        val switcher = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val questionView = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        questionView.addView(TextView(activity).apply {
            text = question
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (16 * dp).toInt()
            layoutParams = lp
        })

        val emojiRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        emojis.forEachIndexed { idx, emoji ->
            emojiRow.addView(TextView(activity).apply {
                text = emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                gravity = Gravity.CENTER
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (4 * dp).toInt()
                lp.marginEnd = (4 * dp).toInt()
                layoutParams = lp
                isClickable = true
                setOnClickListener {
                    Dijji.trackPushEvent("__dijji_reaction_submitted",
                        pushId = id,
                        extra = mapOf("reaction" to emoji, "index" to idx))
                    switcher.removeAllViews()
                    val thanksView = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                    }
                    thanksView.addView(TextView(activity).apply {
                        text = emoji
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
                        gravity = Gravity.CENTER
                    })
                    thanksView.addView(TextView(activity).apply {
                        text = thanks
                        setTextColor(accent)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = (10 * dp).toInt()
                        layoutParams = lp
                    })
                    switcher.addView(thanksView)
                    mainHandler.postDelayed({ runCatching { dialog.dismiss() } }, 1100L)
                }
            })
        }
        questionView.addView(emojiRow)

        switcher.addView(questionView)
        root.addView(switcher)

        dialog.setContentView(root)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }
        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── Countdown: live ticker urgency modal ─────

    private fun renderCountdown(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val title = cfg["title"]?.toString().orEmpty()
        val body  = cfg["body"]?.toString().orEmpty()
        val ctaText = cfg["cta_text"]?.toString()
        val ctaUrl  = cfg["cta_url"]?.toString()
        val endedText = cfg["ended_text"]?.toString() ?: "Time's up."
        val imageUrl = cfg["image_url"]?.toString()
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val deadlineMs = parseDeadline(cfg["deadline"])
        if (deadlineMs == null) return

        val dialog = android.app.Dialog(activity).apply {
            window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000000")))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setCancelable(true)
        }

        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#0F0F18"))
            }
            elevation = 32 * dp
            clipToOutline = true
        }

        if (!imageUrl.isNullOrBlank()) {
            val img = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (180 * dp).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            card.addView(img)
            loadImageInto(img, imageUrl)
        }

        val cardInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        if (title.isNotBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (16 * dp).toInt()
                layoutParams = lp
            })
        }

        // Timer cells (D : HH : MM : SS) — values updated each second
        val timerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val cellD = makeTimerCell(activity, "0", "d", accent)
        val cellH = makeTimerCell(activity, "00", "h", accent)
        val cellM = makeTimerCell(activity, "00", "m", accent)
        val cellS = makeTimerCell(activity, "00", "s", accent)
        timerRow.addView(cellD)
        timerRow.addView(makeColon(activity))
        timerRow.addView(cellH)
        timerRow.addView(makeColon(activity))
        timerRow.addView(cellM)
        timerRow.addView(makeColon(activity))
        timerRow.addView(cellS)
        cardInner.addView(timerRow)

        val endedView = TextView(activity).apply {
            text = endedText
            setTextColor(Color.parseColor("#E57373"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        cardInner.addView(endedView)

        if (body.isNotBlank()) {
            cardInner.addView(TextView(activity).apply {
                text = body
                setTextColor(Color.parseColor("#B5B5C8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * dp).toInt()
                layoutParams = lp
            })
        }

        val ctaBtn = if (!ctaText.isNullOrBlank()) MaterialButton(activity).apply {
            text = ctaText
            setBackgroundColor(accent)
            setTextColor(Color.WHITE)
            cornerRadius = (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            ).apply { topMargin = (22 * dp).toInt() }
            setOnClickListener {
                Dijji.trackPushEvent("in_app_clicked", pushId = id)
                navigateDeepLink(activity, ctaUrl)
                dialog.dismiss()
            }
        } else null
        if (ctaBtn != null) cardInner.addView(ctaBtn)

        card.addView(cardInner)
        container.addView(card)
        dialog.setContentView(container)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }

        // Tick every second; cancel when dialog dismisses
        val tick = object : Runnable {
            override fun run() {
                val remaining = deadlineMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    timerRow.visibility = View.GONE
                    endedView.visibility = View.VISIBLE
                    ctaBtn?.isEnabled = false
                    return
                }
                val totalSecs = remaining / 1000L
                val days  = totalSecs / 86400L
                val hours = (totalSecs / 3600L) % 24L
                val mins  = (totalSecs / 60L) % 60L
                val secs  = totalSecs % 60L
                (cellD.getChildAt(0) as TextView).text = days.toString()
                cellD.visibility = if (days > 0) View.VISIBLE else View.GONE
                (cellH.getChildAt(0) as TextView).text = hours.toString().padStart(2, '0')
                (cellM.getChildAt(0) as TextView).text = mins.toString().padStart(2, '0')
                (cellS.getChildAt(0) as TextView).text = secs.toString().padStart(2, '0')
                mainHandler.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener { mainHandler.removeCallbacks(tick) }
        tick.run()

        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    private fun makeTimerCell(activity: Activity, value: String, label: String, accent: Int): LinearLayout {
        val dp = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = (4 * dp).toInt()
            lp.marginEnd = (4 * dp).toInt()
            layoutParams = lp
            addView(TextView(activity).apply {
                text = value
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(accent and 0x1FFFFFFF.toInt())
                    setStroke((1 * dp).toInt(), accent and 0x66FFFFFF.toInt())
                }
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            })
            addView(TextView(activity).apply {
                text = label
                setTextColor(Color.parseColor("#7A7A90"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                val lp2 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp2.topMargin = (4 * dp).toInt()
                layoutParams = lp2
            })
        }
    }

    private fun makeColon(activity: Activity): TextView {
        val dp = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = ":"
            setTextColor(Color.parseColor("#4A4A5C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * dp).toInt()
            layoutParams = lp
        }
    }

    // ───── helpers ─────

    /**
     * Resolves a deadline config value into epoch ms. Accepts:
     *  - ISO-8601 strings (`2026-05-01T18:00:00Z`)
     *  - Relative offsets (`+24 hours`, `+30 minutes`, `+3 days`)
     *  - Unix-seconds number
     *  Returns null when unparseable — caller drops the message rather than
     *  rendering a broken timer.
     */
    private fun parseDeadline(raw: Any?): Long? {
        if (raw == null) return null
        if (raw is Number) return raw.toLong() * 1000L
        if (raw !is String || raw.isEmpty()) return null
        val s = raw.trim()
        if (s.startsWith("+")) {
            val match = Regex("^\\+\\s*(\\d+)\\s*(second|minute|hour|day|week)s?$",
                RegexOption.IGNORE_CASE).find(s) ?: return null
            val n = match.groupValues[1].toLong()
            val unit = match.groupValues[2].lowercase()
            val ms = when (unit) {
                "second" -> n * 1000L
                "minute" -> n * 60_000L
                "hour"   -> n * 3_600_000L
                "day"    -> n * 86_400_000L
                "week"   -> n * 604_800_000L
                else -> return null
            }
            return System.currentTimeMillis() + ms
        }
        return runCatching {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        }.recoverCatching {
            java.time.Instant.parse(s).toEpochMilli()
        }.getOrNull()
    }

    /**
     * Async image loader — fetches via HttpURLConnection, decodes to Bitmap on
     * a background thread, posts back to the ImageView on main. Fail-soft: any
     * exception leaves the placeholder background and never throws to caller.
     * No third-party dependency to keep the SDK lean.
     */
    private fun loadImageInto(target: ImageView, url: String) {
        imageExec.execute {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val bm = BitmapFactory.decodeStream(conn.inputStream)
                    if (bm != null) {
                        mainHandler.post { target.setImageBitmap(bm) }
                    }
                }
                conn.disconnect()
            }
        }
    }

    private fun navigateDeepLink(activity: Activity, url: String?) {
        if (url.isNullOrBlank() || url == "#close") return
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }
    }

    private fun parseColor(hex: String?, fallback: Int): Int {
        if (hex.isNullOrBlank()) return fallback
        return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
    }
}
