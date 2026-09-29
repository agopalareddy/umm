package io.github.agopalareddy.umm.ui.charts

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * False when the system animation scale is 0 ("remove animations"), so charts
 * should draw their final state instead of animating. Read on each composition;
 * a change made while the screen is open shows up on the next recomposition.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}
