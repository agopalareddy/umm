package io.github.agopalareddy.umm.desktop

import androidx.compose.ui.graphics.ImageBitmap
import io.github.agopalareddy.umm.core.data.CategoryRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import java.security.KeyPairGenerator
import java.util.ServiceLoader
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.freedesktop.dbus.spi.transport.ITransportProvider

object SelfCheck {
    fun run(): List<String> {
        val failures = mutableListOf<String>()

        fun check(name: String, block: () -> Unit) {
            runCatching(block).onFailure { error ->
                failures += "$name: ${error.message ?: error::class.simpleName}"
            }
        }

        check("sound") {
            val mixers = AudioSystem.getMixerInfo()
            checkNotNull(mixers)
        }

        check("database") {
            val db = inMemoryUmmDatabase()
            try {
                runBlocking {
                    val categories = CategoryRepository(db).observeAll().first()
                    check(categories.isNotEmpty()) { "no categories found" }
                }
            } finally {
                db.close()
            }
        }

        check("crypto") {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
            val iv = ByteArray(12) { 2 }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = "self-check".toByteArray(Charsets.UTF_8)
            val sealed = cipher.doFinal(plain)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val opened = cipher.doFinal(sealed)
            check(opened.contentEquals(plain)) { "AES-GCM mismatch" }

            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256)
            val pair = keyGen.generateKeyPair()
            check(pair.public != null && pair.private != null) { "EC key pair generation failed" }
        }

        check("tls") {
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, null, null)
            checkNotNull(ssl.socketFactory)
            val client = OkHttpClient.Builder().build()
            checkNotNull(client)
        }

        check("dbus") {
            val providers = ServiceLoader.load(ITransportProvider::class.java).toList()
            check(providers.isNotEmpty()) { "no ITransportProvider found" }
        }

        check("compose") {
            val bitmap = ImageBitmap(1, 1)
            check(bitmap.width == 1 && bitmap.height == 1) { "ImageBitmap allocation failed" }
        }

        return failures
    }
}
