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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.R

/** The launcher icon's mark on a theme-colored circle. */
@Composable
fun UmmLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_umm_mark),
            contentDescription = null, // always shown beside the app name, so TalkBack would say "Umm Umm"
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}
