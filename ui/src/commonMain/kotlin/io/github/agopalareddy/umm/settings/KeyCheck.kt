package io.github.agopalareddy.umm.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.ui.LocalUmm

sealed interface KeyCheck {
    data object Checking : KeyCheck
    data class Ok(val info: KeyInfo) : KeyCheck
    data object Rejected : KeyCheck
    data object Unreachable : KeyCheck
}

/** What OpenRouter says about [key] right now. */
@Composable
fun rememberKeyCheck(key: String?): KeyCheck {
    val openRouter = LocalUmm.current.openRouter
    val check by produceState<KeyCheck>(KeyCheck.Checking, key) {
        value = KeyCheck.Checking
        if (key != null) {
            value = try {
                KeyCheck.Ok(openRouter.keyInfo())
            } catch (e: OpenRouterException.Unauthorized) {
                KeyCheck.Rejected
            } catch (e: OpenRouterException) {
                KeyCheck.Unreachable
            }
        }
    }
    return check
}
