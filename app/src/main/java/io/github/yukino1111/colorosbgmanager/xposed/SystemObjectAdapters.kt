package io.github.yukino1111.colorosbgmanager.xposed

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object SystemObjectAdapters {

    private class ProcessClassAdapter(clazz: Class<*>) {
        val getAppInfoMethod: Method? = findPublicMethod(clazz, "getApplicationInfo")
        val getPkgMethod: Method? = findPublicMethod(clazz, "getPackageName")
            ?: findPublicMethod(clazz, "getPkgName")
        val getUidMethod: Method? = findPublicMethod(clazz, "getUid")
        val getUserIdMethod: Method? = findPublicMethod(clazz, "getUserId")
        val appInfoField: Field? = findDeclaredField(clazz, "info")
            ?: findDeclaredField(clazz, "appInfo")
        val pkgField: Field? = findDeclaredField(clazz, "packageName")
            ?: findDeclaredField(clazz, "mPackageName")
        val procField: Field? = findDeclaredField(clazz, "processName")
            ?: findDeclaredField(clazz, "mProcessName")
        val uidField: Field? = findDeclaredField(clazz, "uid")
            ?: findDeclaredField(clazz, "mUid")
        val userIdField: Field? = findDeclaredField(clazz, "userId")
            ?: findDeclaredField(clazz, "mUserId")
    }

    private val processAdapters = ConcurrentHashMap<Class<*>, ProcessClassAdapter>()
    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val missingFieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Boolean>()
    private val serviceAppFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val serviceAppMethodCache = ConcurrentHashMap<Class<*>, Method>()

    @Volatile
    private var getHansPkgNameMethod: Method? = null

    @Volatile
    private var getHansUidMethod: Method? = null

    @Volatile
    private var processRecordHostingMethod: Method? = null

    @Volatile
    private var hostingGetTypeMethod: Method? = null

    @Volatile
    private var processInfoGetPkgMethod: Method? = null

    @Volatile
    private var processInfoGetUidMethod: Method? = null

    @Volatile
    private var taskGetBasePkgMethod: Method? = null

    fun findDeclaredField(clazz: Class<*>, name: String): Field? {
        val key = clazz to name
        fieldCache[key]?.let { return it }
        if (missingFieldCache.containsKey(key)) return null
        var cur: Class<*>? = clazz
        while (cur != null) {
            val found = runCatching { cur.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (found != null) {
                fieldCache[key] = found
                return found
            }
            cur = cur.superclass
        }
        missingFieldCache[key] = true
        return null
    }

    fun findDeclaredMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var cur: Class<*>? = clazz
        while (cur != null) {
            val found = runCatching { cur.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true } }.getOrNull()
            if (found != null) return found
            cur = cur.superclass
        }
        return null
    }

    private fun findPublicMethod(clazz: Class<*>, name: String): Method? =
        runCatching { clazz.getMethod(name) }.getOrNull()

    private fun processAdapter(obj: Any): ProcessClassAdapter =
        processAdapters.computeIfAbsent(obj.javaClass) { ProcessClassAdapter(it) }

    fun extractProcessPkg(obj: Any?): String? {
        if (obj == null) return null
        val adapter = processAdapter(obj)
        val appInfoPkg = runCatching {
            (adapter.getAppInfoMethod?.invoke(obj) as? ApplicationInfo)?.packageName
                ?: (adapter.appInfoField?.get(obj) as? ApplicationInfo)?.packageName
        }.getOrNull()
        if (!appInfoPkg.isNullOrEmpty()) return appInfoPkg

        val directPkg = runCatching {
            (adapter.getPkgMethod?.invoke(obj) as? String)
                ?: (adapter.pkgField?.get(obj) as? String)
        }.getOrNull()
        if (!directPkg.isNullOrEmpty()) return directPkg

        return runCatching {
            (adapter.procField?.get(obj) as? String)?.substringBefore(':')?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun extractProcessUserId(obj: Any?): Int {
        if (obj == null) return 0
        val adapter = processAdapter(obj)
        val appUid = runCatching {
            (adapter.getAppInfoMethod?.invoke(obj) as? ApplicationInfo)?.uid
                ?: (adapter.appInfoField?.get(obj) as? ApplicationInfo)?.uid
        }.getOrNull()
        if (appUid != null && appUid > 0) return MultiUserPackageHelper.userIdOfUid(appUid)

        val methodUid = runCatching { adapter.getUidMethod?.invoke(obj) as? Int }.getOrNull()
        if (methodUid != null && methodUid >= 0) return MultiUserPackageHelper.userIdOfUid(methodUid)

        val fieldUid = runCatching { adapter.uidField?.getInt(obj) }.getOrNull()
        if (fieldUid != null && fieldUid >= 0) return MultiUserPackageHelper.userIdOfUid(fieldUid)

        val methodUserId = runCatching { adapter.getUserIdMethod?.invoke(obj) as? Int }.getOrNull()
        if (methodUserId != null && methodUserId >= 0) return methodUserId

        val fieldUserId = runCatching { adapter.userIdField?.getInt(obj) }.getOrNull()
        if (fieldUserId != null && fieldUserId >= 0) return fieldUserId

        return 0
    }

    fun extractHostingType(pr: Any?): String? {
        if (pr == null) return null
        val hm = processRecordHostingMethod
            ?: findDeclaredMethod(pr.javaClass, "getHostingRecord")?.also { processRecordHostingMethod = it }
            ?: return null
        return extractHostingRecordType(runCatching { hm.invoke(pr) }.getOrNull())
    }

    fun extractHostingRecordType(hosting: Any?): String? {
        if (hosting == null) return null
        val tm = hostingGetTypeMethod
            ?: findDeclaredMethod(hosting.javaClass, "getType")?.also { hostingGetTypeMethod = it }
            ?: return null
        return runCatching { tm.invoke(hosting) as? String }.getOrNull()
    }

    fun extractHansPkgName(obj: Any?): String? {
        if (obj == null) return null
        val m = getHansPkgNameMethod ?: runCatching {
            obj.javaClass.getMethod("getPkgName").also { getHansPkgNameMethod = it }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(obj) as? String }.getOrNull()
    }

    fun extractHansUserId(obj: Any?): Int {
        if (obj == null) return 0
        val m = getHansUidMethod ?: runCatching {
            obj.javaClass.getMethod("getUid").also { getHansUidMethod = it }
        }.getOrNull()
        val uid = runCatching { m?.invoke(obj) as? Int }.getOrNull()
            ?: MultiUserPackageHelper.extractFieldInt(obj, "mUid")
            ?: return 0
        return MultiUserPackageHelper.userIdOfUid(uid)
    }

    fun extractProcessInfoPkg(obj: Any?): String? {
        if (obj == null) return null
        val m = processInfoGetPkgMethod ?: runCatching {
            obj.javaClass.getMethod("getPkgName").also { processInfoGetPkgMethod = it }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(obj) as? String }.getOrNull()
    }

    fun extractProcessInfoUserId(obj: Any?): Int {
        if (obj == null) return 0
        val m = processInfoGetUidMethod ?: runCatching {
            obj.javaClass.getMethod("getUid").also { processInfoGetUidMethod = it }
        }.getOrNull()
        val uid = runCatching { m?.invoke(obj) as? Int }.getOrNull()
            ?: MultiUserPackageHelper.extractFieldInt(obj, "mUid")
            ?: return 0
        return MultiUserPackageHelper.userIdOfUid(uid)
    }

    fun extractOsenseProcInfoPkg(obj: Any?): String? {
        if (obj == null) return null
        val appPkg = runCatching {
            (findDeclaredField(obj.javaClass, "info")?.get(obj) as? ApplicationInfo)?.packageName
        }.getOrNull()
        if (!appPkg.isNullOrEmpty()) return appPkg

        val groupPkg = extractOsenseGroupPkg(
            runCatching { findDeclaredField(obj.javaClass, "mGroup")?.get(obj) }.getOrNull(),
        )
        if (!groupPkg.isNullOrEmpty()) return groupPkg

        val prPkg = extractProcessPkg(
            runCatching { findDeclaredField(obj.javaClass, "mApp")?.get(obj) }.getOrNull(),
        )
        if (!prPkg.isNullOrEmpty()) return prPkg

        return runCatching {
            (findDeclaredField(obj.javaClass, "mProcessName")?.get(obj) as? String)?.substringBefore(':')
        }.getOrNull()
    }

    fun extractOsenseProcInfoUserId(obj: Any?): Int {
        if (obj == null) return 0
        val infoUid = runCatching {
            (findDeclaredField(obj.javaClass, "info")?.get(obj) as? ApplicationInfo)?.uid
        }.getOrNull()
        if (infoUid != null && infoUid > 0) return MultiUserPackageHelper.userIdOfUid(infoUid)

        val fieldUid = runCatching { findDeclaredField(obj.javaClass, "mUid")?.getInt(obj) }.getOrNull()
        if (fieldUid != null && fieldUid > 0) return MultiUserPackageHelper.userIdOfUid(fieldUid)

        val appObj = runCatching { findDeclaredField(obj.javaClass, "mApp")?.get(obj) }.getOrNull()
        if (appObj != null) return extractProcessUserId(appObj)

        return extractOsenseGroupUserId(
            runCatching { findDeclaredField(obj.javaClass, "mGroup")?.get(obj) }.getOrNull(),
        )
    }

    fun setOsenseSkipReason(obj: Any?, reason: String) {
        if (obj == null) return
        val f = findDeclaredField(obj.javaClass, "mSkipReason") ?: return
        runCatching { f.set(obj, reason) }
    }

    fun extractOsenseGroupPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = findDeclaredField(obj.javaClass, "mPackageName") ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
    }

    fun extractOsenseGroupUserId(obj: Any?): Int {
        if (obj == null) return 0
        val uid = runCatching { findDeclaredField(obj.javaClass, "mUid")?.getInt(obj) }.getOrNull()
        if (uid != null && uid > 0) return MultiUserPackageHelper.userIdOfUid(uid)
        return MultiUserPackageHelper.extractFieldInt(obj, "mUserId") ?: 0
    }

    fun setOsenseGroupSkipReason(obj: Any?, reason: String) {
        if (obj == null) return
        val f = findDeclaredField(obj.javaClass, "mSkipReason") ?: return
        runCatching { f.set(obj, reason) }
    }

    fun extractTaskPkg(task: Any?): String? {
        if (task == null) return null
        val m = taskGetBasePkgMethod ?: runCatching {
            task.javaClass.getMethod("getBasePackageName").also { taskGetBasePkgMethod = it }
        }.getOrNull()
        val basePkg = runCatching { m?.invoke(task) as? String }.getOrNull()
        if (!basePkg.isNullOrEmpty()) return basePkg
        val f = findDeclaredField(task.javaClass, "realActivity") ?: return null
        return runCatching { (f.get(task) as? ComponentName)?.packageName }.getOrNull()
    }

    fun extractTaskUserId(task: Any?): Int {
        if (task == null) return 0
        val f = findDeclaredField(task.javaClass, "mUserId") ?: return 0
        return runCatching { f.getInt(task) }.getOrDefault(0)
    }

    fun extractServiceRecordPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = findDeclaredField(obj.javaClass, "packageName") ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
    }

    fun extractServiceRecordUserId(obj: Any?, fallbackUserId: Int = 0): Int {
        if (obj == null) return fallbackUserId
        val userId = runCatching { findDeclaredField(obj.javaClass, "userId")?.getInt(obj) }.getOrNull()
        if (userId != null) return userId
        val appInfo = runCatching { findDeclaredField(obj.javaClass, "appInfo")?.get(obj) as? ApplicationInfo }.getOrNull()
            ?: return fallbackUserId
        return MultiUserPackageHelper.userIdOfUid(appInfo.uid)
    }

    fun extractServiceApp(sr: Any): Any? {
        val clazz = sr.javaClass
        val getter = serviceAppMethodCache[clazz]
            ?: findPublicMethod(clazz, "getHostProcess")?.also { serviceAppMethodCache[clazz] = it }
        if (getter != null) {
            val host = runCatching { getter.invoke(sr) }
            if (host.isSuccess) return host.getOrNull()
        }
        val field = serviceAppFieldCache[clazz]
            ?: (findDeclaredField(clazz, "mHostProcess") ?: findDeclaredField(clazz, "app"))
                ?.also { serviceAppFieldCache[clazz] = it }
            ?: return null
        return runCatching { field.get(sr) }.getOrNull()
    }

    fun extractBroadcastFilterPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = findDeclaredField(obj.javaClass, "packageName") ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
    }

    fun extractReceiverUserId(ri: ResolveInfo?, bf: Any?, br: Any?): Int {
        val riUid = ri?.activityInfo?.applicationInfo?.uid
        if (riUid != null && riUid > 0) return MultiUserPackageHelper.userIdOfUid(riUid)
        if (bf != null) {
            val uid = runCatching { findDeclaredField(bf.javaClass, "owningUid")?.getInt(bf) }.getOrNull()
            if (uid != null && uid > 0) return MultiUserPackageHelper.userIdOfUid(uid)
        }
        if (br != null) {
            val uid = runCatching { findDeclaredField(br.javaClass, "userId")?.getInt(br) }.getOrNull()
            if (uid != null) return uid
        }
        return 0
    }
}
