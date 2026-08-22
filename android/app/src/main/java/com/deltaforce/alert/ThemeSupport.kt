package com.deltaforce.alert

import android.content.Context
import androidx.annotation.AttrRes

/** 6 套主题注册表与工具。 */
object ThemeIndex {
    val styles = intArrayOf(
        R.style.Theme_DeltaBulletAlert,          // A · 深蓝鎏金
        R.style.Theme_DeltaBulletAlert_Neon,     // B · 霓虹电竞
        R.style.Theme_DeltaBulletAlert_Emerald,  // C · 翡翠终端
        R.style.Theme_DeltaBulletAlert_Aurora,   // D · 琉璃星空
        R.style.Theme_DeltaBulletAlert_Mono,     // E · 纯黑暗色
        R.style.Theme_DeltaBulletAlert_Light,    // F · 白色亮色
    )

    fun styleOf(index: Int): Int = styles[index.coerceIn(0, styles.size - 1)]
}

/** 从当前主题解析颜色属性值。 */
fun Context.attrColor(@AttrRes attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    val c = a.getColor(0, 0)
    a.recycle()
    return c
}
