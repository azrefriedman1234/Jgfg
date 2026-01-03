package com.pasiflonet.mobile.ui.overlay

import kotlinx.serialization.Serializable

@Serializable
data class BlurRectNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

@Serializable
data class EditPlan(
    val watermarkX: Float? = null,
    val watermarkY: Float? = null,
    val blurRects: List<BlurRectNorm> = emptyList()
)
