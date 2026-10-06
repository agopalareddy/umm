package io.github.agopalareddy.umm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.agopalareddy.umm.ui.LocalUmm

/** Gives shared composables their [LocalUmm]; every Compose host in the app wraps its content in this. */
@Composable
fun ProvideUmm(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val services = remember(context) { context.graph.services(context) }
    CompositionLocalProvider(LocalUmm provides services, content = content)
}
