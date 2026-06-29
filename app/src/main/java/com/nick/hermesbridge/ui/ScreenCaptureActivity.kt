package com.nick.hermesbridge.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent activity to request MediaProjection permission for screen capture.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG = "HermesCap"
        private const val REQ_SCREEN_CAPTURE = 2001

        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // Store intent for later use
                ScreenCaptureData.resultCode = resultCode
                ScreenCaptureData.data = data
                Log.i(TAG, "Screen capture permission granted")
            } else {
                Log.w(TAG, "Screen capture permission denied")
            }
        }
        finish()
    }
}

object ScreenCaptureData {
    var resultCode: Int = 0
    var data: Intent? = null
}
