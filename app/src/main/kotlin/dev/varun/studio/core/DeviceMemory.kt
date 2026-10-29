package dev.varun.studio.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.io.File

/**
 * Device capability probes used to decide whether a model can be loaded,
 * whether to warn about thermals, and how much storage is left.
 *
 * All values are read on demand and are cheap; nothing here polls in a loop.
 */
class DeviceMemory(private val context: Context) {

    data class MemoryInfo(
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val perAppHeapBytes: Long,
        val isLowRamDevice: Boolean,
    ) {
        /**
         * RAM this app may realistically use for a model. Native allocations
         * are NOT bounded by the Java heap limit, but the system will kill the
         * process under pressure, so we budget from *available* system RAM and
         * keep a reserve for Android itself.
         */
        val usableForModelBytes: Long
            get() = (availableRamBytes - RESERVE_BYTES).coerceAtLeast(0L)

        companion object {
            /** Keep 700 MB for the system UI, keyboards, and the app's own Java heap. */
            const val RESERVE_BYTES = 700L * 1024 * 1024
        }
    }

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val appDirBytes: Long,
        val modelsBytes: Long,
        val workspacesBytes: Long,
    )

    enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, UNKNOWN }

    data class PowerInfo(
        val thermal: ThermalLevel,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val isPowerSaver: Boolean,
    )

    private val activityManager: ActivityManager?
        get() = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    fun memory(): MemoryInfo {
        val am = activityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val total = if (mi.totalMem > 0) mi.totalMem else 0L
        val avail = if (mi.availMem > 0) mi.availMem else 0L
        val heap = am?.let { it.memoryClass.toLong() * 1024 * 1024 } ?: 256L * 1024 * 1024
        return MemoryInfo(
            totalRamBytes = total,
            availableRamBytes = avail,
            perAppHeapBytes = heap,
            isLowRamDevice = mi.lowMemory,
        )
    }

    fun storage(modelsDir: File? = null, workspacesDir: File? = null): StorageInfo {
        val filesDir = context.filesDir
        val stat = StatFs(filesDir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return StorageInfo(
            totalBytes = total,
            freeBytes = free,
            appDirBytes = directorySize(filesDir),
            modelsBytes = modelsDir?.let { directorySize(it) } ?: 0L,
            workspacesBytes = workspacesDir?.let { directorySize(it) } ?: 0L,
        )
    }

    fun power(): PowerInfo {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = when {
            pm == null -> ThermalLevel.UNKNOWN
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> ThermalLevel.UNKNOWN
            else -> when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
                else -> ThermalLevel.UNKNOWN
            }
        }
        val (percent, charging) = battery()
        return PowerInfo(
            thermal = thermal,
            batteryPercent = percent,
            isCharging = charging,
            isPowerSaver = pm?.isPowerSaveMode ?: false,
        )
    }

    private fun battery(): Pair<Int, Boolean> {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                ?: return@runCatching -1 to false
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            pct to false
        }.getOrElse { -1 to false }
    }

    /**
     * Recommended inference thread count.
     *
     * Big.LITTLE phones get slower when all cores are used, because the little
     * cores drag the barrier down and the SoC throttles. Empirically 2-4 works
     * best; we cap at half the reported cores and never exceed 4.
     */
    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 1
            cores <= 4 -> 2
            cores <= 8 -> 4
            else -> 4
        }
    }

    fun isArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun supportedAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    companion object {
        fun directorySize(dir: File): Long {
            if (!dir.exists()) return 0
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val d = stack.removeFirst()
                val children = d.listFiles() ?: continue
                for (c in children) {
                    if (c.isDirectory) stack.addLast(c) else total += c.length()
                }
            }
            return total
        }
    }
}
