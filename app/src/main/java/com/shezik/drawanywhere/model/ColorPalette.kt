package com.shezik.drawanywhere.model

import androidx.compose.ui.graphics.Color

val PRESET_COLORS = listOf(
    Color(0xFFFF0000),
    Color(0xFFFF8800),
    Color(0xFF00CC00),
    Color(0xFF00CCCC),
    Color(0xFF0066FF),
    Color(0xFF9900FF),

    Color(0xFFCC4444),
    Color(0xFFCC8844),
    Color(0xFF448844),
    Color(0xFF448888),
    Color(0xFF4444CC),
    Color(0xFF8844CC),

    Color.White,
    Color(0xFFCCCCCC),
    Color(0xFF999999),
    Color(0xFF666666),
    Color(0xFF333333),
    Color.Black,
)

/** The ten swatches shown on the floating toolbar; also cycled by the stylus "cycle color" action. */
val TOOLBAR_COLORS = listOf(
    Color(0xFF000000),
    Color(0xFF2238A8),
    Color(0xFFE31D31),
    Color(0xFF8E5C5E),
    Color(0xFF7A58E4),
    Color(0xFF5165CC),
    Color(0xFF5BC3DB),
    Color(0xFFFFD03B),
    Color(0xFFFB681C),
    Color(0xFFF2F2F2),
)
