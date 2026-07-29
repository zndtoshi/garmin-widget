package com.zndtoshi.garminwidget.data

import org.json.JSONObject

object WidgetResponseParser {
    fun parse(rawJson: String): WidgetResponse {
        val json = JSONObject(rawJson)
        val schemaVersion = json.optInt("schemaVersion", -1)
        require(schemaVersion == 1) { "Unsupported schema version: $schemaVersion" }
        return WidgetResponse.fromJson(json)
    }
}
