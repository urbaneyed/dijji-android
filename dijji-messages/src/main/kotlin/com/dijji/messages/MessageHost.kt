package com.dijji.messages

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.app.AppCompatActivity
import com.dijji.sdk.Dijji
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * In-app message host — renders banner / bottom-sheet / modal on top of the
 * foreground activity. Universal: works in View apps and Compose apps alike,
 * because everything is drawn with plain Android Views + Material Components.
 *
 * Called by the core SDK's InAppHandler when an inbox poll returns an
 * action. Dev never writes UI code for this.
 *
 * Thread-safety: must be invoked on the main thread. InAppHandler posts to
 * the main looper before calling.
 */
public object MessageHost {

    /**
     * Render a message by config. Dispatches on action_type.
     * @param activity Activity whose decor view will host the overlay. Pass the
     *                 current foreground activity via Dijji.currentActivity().
     * @param messageId Opaque ID for outcome reporting (dismissed / clicked).
     * @param actionType One of: in_app_banner | in_app_bottom_sheet | in_app_modal
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
            "in_app_banner"       -> renderBanner(activity, messageId, cfg)
            "in_app_bottom_sheet" -> renderBottomSheet(activity, messageId, cfg)
            "in_app_modal"        -> renderModal(activity, messageId, cfg)
            else -> { /* silently ignore unknown; core logs */ }
        }
    }

    // ───── Banner: top or bottom strip, non-blocking ─────

    private fun renderBanner(activity: Activity, id: String, cfg: Map<String, Any?>) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val title = cfg["title"]?.toString().orEmpty()
        val body  = cfg["body"]?.toString().orEmpty()
        val ctaText = cfg["cta_text"]?.toString()
        val position = (cfg["position"]?.toString() ?: "top").lowercase()
        val accent = parseColor(cfg["accent"]?.toString(), 0xFF7C3AED.toInt())

        val dp = activity.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#111118"))
                setStroke((1 * dp).toInt(), accent and 0x40FFFFFF.toInt())
            }
            elevation = 16 * dp
        }

        val accentBar = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams((3 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = (12 * dp).toInt()
            }
            background = ColorDrawable(accent)
        }
        container.addView(accentBar)

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

        // Layout params — overlay position depends on top/bottom
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

        // Dismiss button
        val closeBtn = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#6b7280"))
            setBackgroundColor(Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
            layoutParams = lp
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

        // Image (optional)
        if (!imageUrl.isNullOrBlank()) {
            val img = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (160 * dp).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(Color.parseColor("#1a1a24"))
                }
            }
            root.addView(img)
            // Note: no image loader dependency — host apps that want actual image
            // display should pre-fetch + pass a Drawable via onInAppMessage callback.
            // For v1 we show a placeholder.
            (root.getChildAt(root.childCount - 1) as LinearLayout.LayoutParams?)?.bottomMargin = (14 * dp).toInt()
        }

        if (title.isNotBlank()) {
            root.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = (8 * dp).toInt()
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
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#0F0F18"))
                setStroke((1 * dp).toInt(), Color.parseColor("#2a2a3a"))
            }
            elevation = 32 * dp
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
        card.addView(close)

        if (title.isNotBlank()) {
            card.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        if (body.isNotBlank()) {
            card.addView(TextView(activity).apply {
                text = body
                setTextColor(Color.parseColor("#D1D5DB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (12 * dp).toInt()
                lp.bottomMargin = (24 * dp).toInt()
                layoutParams = lp
            })
        }

        if (!ctaText.isNullOrBlank()) {
            card.addView(MaterialButton(activity).apply {
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

        container.addView(card)
        dialog.setContentView(container)
        dialog.setOnCancelListener {
            Dijji.trackPushEvent("in_app_dismissed", pushId = id)
        }
        dialog.show()
        Dijji.trackPushEvent("in_app_shown", pushId = id)
    }

    // ───── helpers ─────

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
