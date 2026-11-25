package com.vantora.app.device

data class DeviceCaps(
    val abis: List<String>,
    val cpuCores: Int,
    val cpuModel: String,
    val cpuFeatures: Set<String>,
    val totalRamMb: Long,
    val availRamMb: Long,
    val freeStorageMb: Long,
    val androidSdk: Int,
    val androidRelease: String,
    val device: String,
    val glEs: String,
    val glRenderer: String,
    val glVendor: String,
    val vulkanApi: String?,
    val kvmAccessible: Boolean
) {
    val isArm64: Boolean get() = abis.firstOrNull() == "arm64-v8a"
    val has32BitArm: Boolean get() = "armeabi-v7a" in abis
}
