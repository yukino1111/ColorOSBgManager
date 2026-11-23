package io.github.yukino1111.colorosbgmanager.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Bundle
import android.os.PersistableBundle
import android.os.UserHandle
import android.os.UserManager
import io.github.yukino1111.colorosbgmanager.model.BridgeAppEntry
import io.github.yukino1111.colorosbgmanager.model.DeviceUserInfo
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object MultiUserPackageHelper {

    private const val FLAG_MULTI_APP_USER = 0x40000000
    private const val FLAG_SUSPENDED_APP = 1 shl 30
    private const val PER_USER_RANGE = 100000

    fun userIdOfUid(uid: Int): Int = if (uid >= 0) uid / PER_USER_RANGE else 0

    fun appIdOfUid(uid: Int): Int = if (uid >= 0) uid % PER_USER_RANGE else uid

    private var cachedIpm: Any? = null
    private var cachedInstalledAppsMethod: Method? = null
    private var cachedQueryIntentMethod: Method? = null
    private var cachedSuspendMethod: Method? = null
    private var cachedIsSuspendedMethod: Method? = null
    private var cachedUserHandleOfMethod: Method? = null
    private var cachedStartAsUserMethod: Method? = null

    fun userHandleOf(userId: Int): UserHandle? = runCatching {
        val m = cachedUserHandleOfMethod ?: UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
            .also { cachedUserHandleOfMethod = it }
        m.invoke(null, userId) as? UserHandle
    }.getOrNull() ?: runCatching {
        UserHandle::class.java.getConstructor(Int::class.javaPrimitiveType).newInstance(userId)
    }.getOrNull()

    private fun getIpm(): Any? {
        cachedIpm?.let { return it }
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("getPackageManager")
                .invoke(null)
                ?.also { cachedIpm = it }
        }.getOrNull()
    }

    fun queryUsers(ctx: Context): List<DeviceUserInfo> {
        val um = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return listOf(DeviceUserInfo(0, "Owner", false))
        val rawList = runCatching {
            val m = um.javaClass.methods.firstOrNull {
                it.name == "getUsers" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: um.javaClass.methods.firstOrNull { it.name == "getAliveUsers" && it.parameterTypes.isEmpty() }
            ?: um.javaClass.methods.firstOrNull { it.name == "getUsers" && it.parameterTypes.isEmpty() }
            when (m?.parameterTypes?.size) {
                1 -> m.invoke(um, true) as? List<*>
                0 -> m.invoke(um) as? List<*>
                else -> null
            }
        }.getOrNull().orEmpty()

        val out = ArrayList<DeviceUserInfo>()
        for (item in rawList) {
            if (item == null) continue
            val id = runCatching { item.javaClass.getField("id").getInt(item) }.getOrNull() ?: continue
            val uh = userHandleOf(id)
            val running = if (uh != null) runCatching { um.isUserRunning(uh) }.getOrDefault(id == 0) else (id == 0)
            if (!running && id != 0) continue
            val name = runCatching { item.javaClass.getField("name").get(item) as? String }.getOrNull().orEmpty()
            val flags = runCatching { item.javaClass.getField("flags").getInt(item) }.getOrDefault(0)
            val userType = runCatching { item.javaClass.getField("userType").get(item) as? String }.getOrNull().orEmpty()
            val isClone = id != 0 && (
                (flags and FLAG_MULTI_APP_USER) != 0 ||
                    userType.contains("clone", ignoreCase = true) ||
                    userType.contains("multi", ignoreCase = true) ||
                    name.equals("MultiApp", ignoreCase = true)
                )
            val displayName = name.ifBlank { if (id == 0) "Owner" else "User $id" }
            out.add(DeviceUserInfo(userId = id, name = displayName, isClone = isClone))
        }
        if (out.none { it.userId == 0 }) {
            out.add(0, DeviceUserInfo(0, "Owner", false))
        }
        return out.distinctBy { it.userId }.sortedBy { it.userId }
    }

    fun buildAppsBundle(ctx: Context): Bundle {
        val token = Binder.clearCallingIdentity()
        return try {
            val users = queryUsers(ctx)
            val pm = ctx.packageManager
            val ipm = getIpm()
            val encodedUsers = ArrayList(users.map { it.encode() })
            val encodedApps = ArrayList<String>()

            for (user in users) {
                val appInfos = getInstalledAppsForUser(ipm, pm, user.userId)
                for (info in appInfos) {
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank { info.packageName }
                    val isSys = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    val suspended = isPackageSuspended(ctx, ipm, info, user.userId)
                    encodedApps.add(
                        BridgeAppEntry(
                            packageName = info.packageName,
                            userId = user.userId,
                            uid = info.uid,
                            label = label,
                            isSystem = isSys,
                            isSuspended = suspended,
                        ).encode(),
                    )
                }
            }
            Bundle().apply {
                putBoolean("ok", true)
                putStringArrayList("users", encodedUsers)
                putStringArrayList("apps", encodedApps)
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun getInstalledAppsForUser(ipm: Any?, pm: PackageManager, userId: Int): List<ApplicationInfo> {
        if (ipm != null) {
            val sliceOrList = runCatching {
                val m = cachedInstalledAppsMethod ?: ipm.javaClass.methods.firstOrNull {
                    it.name == "getInstalledApplications" &&
                        it.parameterTypes.size == 2 &&
                        (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaPrimitiveType) &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }?.also { cachedInstalledAppsMethod = it }
                if (m != null) {
                    val flagArg: Any = if (m.parameterTypes[0] == Long::class.javaPrimitiveType) 0L else 0
                    m.invoke(ipm, flagArg, userId)
                } else {
                    null
                }
            }.getOrNull()
            val list = unwrapParceledList<ApplicationInfo>(sliceOrList)
            if (list.isNotEmpty()) return list
        }
        return if (userId == 0) {
            runCatching { pm.getInstalledApplications(PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    fun isPackageSuspended(ctx: Context, pkg: String, userId: Int): Boolean {
        val ipm = getIpm()
        if (ipm != null) {
            val byIpm = runCatching {
                val m = cachedIsSuspendedMethod ?: ipm.javaClass.methods.firstOrNull {
                    it.name == "isPackageSuspendedForUser" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }?.also { cachedIsSuspendedMethod = it }
                m?.invoke(ipm, pkg, userId) as? Boolean
            }.getOrNull()
            if (byIpm != null) return byIpm
        }
        if (userId == 0) {
            return runCatching { ctx.packageManager.isPackageSuspended(pkg) }.getOrDefault(false)
        }
        return false
    }

    private fun isPackageSuspended(ctx: Context, ipm: Any?, info: ApplicationInfo, userId: Int): Boolean {
        if ((info.flags and FLAG_SUSPENDED_APP) != 0) return true
        return isPackageSuspended(ctx, info.packageName, userId)
    }

    fun setPackageSuspended(pkg: String, userId: Int, suspend: Boolean): Boolean {
        val ipm = getIpm() ?: return false
        val token = Binder.clearCallingIdentity()
        return try {
            val m = cachedSuspendMethod ?: ipm.javaClass.methods
                .filter { it.name == "setPackagesSuspendedAsUser" }
                .maxByOrNull { it.parameterTypes.size }
                ?.also { cachedSuspendMethod = it }
                ?: return false

            val args = buildSuspendArgs(m.parameterTypes, pkg, userId, suspend)
            val failed = m.invoke(ipm, *args) as? Array<*>
            failed == null || failed.isEmpty()
        } catch (_: Throwable) {
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun buildSuspendArgs(paramTypes: Array<Class<*>>, pkg: String, userId: Int, suspend: Boolean): Array<Any?> {
        val intIndices = paramTypes.indices.filter { paramTypes[it] == Int::class.javaPrimitiveType }
        val lastIntIdx = intIndices.lastOrNull() ?: -1
        return Array(paramTypes.size) { idx ->
            val p = paramTypes[idx]
            when {
                p.isArray && p.componentType == String::class.java -> arrayOf(pkg)
                p == Boolean::class.javaPrimitiveType -> suspend
                p == PersistableBundle::class.java -> null
                p.name == "android.content.pm.SuspendDialogInfo" -> null
                p == String::class.java -> "android"
                p == Int::class.javaPrimitiveType -> if (idx == lastIntIdx) userId else 0
                else -> null
            }
        }
    }

    fun launchPackageAsUser(ctx: Context, pkg: String, userId: Int): Boolean {
        val token = Binder.clearCallingIdentity()
        return try {
            if (isPackageSuspended(ctx, pkg, userId)) {
                setPackageSuspended(pkg, userId, false)
            }
            val launchIntent = resolveLaunchIntentForUser(ctx, pkg, userId) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val uh = userHandleOf(userId)
            if (uh != null) {
                val m = cachedStartAsUserMethod ?: ctx.javaClass.methods.firstOrNull {
                    it.name == "startActivityAsUser" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Intent::class.java &&
                        it.parameterTypes[1] == UserHandle::class.java
                }?.also { cachedStartAsUserMethod = it }
                if (m != null) {
                    m.invoke(ctx, launchIntent, uh)
                    return true
                }
            }
            if (userId == 0) {
                ctx.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun resolveLaunchIntentForUser(ctx: Context, pkg: String, userId: Int): Intent? {
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        val ipm = getIpm()
        if (ipm != null) {
            val res = runCatching {
                val m = cachedQueryIntentMethod ?: ipm.javaClass.methods.firstOrNull {
                    it.name == "queryIntentActivities" &&
                        it.parameterTypes.size == 4 &&
                        it.parameterTypes[0] == Intent::class.java &&
                        (it.parameterTypes[2] == Long::class.javaPrimitiveType || it.parameterTypes[2] == Int::class.javaPrimitiveType) &&
                        it.parameterTypes[3] == Int::class.javaPrimitiveType
                }?.also { cachedQueryIntentMethod = it }
                if (m != null) {
                    val flagsArg: Any = if (m.parameterTypes[2] == Long::class.javaPrimitiveType) 0L else 0
                    m.invoke(ipm, query, null, flagsArg, userId)
                } else {
                    null
                }
            }.getOrNull()
            val resolveInfos = unwrapParceledList<ResolveInfo>(res)
            val act = resolveInfos.firstOrNull()?.activityInfo
            if (act != null) {
                return Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(act.packageName, act.name)
                }
            }
        }
        return if (userId == 0) ctx.packageManager.getLaunchIntentForPackage(pkg) else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> unwrapParceledList(obj: Any?): List<T> {
        if (obj == null) return emptyList()
        if (obj is List<*>) return obj as List<T>
        return runCatching {
            val m = obj.javaClass.getMethod("getList")
            (m.invoke(obj) as? List<T>).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun extractFieldInt(obj: Any?, name: String): Int? {
        if (obj == null) return null
        var cur: Class<*>? = obj.javaClass
        while (cur != null) {
            val f: Field? = runCatching { cur.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (f != null) return runCatching { f.getInt(obj) }.getOrNull()
            cur = cur.superclass
        }
        return null
    }
}
