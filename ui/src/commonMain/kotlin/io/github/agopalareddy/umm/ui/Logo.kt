package io.github.agopalareddy.umm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The launcher icon's mark on a theme-colored circle. */
@Composable
fun UmmLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            UmmMark,
            contentDescription = null, // always shown beside the app name, so TalkBack would say "Umm Umm"
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/** The icon's mark alone, cropped for in-app use; tint it from the theme. Same path as the launcher icon. */
private val UmmMark: ImageVector = ImageVector.Builder(
    name = "UmmMark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 54f,
    viewportHeight = 54f,
).addGroup(translationX = -27f, translationY = -29f)
    .addPath(
        pathData = addPathNodes(
            "M42,32 H66 A12,12 0 0 1 78,44 V58 A12,12 0 0 1 66,70 H50 L38,79 L41,69.6 A12,12 0 0 1 30,58 V44 " +
                "A12,12 0 0 1 42,32 Z M41.5,46 V56 H45.5 V46 Z M48.5,41 V61 H52.5 V41 Z M55.5,44 V58 H59.5 V44 Z " +
                "M62.5,48 V54 H66.5 V48 Z",
        ),
        fill = SolidColor(Color.White),
        pathFillType = PathFillType.EvenOdd,
    )
    .clearGroup()
    .build()
