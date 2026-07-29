package com.zndtoshi.garminwidget.network

import com.zndtoshi.garminwidget.data.WidgetResponseParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class WidgetApiClient {
    fun refresh(baseUrl: String, bearerToken: String): String =
        request("POST", "$baseUrl/api/v1/widget/refresh", bearerToken)

    fun latest(baseUrl: String, bearerToken: String): String =
        request("GET", "$baseUrl/api/v1/widget/latest", bearerToken)

    private fun request(method: String, url: String, bearerToken: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.doInput = true

            val statusCode = connection.responseCode
            if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) throw WidgetAuthException()
            if (statusCode !in 200..299) throw IOException("Widget service unavailable ($statusCode)")

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .also(WidgetResponseParser::parse)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 45_000
    }
}

class WidgetAuthException : IOException("Widget authentication failed")
