package com.ultrax26.recorder.util

import kotlinx.serialization.json.Json

val UxJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
    classDiscriminator = "type"
}
