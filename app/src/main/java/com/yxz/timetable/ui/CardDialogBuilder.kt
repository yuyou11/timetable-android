package com.yxz.timetable.ui

import android.content.Context
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.yxz.timetable.R

/**
 * 应用里的对话框统一用**卡片那张皮**：圆角 @dimen/card_radius、底色 card_bg、
 * 1dp card_stroke 描边 —— 和页面里的卡片（bg_card）一模一样。
 *
 * ## 为什么要自己搭一层
 *
 * 页面里的卡片都是 `@drawable/bg_card`（16dp 圆角 + 描边），
 * 而 `AlertDialog` 的圆角来自 Material 主题：默认 28dp、没有描边、底色还带
 * surface tint。弹出来和底下的卡片摆在一起，像两个 App 的东西。
 *
 * 这里不去改主题（Material 的对话框形状由 `shapeAppearance` 决定，
 * 覆盖主题属性在不同版本上生效情况不一），而是**直接把窗口背景换成同一个形状**：
 * 用 [MaterialShapeDrawable] 而不是普通 shape，是因为它自带 outline ——
 * 对话框的投影靠它算，换成普通 drawable 会把阴影一起弄没。
 *
 * 用法：把 `AlertDialog.Builder(...)` 换成 [CardAlertDialogBuilder] 即可，
 * 其余链式调用完全不变。
 */
class CardAlertDialogBuilder(context: Context) : AlertDialog.Builder(context) {

    override fun create(): AlertDialog = super.create().also { it.matchCardCorners() }
}

/** 把已经建好的对话框套上卡片的圆角 / 底色 / 描边 */
fun AlertDialog.matchCardCorners() {
    val w = window ?: return
    val density = context.resources.displayMetrics.density
    val radius = context.resources.getDimension(R.dimen.card_radius)

    val shape = MaterialShapeDrawable(
        ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, radius)
            .build()
    ).apply {
        fillColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.card_bg))
        setStroke(1 * density, ContextCompat.getColor(context, R.color.card_stroke))
    }
    w.setBackgroundDrawable(shape)
}
