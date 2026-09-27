package io.github.agopalareddy.umm.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun passwordVariationsAreDetected() {
        assertTrue(FieldPolicy.isPassword(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(FieldPolicy.isPassword(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(FieldPolicy.isPassword(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(FieldPolicy.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun ordinaryFieldsAreNotPasswords() {
        assertFalse(FieldPolicy.isPassword(text))
        assertFalse(FieldPolicy.isPassword(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(FieldPolicy.isPassword(InputType.TYPE_CLASS_NUMBER))
    }

    @Test fun multiLineFlag() {
        assertTrue(FieldPolicy.isMultiLine(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(FieldPolicy.isMultiLine(text))
    }
}
