package io.github.agopalareddy.umm.sync

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class CodeScanner(private val context: Context) {

    fun isAvailable(): Boolean {
        return runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    suspend fun scan(): String? = suspendCancellableCoroutine { continuation ->
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                if (continuation.isActive) {
                    continuation.resume(barcode.rawValue)
                }
            }
            .addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
    }
}

/**
 * When Play services is missing, returns null so only "Paste code" shows.
 */
fun scanPairingCode(context: Context): (suspend () -> String?)? {
    val scanner = CodeScanner(context)
    return if (scanner.isAvailable()) {
        { scanner.scan() }
    } else {
        null
    }
}
