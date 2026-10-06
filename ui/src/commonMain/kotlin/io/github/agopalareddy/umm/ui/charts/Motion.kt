package io.github.agopalareddy.umm.ui.charts

import androidx.compose.runtime.Composable
import io.github.agopalareddy.umm.ui.LocalUmm

/**
 * False when the system animation scale is 0 ("remove animations"), so charts
 * should draw their final state instead of animating. Read on each composition;
 * a change made while the screen is open shows up on the next recomposition.
 */
@Composable
fun rememberMotionEnabled(): Boolean = LocalUmm.current.platform.animationsEnabled()
