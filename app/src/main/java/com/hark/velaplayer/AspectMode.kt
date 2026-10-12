package com.hark.velaplayer

/** Display modes. Each one maps to a real size/transform of the video surface (see ZoomableVideoLayout). */
enum class AspectMode(val label: String, val short: String) {
    FIT("Fit to screen", "Fit"),
    STRETCH("Stretch", "Stretch"),
    ZOOM("Zoom (crop)", "Zoom"),
    FILL("Fill screen", "Fill"),
    ORIGINAL("Original size (100%)", "100%"),
    R16_9("16:9", "16:9"),
    R4_3("4:3", "4:3"),
    R21_9("21:9 Ultrawide", "21:9"),
    AUTO("Auto (source ratio)", "Auto");

    companion object {
        fun from(key: String?): AspectMode = values().firstOrNull { it.name == key } ?: FIT
    }
}
