package io.github.yukino1111.colorosbgmanager.xposed

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.SparseArray
import io.github.yukino1111.colorosbgmanager.model.ProcessDetailInfo
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object SystemTelemetryHelper {

    private val prevPidCpu = ConcurrentHashMap<Int, Long>()
    private var prevTotalCpu: Long = 0L

    private val pageSizeKb: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L }.getOrDefault(4L).coerceAtLeast(1L)
    }

    private val clockTicksPerSec: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    }

    private val cpuCount: Int by lazy {
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private var processRecordPidMethod: Method? = null

    fun buildProcessesBundle(ctx: Context, ams: Any?): Bundle {
        val token = Binder.clearCallingIdentity()
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val rawProcs = runCatching { am?.runningAppProcesses }.getOrNull().orEmpty()
            val servicesByPid = HashMap<Int, MutableList<String>>()
            val servicesByApp = HashMap<Pair<String, Int>, MutableList<String>>()
            collectActiveServices(ams, am, servicesByPid, servicesByApp)

            val totalCpuNow = readTotalCpuJiffies()
            val deltaTotalCpu = if (prevTotalCpu > 0L && totalCpuNow > prevTotalCpu) {
                (totalCpuNow - prevTotalCpu).toFloat()
            } else {
                0f
            }
            val nowElapsedSec = SystemClock.elapsedRealtime() / 1000.0
            val activePids = HashSet<Int>(rawProcs.size)
            val encodedProcs = ArrayList<String>(rawProcs.size)
            val attachedServiceKeys = HashSet<String>()

            for (proc in rawProcs) {
                val pid = proc.pid
                if (pid <= 0) continue
                activePids.add(pid)
                val uid = proc.uid
                val userId = MultiUserPackageHelper.userIdOfUid(uid)
                val pkg = proc.pkgList?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: proc.processName.substringBefore(':')
                val oomAdj = readOomAdj(pid)
                val isCached = proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED || oomAdj >= 900
                val stateLabel = formatProcImportance(proc.importance, oomAdj)
                val memKb = readRssKb(pid)
                val statInfo = readPidStat(pid)
                val cpuPct = if (statInfo != null && deltaTotalCpu > 0f) {
                    val prev = prevPidCpu[pid]
                    if (prev != null && statInfo.cpuJiffies >= prev) {
                        val procDelta = (statInfo.cpuJiffies - prev).toFloat()
                        ((procDelta / deltaTotalCpu) * cpuCount * 100f).coerceIn(0f, cpuCount * 100f)
                    } else {
                        0f
                    }
                } else {
                    0f
                }
                if (statInfo != null) {
                    prevPidCpu[pid] = statInfo.cpuJiffies
                }
                val uptimeMs = if (statInfo != null) {
                    val startSec = statInfo.startTicks.toDouble() / clockTicksPerSec.toDouble()
                    ((nowElapsedSec - startSec) * 1000.0).toLong().coerceAtLeast(0L)
                } else {
                    0L
                }

                val svcs = servicesByPid[pid]?.distinct().orEmpty()
                svcs.forEach { attachedServiceKeys.add("$userId|$pkg|$it") }

                encodedProcs.add(
                    ProcessDetailInfo(
                        pid = pid,
                        userId = userId,
                        uid = uid,
                        processName = proc.processName.orEmpty().ifBlank { pkg },
                        packageName = pkg,
                        stateLabel = stateLabel,
                        isCached = isCached,
                        oomAdj = oomAdj,
                        memoryKb = memKb,
                        cpuPercent = cpuPct,
                        uptimeMs = uptimeMs,
                        services = svcs,
                    ).encode(),
                )
            }

            prevTotalCpu = totalCpuNow
            prevPidCpu.keys.retainAll(activePids)

            val extraSvcsEncoded = ArrayList<String>()
            for ((pkgUser, svcList) in servicesByApp) {
                val (pkg, userId) = pkgUser
                val unattached = svcList.distinct().filter { "$userId|$pkg|$it" !in attachedServiceKeys }
                if (unattached.isNotEmpty()) {
                    extraSvcsEncoded.add("$userId\t$pkg\t${unattached.joinToString(",") { it.replace(',', '_').replace('\t', ' ') }}")
                }
            }

            Bundle().apply {
                putBoolean("ok", true)
                putStringArrayList("procs", encodedProcs)
                putStringArrayList("extra_services", extraSvcsEncoded)
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun collectAppServices(
        ams: Any?,
        am: ActivityManager?,
        targetPkg: String,
        targetUserId: Int,
    ): List<String> {
        val byPid = HashMap<Int, MutableList<String>>()
        val byApp = HashMap<Pair<String, Int>, MutableList<String>>()
        collectActiveServices(ams, am, byPid, byApp)
        return byApp[targetPkg to targetUserId]?.distinct().orEmpty()
    }

    private fun collectActiveServices(
        ams: Any?,
        am: ActivityManager?,
        byPid: MutableMap<Int, MutableList<String>>,
        byApp: MutableMap<Pair<String, Int>, MutableList<String>>,
    ) {
        var collectedFromAms = false
        if (ams != null) {
            runCatching {
                val svcField = SystemObjectAdapters.findDeclaredField(ams.javaClass, "mServices")
                val activeServices = svcField?.get(ams) ?: return@runCatching
                val mapField = SystemObjectAdapters.findDeclaredField(activeServices.javaClass, "mServiceMap")
                    ?: SystemObjectAdapters.findDeclaredField(activeServices.javaClass, "mServiceMapByUser")
                val sparse = mapField?.get(activeServices) as? SparseArray<*> ?: return@runCatching
                for (i in 0 until sparse.size()) {
                    val userKey = sparse.keyAt(i)
                    val svcMap = sparse.valueAt(i) ?: continue
                    val instField = SystemObjectAdapters.findDeclaredField(svcMap.javaClass, "mServicesByInstanceName")
                    val instMap = instField?.get(svcMap) as? Map<*, *> ?: continue
                    val records = synchronized(instMap) { instMap.values.toList() }
                    for (sr in records) {
                        if (sr == null) continue
                        val pkg = SystemObjectAdapters.extractServiceRecordPkg(sr) ?: continue
                        val uid = SystemObjectAdapters.extractServiceRecordUserId(sr, userKey)
                        val shortName = extractServiceShortName(sr) ?: continue
                        val appObj = SystemObjectAdapters.extractServiceApp(sr) ?: continue
                        val pid = extractProcessPid(appObj)
                        byApp.getOrPut(pkg to uid) { ArrayList() }.add(shortName)
                        if (pid > 0) {
                            byPid.getOrPut(pid) { ArrayList() }.add(shortName)
                        }
                        collectedFromAms = true
                    }
                }
            }
        }
        if (!collectedFromAms && am != null) {
            @Suppress("DEPRECATION")
            runCatching {
                am.getRunningServices(500)?.forEach { info ->
                    val pkg = info.service.packageName
                    val uid = MultiUserPackageHelper.userIdOfUid(info.uid)
                    val shortName = info.service.shortClassName
                    byApp.getOrPut(pkg to uid) { ArrayList() }.add(shortName)
                    if (info.pid > 0) {
                        byPid.getOrPut(info.pid) { ArrayList() }.add(shortName)
                    }
                }
            }
        }
    }

    private fun extractServiceShortName(sr: Any): String? {
        val f = SystemObjectAdapters.findDeclaredField(sr.javaClass, "shortInstanceName")
        val s = runCatching { f?.get(sr) as? String }.getOrNull()
        if (!s.isNullOrBlank()) return s
        val instField = SystemObjectAdapters.findDeclaredField(sr.javaClass, "instanceName")
        val cn = runCatching { instField?.get(sr) as? android.content.ComponentName }.getOrNull()
        return cn?.shortClassName
    }

    private fun extractProcessPid(pr: Any?): Int {
        if (pr == null) return 0
        val m = processRecordPidMethod ?: runCatching {
            pr.javaClass.getMethod("getPid").also { processRecordPidMethod = it }
        }.getOrNull()
        val byMethod = runCatching { m?.invoke(pr) as? Int }.getOrNull()
        if (byMethod != null) return byMethod
        return MultiUserPackageHelper.extractFieldInt(pr, "mPid") ?: 0
    }

    private data class PidStat(val cpuJiffies: Long, val startTicks: Long)

    private fun readPidStat(pid: Int): PidStat? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val rparen = text.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= text.length) return null
        val rest = text.substring(rparen + 2).trim().split(' ')
        if (rest.size <= 19) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        val start = rest[19].toLongOrNull() ?: return null
        PidStat(utime + stime, start)
    }.getOrNull()

    private fun readTotalCpuJiffies(): Long = runCatching {
        val firstLine = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return 0L
        val parts = firstLine.trim().split(Regex("\\s+"))
        if (parts.size < 5 || parts[0] != "cpu") return 0L
        var sum = 0L
        for (i in 1 until parts.size) {
            sum += parts[i].toLongOrNull() ?: 0L
        }
        sum
    }.getOrDefault(0L)

    private fun readRssKb(pid: Int): Long = runCatching {
        val text = File("/proc/$pid/statm").readText().trim()
        val space = text.indexOf(' ')
        if (space < 0) return 0L
        val afterFirst = text.substring(space + 1).trimStart()
        val nextSpace = afterFirst.indexOf(' ')
        val pagesStr = if (nextSpace < 0) afterFirst else afterFirst.substring(0, nextSpace)
        val pages = pagesStr.toLongOrNull() ?: return 0L
        pages * pageSizeKb
    }.getOrDefault(0L)

    private fun readOomAdj(pid: Int): Int = runCatching {
        File("/proc/$pid/oom_score_adj").readText().trim().toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun formatProcImportance(importance: Int, oomAdj: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "TOP"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FGS"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VIS"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PER"
        importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "SVC"
        oomAdj >= 900 || importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CCH"
        else -> "BG"
    }
}
