package com.nasari.gbaia

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Entrega los juegos a otros emuladores como content:// (solo lectura, con permiso por intent). */
class RomProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.nasari.gbaia.roms"
        fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
            .appendPath("archivo").appendPath(f.absolutePath.trimStart('/')).build()
    }

    private fun fileOf(uri: Uri): File? {
        val segs = uri.pathSegments
        if (segs.size < 2 || segs[0] != "archivo") return null
        val f = File("/" + segs.drop(1).joinToString("/")).canonicalFile
        // Solo archivos dentro de la carpeta de juegos
        val root = LauncherPrefs.romRoot(context!!)?.let { File(it).canonicalPath } ?: return null
        return f.takeIf { it.path.startsWith(root) && it.isFile }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.')?.lowercase() ?: "")
            ?: "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Abre un juego con el emulador que toca. */
object Launch {
    fun installedPackage(c: Context, pkgs: List<String>): String? = pkgs.firstOrNull {
        try {
            c.packageManager.getPackageInfo(it, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAvailable(c: Context, l: Launcher): Boolean = when (l) {
        Launcher.Internal, Launcher.Any -> true
        is Launcher.RetroArch -> installedPackage(c, l.packages) != null
        is Launcher.App -> installedPackage(c, l.packages) != null
    }

    /** El emulador elegido para la consola, o el primero instalado. */
    fun launcherFor(c: Context, sys: GameSystem): Launcher {
        val i = LauncherPrefs.launcherIndex(c, sys.id)
        sys.launchers.getOrNull(i)?.let { if (isAvailable(c, it)) return it }
        return sys.launchers.firstOrNull { isAvailable(c, it) } ?: Launcher.Any
    }

    /**
     * Abre el juego. [state]: "auto", "1", "2", "3" o null (solo para el emulador integrado).
     * Devuelve un mensaje de error o null.
     */
    fun start(c: Context, g: Game, state: String? = null): String? {
        val sys = Systems.byId(g.system) ?: return "Consola desconocida"
        val f = g.file
        if (!f.exists()) return "No encuentro el archivo: ${f.name}"
        val l = launcherFor(c, sys)
        val intent = when (l) {
            Launcher.Internal -> Intent(c, EmulatorActivity::class.java).apply {
                data = Uri.fromFile(f)
                putExtra(EmulatorActivity.EXTRA_PATH, g.path)
                if (state != null) putExtra(EmulatorActivity.EXTRA_STATE, state)
            }
            is Launcher.RetroArch -> {
                val pkg = installedPackage(c, l.packages) ?: return "RetroArch no está instalado"
                Intent().apply {
                    component = ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
                    putExtra("ROM", f.absolutePath)
                    putExtra("LIBRETRO", "/data/data/$pkg/cores/${l.core}_libretro_android.so")
                    putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg")
                    putExtra("QUITFOCUS", "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            }
            is Launcher.App -> {
                val pkg = installedPackage(c, l.packages) ?: return "${l.label} no está instalado"
                val uri = RomProvider.uriFor(f)
                Intent(Intent.ACTION_VIEW).apply {
                    if (l.activity != null) component = ComponentName(pkg, l.activity) else setPackage(pkg)
                    if (l.extra != null) putExtra(l.extra, if (l.asUri) uri.toString() else f.absolutePath)
                    else setDataAndType(uri, c.contentResolver.getType(uri))
                    if (l.extra != null) clipData = android.content.ClipData.newRawUri(f.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            }
            Launcher.Any -> {
                val uri = RomProvider.uriFor(f)
                Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, c.contentResolver.getType(uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Abrir ${g.title} con…")
            }
        }
        return try {
            c.startActivity(intent)
            if (l != Launcher.Internal) GameDb.markLaunched(c, g.path)
            null
        } catch (e: ActivityNotFoundException) {
            "No se pudo abrir con ${l.label}"
        } catch (e: SecurityException) {
            "${l.label} no permitió abrir el juego"
        }
    }
}
