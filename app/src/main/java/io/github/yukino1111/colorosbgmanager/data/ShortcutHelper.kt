package io.github.yukino1111.colorosbgmanager.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.Icon
import android.os.PersistableBundle
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import io.github.yukino1111.colorosbgmanager.LaunchSuspendedActivity
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import kotlin.math.cos
import kotlin.math.sin

object ShortcutHelper {

    private const val SHORTCUT_PREFIX = "launch_"
    private const val EXTRA_SUSPENDED_STATE = "shortcut_suspended_state"

    fun shortcutId(pkg: String, userId: Int): String = "$SHORTCUT_PREFIX${userId}_$pkg"

    fun pinShortcut(context: Context, app: InstalledAppEntry): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) return false
        val label = formatLabel(app.label, app.userId)
        val shortcut = buildShortcutInfo(
            context = context,
            pkg = app.packageName,
            userId = app.userId,
            label = label,
            isSuspended = app.isSuspended,
            fallbackDrawable = app.icon,
        )
        if (sm.pinnedShortcuts.any { it.id == shortcut.id }) {
            runCatching { sm.updateShortcuts(listOf(shortcut)) }
        }
        return runCatching { sm.requestPinShortcut(shortcut, null) }.getOrDefault(false)
    }

    fun syncPinnedShortcut(
        context: Context,
        pkg: String,
        userId: Int,
        isSuspended: Boolean,
        label: String? = null,
    ) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val id = shortcutId(pkg, userId)
        val existing = sm.pinnedShortcuts.firstOrNull { it.id == id } ?: return
        val desiredState = if (isSuspended) 1 else 0
        if (existing.extras?.getInt(EXTRA_SUSPENDED_STATE, -1) == desiredState) return

        val resolvedLabel = label?.takeIf { it.isNotBlank() }
            ?: existing.shortLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: resolveAppLabel(context, pkg, userId)
        val updated = buildShortcutInfo(
            context = context,
            pkg = pkg,
            userId = userId,
            label = resolvedLabel,
            isSuspended = isSuspended,
            fallbackDrawable = null,
        )
        runCatching { sm.updateShortcuts(listOf(updated)) }
    }

    fun syncAllPinnedShortcuts(context: Context, apps: List<InstalledAppEntry>) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val pinned = sm.pinnedShortcuts
        if (pinned.isEmpty()) return
        val appMap = apps.associateBy { it.key }
        val updates = ArrayList<ShortcutInfo>()
        for (sc in pinned) {
            val parsed = parseShortcutId(sc.id) ?: continue
            val app = appMap[parsed] ?: continue
            val desiredState = if (app.isSuspended) 1 else 0
            if (sc.extras?.getInt(EXTRA_SUSPENDED_STATE, -1) == desiredState) continue
            updates.add(
                buildShortcutInfo(
                    context = context,
                    pkg = app.packageName,
                    userId = app.userId,
                    label = formatLabel(app.label, app.userId),
                    isSuspended = app.isSuspended,
                    fallbackDrawable = app.icon,
                ),
            )
        }
        if (updates.isNotEmpty()) {
            runCatching { sm.updateShortcuts(updates) }
        }
    }

    private fun buildShortcutInfo(
        context: Context,
        pkg: String,
        userId: Int,
        label: String,
        isSuspended: Boolean,
        fallbackDrawable: Drawable?,
    ): ShortcutInfo {
        val token = DeviceSyncClient.getOrCreateShortcutToken(context)
        val launchIntent = Intent(context, LaunchSuspendedActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(LaunchSuspendedActivity.EXTRA_PKG, pkg)
            putExtra(LaunchSuspendedActivity.EXTRA_USER_ID, userId)
            putExtra(LaunchSuspendedActivity.EXTRA_TOKEN, token)
        }
        val icon = buildShortcutIcon(context, pkg, fallbackDrawable, isSuspended)
        val extras = PersistableBundle().apply {
            putInt(EXTRA_SUSPENDED_STATE, if (isSuspended) 1 else 0)
        }
        return ShortcutInfo.Builder(context, shortcutId(pkg, userId))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(launchIntent)
            .setExtras(extras)
            .build()
    }

    private fun buildShortcutIcon(
        context: Context,
        pkg: String,
        fallbackDrawable: Drawable?,
        isSuspended: Boolean,
    ): Icon {
        val pm = context.packageManager
        var drawable = pm.getLaunchIntentForPackage(pkg)?.component?.let { cmp ->
            runCatching { pm.getActivityIcon(cmp) }.getOrNull()
        } ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            ?: fallbackDrawable
            ?: pm.defaultActivityIcon
        while (drawable is DrawableWrapper) {
            drawable = drawable.drawable ?: break
        }
        val am = context.getSystemService(ActivityManager::class.java)
        val launcherSize = (am?.launcherLargeIconSize ?: 192).coerceIn(144, 288)
        val adaptiveSize = (launcherSize * 1.5f).toInt().coerceIn(216, 432)
        if (drawable is AdaptiveIconDrawable) {
            val copy = drawable.constantState?.newDrawable()?.mutate() as? AdaptiveIconDrawable
                ?: drawable
            val bitmap = createBitmap(adaptiveSize, adaptiveSize)
            val canvas = Canvas(bitmap)
            copy.background?.let { bg ->
                bg.setBounds(0, 0, adaptiveSize, adaptiveSize)
                bg.draw(canvas)
            }
            copy.foreground?.let { fg ->
                fg.setBounds(0, 0, adaptiveSize, adaptiveSize)
                fg.draw(canvas)
            }
            val finalBitmap = if (isSuspended) applySuspendedEffect(bitmap) else bitmap
            return Icon.createWithAdaptiveBitmap(finalBitmap)
        }
        val rawBitmap = drawable.toBitmap(launcherSize, launcherSize, Bitmap.Config.ARGB_8888)
        return adaptBitmapToShortcutIcon(rawBitmap, adaptiveSize, isSuspended)
    }

    private fun adaptBitmapToShortcutIcon(
        src: Bitmap,
        adaptiveSize: Int,
        isSuspended: Boolean,
    ): Icon {
        val w = src.width
        val h = src.height
        val cornersTransparent = listOf(
            src.getPixel(0, 0),
            src.getPixel(w - 1, 0),
            src.getPixel(0, h - 1),
            src.getPixel(w - 1, h - 1),
        ).any { Color.alpha(it) < 32 }
        if (!cornersTransparent) {
            val scaled = src.scale(adaptiveSize, adaptiveSize)
            return Icon.createWithAdaptiveBitmap(if (isSuspended) applySuspendedEffect(scaled) else scaled)
        }

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(src.getPixel(x, y)) > 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return Icon.createWithBitmap(if (isSuspended) applySuspendedEffect(src) else src)
        }

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = (maxX - minX) / 2f
        val ry = (maxY - minY) / 2f
        val rimColors = ArrayList<Int>(32)
        for (i in 0 until 32) {
            val angle = (i * Math.PI * 2.0) / 32.0
            val c = cos(angle)
            val s = sin(angle)
            for (step in 95 downTo 70 step 5) {
                val frac = step / 100f
                val sx = (cx + rx * frac * c).toInt().coerceIn(0, w - 1)
                val sy = (cy + ry * frac * s).toInt().coerceIn(0, h - 1)
                val px = src.getPixel(sx, sy)
                if (Color.alpha(px) > 200) {
                    rimColors.add(px)
                    break
                }
            }
        }
        val bleedColor = if (rimColors.isNotEmpty()) {
            val r = rimColors.map { Color.red(it) }.sorted()[rimColors.size / 2]
            val g = rimColors.map { Color.green(it) }.sorted()[rimColors.size / 2]
            val b = rimColors.map { Color.blue(it) }.sorted()[rimColors.size / 2]
            Color.rgb(r, g, b)
        } else {
            Color.WHITE
        }

        val out = createBitmap(adaptiveSize, adaptiveSize)
        val canvas = Canvas(out)
        canvas.drawColor(bleedColor)
        val targetBox = (adaptiveSize * (2f / 3f)).toInt() + 2
        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1
        val scale = targetBox.toFloat() / maxOf(cropW, cropH)
        val dstW = (cropW * scale).toInt().coerceAtLeast(1)
        val dstH = (cropH * scale).toInt().coerceAtLeast(1)
        val left = (adaptiveSize - dstW) / 2
        val top = (adaptiveSize - dstH) / 2
        canvas.drawBitmap(
            src,
            Rect(minX, minY, maxX + 1, maxY + 1),
            Rect(left, top, left + dstW, top + dstH),
            null,
        )
        return Icon.createWithAdaptiveBitmap(if (isSuspended) applySuspendedEffect(out) else out)
    }

    private fun applySuspendedEffect(src: Bitmap): Bitmap {
        val dimMatrix = ColorMatrix(
            floatArrayOf(
                0.45f, 0f, 0f, 0f, 85f,
                0f, 0.45f, 0f, 0f, 85f,
                0f, 0f, 0.45f, 0f, 85f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        dimMatrix.preConcat(ColorMatrix().apply { setSaturation(0f) })
        val out = createBitmap(src.width, src.height)
        Canvas(out).drawBitmap(
            src,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(dimMatrix) },
        )
        return out
    }

    private fun formatLabel(appLabel: String, userId: Int): String =
        if (userId == 0) appLabel else "$appLabel (u$userId)"

    private fun resolveAppLabel(context: Context, pkg: String, userId: Int): String {
        val pm = context.packageManager
        val baseLabel = runCatching {
            val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
        return formatLabel(baseLabel, userId)
    }

    private fun parseShortcutId(id: String): AppUserKey? {
        if (!id.startsWith(SHORTCUT_PREFIX)) return null
        val rest = id.removePrefix(SHORTCUT_PREFIX)
        val sep = rest.indexOf('_')
        if (sep <= 0 || sep >= rest.length - 1) return null
        val userId = rest.substring(0, sep).toIntOrNull() ?: return null
        val pkg = rest.substring(sep + 1)
        return if (pkg.isNotEmpty()) AppUserKey(pkg, userId) else null
    }
}
