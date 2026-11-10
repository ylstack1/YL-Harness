package com.androidharness.app.data.env

import android.os.ParcelFileDescriptor
import com.androidharness.app.data.env.adb.AdbShell
import moe.shizuku.server.IRemoteProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** The private stdin/stdout runner works over either Shizuku or a direct ADB shell. */
internal interface CompatProcess {
    val inputStream: InputStream
    val outputStream: OutputStream
    val errorStream: InputStream?
    val isAlive: Boolean
    fun waitForTimeout(value: Long, unit: String)
    fun destroy()
}

internal class ShizukuCompatProcess(private val process: IRemoteProcess) : CompatProcess {
    override val inputStream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
    override val outputStream: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream)
    override val errorStream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
    override val isAlive: Boolean get() = process.asBinder().pingBinder()

    override fun waitForTimeout(value: Long, unit: String) {
        runCatching { process.waitForTimeout(value, unit) }
    }

    override fun destroy() {
        runCatching { process.destroy() }
    }
}

internal class AdbCompatProcess(private val shell: AdbShell) : CompatProcess {
    override val inputStream: InputStream get() = shell.inputStream
    override val outputStream: OutputStream get() = shell.outputStream
    override val errorStream: InputStream? = null
    override val isAlive: Boolean get() = shell.isAlive

    override fun waitForTimeout(value: Long, unit: String) {
        runCatching { shell.awaitClosed(value, TimeUnit.valueOf(unit)) }
    }

    override fun destroy() = shell.close()
}
