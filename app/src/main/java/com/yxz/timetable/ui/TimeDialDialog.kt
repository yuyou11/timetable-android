package com.yxz.timetable.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import com.yxz.timetable.R
import com.yxz.timetable.databinding.DialogTimeDialBinding

/**
 * 两步选时间：**先小时盘 → 确定 → 再分钟盘 → 确定**。
 *
 * 为什么不一张盘上装两个指针：早上半梦半醒的人分不清「我现在拖的是时还是分」，
 * 一根指针一件事最不容易出错。两步的代价只是多按一次确定。
 *
 * 切换时整张盘会缩一下再淡入（不是硬切）—— 否则用户会以为还是同一张盘，
 * 只是在原来的位置上又转了一圈。
 */
class TimeDialDialog(
    private val ctx: Context,
    private var hour: Int,
    private var minute: Int,
    private val onConfirm: (hour: Int, minute: Int) -> Unit
) {

    private val binding = DialogTimeDialBinding.inflate(LayoutInflater.from(ctx))
    private val dialog = Dialog(ctx)
    private var step = STEP_HOUR

    fun show(): Dialog {
        dialog.setContentView(binding.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            val density = ctx.resources.displayMetrics.density
            val width = (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt()
                .coerceAtMost((352 * density).toInt())
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f)
            setWindowAnimations(R.style.CourseCardWindowAnim)
        }

        binding.dial.onPick = { updatePreview() }
        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnOk.setOnClickListener { confirm() }

        applyStep(STEP_HOUR, animate = false)
        dialog.show()
        return dialog
    }

    // ------------------------------------------------------------------

    private fun confirm() {
        if (step == STEP_HOUR) {
            // 「拖到哪一格、再按确定」—— 这才是把小时定下来
            hour = binding.dial.value
            applyStep(STEP_MINUTE, animate = true)
        } else {
            minute = binding.dial.value
            onConfirm(hour, minute)
            dialog.dismiss()
        }
    }

    private fun applyStep(to: Int, animate: Boolean) {
        step = to
        val apply = {
            binding.dial.cancelAnimation()
            if (to == STEP_HOUR) {
                binding.dial.unit = "时"
                binding.dial.cellCount = 24
                binding.dial.value = hour
                binding.tvTitle.text = "选择小时"
            } else {
                binding.dial.unit = "分"
                binding.dial.cellCount = 60
                binding.dial.value = minute
                binding.tvTitle.text = "选择分钟"
            }
            updatePreview()
        }

        if (!animate) {
            apply()
            return
        }
        // 缩一下 → 换盘 → 淡入。别硬切：硬切会让人以为指针自己跳了一下
        binding.dial.animate()
            .alpha(0f).scaleX(0.88f).scaleY(0.88f)
            .setDuration(110)
            .withEndAction {
                apply()
                binding.dial.alpha = 0f
                binding.dial.scaleX = 0.88f
                binding.dial.scaleY = 0.88f
                binding.dial.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(190)
                    .start()
            }
            .start()
    }

    private fun updatePreview() {
        val h = if (step == STEP_HOUR) binding.dial.value else hour
        val m = if (step == STEP_HOUR) minute else binding.dial.value
        val tail = if (step == STEP_HOUR) "先选小时" else "再选分钟"
        binding.tvPreview.text = "%02d:%02d · %s".format(h, m, tail)
    }

    private companion object {
        const val STEP_HOUR = 0
        const val STEP_MINUTE = 1
    }
}
