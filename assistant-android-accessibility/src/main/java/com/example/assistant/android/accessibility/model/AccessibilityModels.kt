package com.example.assistant.android.accessibility.model

data class CurrentAppInfo(
    val packageName: String?,
    val className: String?
)

data class UiTreeSnapshot(
    val available: Boolean,
    val rootClassName: String?
)

