package io.github.agopalareddy.umm

import io.github.agopalareddy.umm.core.auth.SecretCipher

class TestUmmApp : UmmApp() {
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(blob: ByteArray): ByteArray = blob
    }

    override fun createGraph(): AppGraph = AppGraph(
        this,
        apiCipher = FakeCipher(),
        syncCipher = FakeCipher(),
    )
}
