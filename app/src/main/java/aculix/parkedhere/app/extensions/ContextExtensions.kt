package aculix.parkedhere.app.extensions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

/**
 * Shows the toast message
 */
fun Context.toast(message: String, length: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, length).show()
}

/**
 * Vibrates the devices for the specified time
 */
@Suppress("DEPRECATION")
fun Context.vibrate(timeInMillis: Long = 100) {
    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    val vibrationEffect =
        VibrationEffect.createOneShot(timeInMillis, VibrationEffect.DEFAULT_AMPLITUDE)

    if (vibrator.hasVibrator()) vibrator.vibrate(vibrationEffect)
}

