package com.limpe.tengerium.util

import android.animation.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.limpe.tengerium.R
import kotlinx.coroutines.*

/**
 * Централизованное хранилище анимаций приложения.
 */
object AnimationHelper {

    /**
     * Эффект "тряски" для Nudge.
     */
    fun animateNudge(view: View) {
        val density = view.resources.displayMetrics.density
        val offset = 12f * density
        val pvhX = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, offset)
        val pvhY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, offset / 2)
        ObjectAnimator.ofPropertyValuesHolder(view, pvhX, pvhY).apply {
            duration = 500
            interpolator = CycleInterpolator(5f)
            start()
        }
    }

    /**
     * Эффект "стук в экран" при отправке Nudge: рука дважды стучит по окну,
     * на месте удара расходится кольцо, окно вздрагивает.
     *
     * @param onKnock вызывается на каждом ударе (0 — первый, 1 — второй)
     */
    fun playNudgeKnock(root: ViewGroup, onKnock: (Int) -> Unit = {}) {
        if (!root.isAttachedToWindow || root.width == 0 || root.height == 0) return

        val density = root.resources.displayMetrics.density
        val overlay = FrameLayout(root.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }

        val handSize = (128 * density).toInt()
        val centerX = root.width * 0.66f
        val centerY = root.height * 0.38f

        val hand = ImageView(root.context).apply {
            setImageResource(R.drawable.nudge_hand)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
        }
        overlay.addView(hand, FrameLayout.LayoutParams(handSize, handSize).apply {
            leftMargin = (centerX - handSize / 2f).toInt()
            topMargin = (centerY - handSize / 2f).toInt()
        })

        val ringSize = (handSize * 1.35f).toInt()
        val ring = View(root.context).apply {
            background = ContextCompat.getDrawable(root.context, R.drawable.nudge_knock_ring)
            alpha = 0f
        }
        overlay.addView(ring, FrameLayout.LayoutParams(ringSize, ringSize).apply {
            leftMargin = (centerX - ringSize / 2f).toInt()
            topMargin = (centerY - ringSize / 2f).toInt()
        })

        root.addView(overlay)

        fun impact() {
            ring.animate().cancel()
            ring.scaleX = 0.35f
            ring.scaleY = 0.35f
            ring.alpha = 0.85f
            ring.animate().scaleX(1.6f).scaleY(1.6f).alpha(0f).setDuration(380).start()
            animateNudge(root)
        }

        fun knock(index: Int, startDelay: Long) {
            hand.animate().cancel()
            hand.translationX = 190 * density
            hand.translationY = 110 * density
            hand.rotation = 18f
            hand.scaleX = 0.9f
            hand.scaleY = 0.9f
            hand.alpha = 1f
            hand.animate()
                .translationX(0f)
                .translationY(0f)
                .rotation(-4f)
                .scaleX(1.02f)
                .scaleY(1.02f)
                .setStartDelay(startDelay)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    onKnock(index)
                    impact()
                    hand.animate()
                        .scaleX(1.16f)
                        .scaleY(1.16f)
                        .setDuration(70)
                        .withEndAction {
                            hand.animate()
                                .scaleX(1.02f)
                                .scaleY(1.02f)
                                .translationX(46 * density)
                                .translationY(16 * density)
                                .rotation(-10f)
                                .setDuration(140)
                                .withEndAction {
                                    if (index == 0) {
                                        knock(1, 120)
                                    } else {
                                        hand.animate()
                                            .alpha(0f)
                                            .translationX(240 * density)
                                            .translationY(130 * density)
                                            .scaleX(0.8f)
                                            .scaleY(0.8f)
                                            .setDuration(220)
                                            .withEndAction {
                                                (overlay.parent as? ViewGroup)?.removeView(overlay)
                                            }
                                            .start()
                                    }
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }

        knock(0, 0)
    }

    /**
     * Анимация логотипа на экране входа.
     */
    fun animateLoginLogo(logoView: View?) {
        logoView ?: return
        logoView.alpha = 0f
        logoView.scaleX = 0.2f
        logoView.scaleY = 0.2f

        logoView.animate()
            .alpha(1f)
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(700)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                logoView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    /**
     * Анимация появления полей ввода на экране входа.
     */
    fun animateLoginInputs(views: List<View?>, baseDelay: Long = 500L) {
        views.filterNotNull().forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 100f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(600)
                .setStartDelay(baseDelay + (index * 100L))
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    /**
     * Анимация появления сообщения.
     */
    fun animateMessagePop(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Анимация изменения статуса.
     */
    fun animateStatusChange(view: View, color: Int) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(150)
            .withEndAction {
                view.background?.setTint(color)
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(250)
                    .setInterpolator(AnticipateOvershootInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Анимация набора текста (троеточие).
     */
    fun startTypingAnimation(scope: CoroutineScope, textView: TextView, baseText: String): Job {
        return scope.launch {
            val isTypingSuffix = textView.context.getString(R.string.is_typing)
            val textWithoutDots = baseText.removeSuffix(isTypingSuffix).trim()
            var dots = 1
            while (isActive) {
                textView.text = "$textWithoutDots $isTypingSuffix${".".repeat(dots)}"
                dots = if (dots >= 3) 1 else dots + 1
                delay(500)
            }
        }
    }

    /**
     * Анимация появления In-App уведомления.
     */
    fun animateNotificationIn(view: View) {
        view.translationY = -300f
        view.alpha = 0f
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(UiConstants.ANIM_DURATION_LONG)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /**
     * Анимация исчезновения In-App уведомления.
     */
    fun animateNotificationOut(view: View, onEnd: () -> Unit) {
        view.animate()
            .translationY(-300f)
            .alpha(0f)
            .setDuration(UiConstants.ANIM_DURATION_LONG)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { onEnd() }
            .start()
    }

    /**
     * Анимация появления/скрытия (Alpha).
     */
    fun fadeVisibility(view: View, isVisible: Boolean, duration: Long = UiConstants.ANIM_DURATION_SHORT) {
        if (isVisible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(duration).start()
        } else {
            view.animate().alpha(0f).setDuration(duration).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }

    /**
     * Анимация перехода для экрана загрузки/авторизации.
     */
    fun animateLoadingState(mainView: View?, loadingView: View?, isLoading: Boolean) {
        if (isLoading) {
            mainView?.isEnabled = false
            mainView?.animate()?.alpha(0f)?.setDuration(UiConstants.ANIM_DURATION_MEDIUM)?.start()

            loadingView?.apply {
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(UiConstants.ANIM_DURATION_MEDIUM)
                    .setListener(null)
                    .start()
            }
        } else {
            mainView?.isEnabled = true
            mainView?.animate()?.alpha(1f)?.setDuration(UiConstants.ANIM_DURATION_MEDIUM)?.start()

            loadingView?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.setDuration(UiConstants.ANIM_DURATION_SHORT)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        loadingView.visibility = View.GONE
                    }
                })?.start()
        }
    }

    /**
     * Анимация подсветки элемента (Highlight).
     */
    fun animateHighlight(view: View): ValueAnimator? {
        val colorFrom = Color.TRANSPARENT
        val colorTo = try {
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        } catch (e: Exception) {
            Color.CYAN
        }

        return ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo, colorFrom).apply {
            duration = 1000
            repeatCount = 2
            addUpdateListener { anim ->
                view.setBackgroundColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    /**
     * Анимация появления списка сервисов (плавное выплывание снизу вверх).
     */
    fun animateServiceItemIn(view: View, delayMs: Long) {
        view.alpha = 0f
        view.translationY = 100f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(delayMs)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Анимация появления вкладки (Scale + Alpha).
     */
    fun animateTabIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(AnticipateOvershootInterpolator())
            .start()
    }

    /**
     * Анимация кнопки отправки: появление кружочка и смена цвета иконки.
     */
    fun animateSendButtonToggle(button: MaterialButton, active: Boolean) {
        val colorSurface = MaterialColors.getColor(button, com.google.android.material.R.attr.colorSurface)
        val colorPrimary = MaterialColors.getColor(button, com.google.android.material.R.attr.colorPrimary)
        val colorGray = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val targetBgColor = if (active) colorPrimary else Color.TRANSPARENT
        val targetIconColor = if (active) colorSurface else colorGray

        button.animate().cancel()

        // Анимация цвета фона (кружочка)
        val startBgColor = button.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
        ValueAnimator.ofObject(ArgbEvaluator(), startBgColor, targetBgColor).apply {
            duration = 250
            addUpdateListener { button.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int) }
            start()
        }

        // Анимация цвета иконки
        val startIconColor = button.iconTint?.defaultColor ?: colorGray
        ValueAnimator.ofObject(ArgbEvaluator(), startIconColor, targetIconColor).apply {
            duration = 250
            addUpdateListener { button.iconTint = ColorStateList.valueOf(it.animatedValue as Int) }
            start()
        }

        if (active) {
            button.scaleX = 0.5f
            button.scaleY = 0.5f
            button.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            button.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        }
    }

    /**
     * Анимация "засасывания" при отправке сообщения.
     */
    fun animateSendAction(view: MaterialButton, onEnd: () -> Unit) {
        view.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                onEnd()
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                animateSendButtonToggle(view, false)
            }
            .start()
    }

    /**
     * Анимация текстового значения (счётчик).
     */
    fun animateTextValue(textView: TextView, start: Int, end: Int, suffix: String = "") {
        ValueAnimator.ofInt(start, end).apply {
            duration = 1000
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                textView.text = "${it.animatedValue}$suffix"
            }
            start()
        }
    }
    /**
     * Умный показ PopupWindow.
     */
    fun showSmartPopup(popup: PopupWindow, anchor: View, preferredXOffset: Int = 0, preferredYOffset: Int = 0) {
        val menuView = popup.contentView
        menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val screenPos = IntArray(2)
        anchor.getLocationOnScreen(screenPos)
        val displayMetrics = anchor.resources.displayMetrics
        
        var finalX = screenPos[0] + preferredXOffset
        var finalY = screenPos[1] + anchor.height + preferredYOffset
        
        if (finalX + menuView.measuredWidth > displayMetrics.widthPixels) finalX = displayMetrics.widthPixels - menuView.measuredWidth - 16
        if (finalY + menuView.measuredHeight > displayMetrics.heightPixels) finalY = screenPos[1] - menuView.measuredHeight - preferredYOffset
        
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, finalX, finalY)
        
        menuView.alpha = 0f
        menuView.scaleX = 0.9f
        menuView.scaleY = 0.9f
        menuView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator())
            .start()
    }
}
