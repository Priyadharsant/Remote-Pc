package com.example.remoteapp

object CameraActionBridge {
    @Volatile
    var isCameraScreenActive = false

    var onOpenRequested: (() -> Unit)? = null
    var onAction: ((action: String, value: String) -> Unit)? = null
}
