package com.vantora.app.core

enum class GuestArch { ARM64, X64, X86, UNKNOWN }

object Category {
    const val WINDOWS_IMAGE = "WINDOWS_IMAGE"
    const val VIRTUAL_DISK = "VIRTUAL_DISK"
    const val EXE = "EXE"
    const val MSI = "MSI"
    const val APK = "APK"
    const val OTHER = "OTHER"

    val labels = linkedMapOf(
        WINDOWS_IMAGE to "Windows Images",
        VIRTUAL_DISK to "Virtual Disks",
        EXE to "EXE Files",
        MSI to "MSI Files",
        APK to "APK Files",
        OTHER to "Other"
    )
}
