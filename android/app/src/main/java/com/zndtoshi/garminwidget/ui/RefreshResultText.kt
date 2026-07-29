package com.zndtoshi.garminwidget.ui

import com.zndtoshi.garminwidget.data.LocalStatus

fun refreshResultText(status: LocalStatus): String = when (status) {
    LocalStatus.READY -> "Refresh complete. The widget is up to date."
    LocalStatus.AUTH_ERROR -> "Token rejected. Check the Render bearer token."
    LocalStatus.NETWORK_ERROR -> "Could not reach the backend. Try again."
    LocalStatus.NOT_CONFIGURED -> "Enter and save the widget token."
    else -> "Refresh did not complete. Try again."
}
