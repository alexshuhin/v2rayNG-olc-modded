package com.v2ray.ang.core

import android.content.Context
import android.util.Log
import com.v2ray.ang.dto.ProfileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the olcrtc CLI binary as a child process, instead of linking gomobile-AAR.
 *
 * Why a separate process?  Both libv2ray and olcrtc are gomobile-bound and ship a
 * `libgojni.so` with the same name and conflicting JNI exports — they can't coexist
 * inside one process.  The CLI binary is statically-linked, free of CGO, and just
 * needs to be exec()'d; its local SOCKS5 listener is what tun2socks (or the user's
 * proxy-only setup) talks to, identical to the gomobile path.
 *
 * The binary is shipped under `app/src/main/jniLibs/<abi>/libolcrtc.so` so Android
 * extracts it into `nativeLibraryDir` with executable bits intact (regular asset
 * files lose +x on extraction).
 */
object OlcrtcEngineController {

    private val processRef = AtomicReference<Process?>(null)
    private var logJob: Job? = null
    private var protector: ((Int) -> Boolean)? = null
    private const val TAG = "OlcrtcEngine"

    /**
     * Stored for parity with the gomobile API. Currently unused at the JVM layer:
     * sockets opened by the child process can't be hooked from here.  In VPN mode
     * the child will be excluded via VpnService.Builder.addDisallowedApplication
     * (TODO) so its WebRTC traffic skips the tun.
     */
    fun setProtector(protector: ((Int) -> Boolean)?) {
        this.protector = protector
    }

    @Throws(Exception::class)
    fun start(context: Context, profile: ProfileItem, socksPort: Int) {
        if (isRunning()) {
            Log.e(TAG, "OlcrtcEngine: already running, stopping previous instance")
            stop()
        }

        val carrier = profile.network.orEmpty().ifEmpty { "wbstream" }
        val transport = profile.headerType.orEmpty().ifEmpty { "datachannel" }
        val roomId = profile.host.orEmpty()
        val clientId = profile.username.orEmpty().ifEmpty { "default" }
        val keyHex = profile.password.orEmpty()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libolcrtc.so")
        if (!binary.exists()) {
            error("olcrtc binary not found at ${binary.path}")
        }

        val args = mutableListOf(
            binary.absolutePath,
            "-mode", "cnc",
            "-carrier", carrier,
            "-transport", transport,
            "-id", roomId,
            "-client-id", clientId,
            "-key", keyHex,
            "-link", "direct",
            "-data", "data",
            "-dns", "1.1.1.1:53",
            "-socks-host", "127.0.0.1",
            "-socks-port", socksPort.toString(),
            "--debug",
        )
        // vp8channel-specific tuning (matches OlcrtcFmt convention)
        if (transport == "vp8channel") {
            args += listOf("-vp8-fps", "60", "-vp8-batch", "64")
        }

        Log.e(TAG, "OlcrtcEngine: exec ${args.joinToString(" ")}")

        val builder = ProcessBuilder(args).apply {
            redirectErrorStream(true)
            // Use the app's data dir as cwd; data files (if any) land in a writable place.
            directory(context.filesDir)
            environment().remove("LD_PRELOAD")
        }

        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw RuntimeException("Failed to launch olcrtc binary: ${e.message}", e)
        }
        processRef.set(process)

        // Pump stdout/stderr → logcat in the background, otherwise the pipe fills and the
        // child blocks on writes.
        logJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        Log.e(TAG, "olcrtc: $line")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OlcrtcEngine: log pump stopped: ${e.message}")
            }
            // When the loop exits the child has terminated.  Surface the exit code.
            try {
                val exit = process.exitValue()
                if (exit != 0) {
                    Log.e(TAG, "OlcrtcEngine: child exited with code=$exit")
                } else {
                    Log.e(TAG, "OlcrtcEngine: child exited cleanly")
                }
            } catch (_: IllegalThreadStateException) {
                // Still running — pipe was closed prematurely; leave the process alone.
            }
            processRef.compareAndSet(process, null)
        }
    }

    /**
     * Block until the SOCKS5 listener is reachable, or [timeoutMillis] elapses.
     * Probes 127.0.0.1:[socksPort] every 100ms.
     */
    fun waitReady(socksPort: Int, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val p = processRef.get() ?: error("olcrtc engine is not running")
            if (!p.isAlive) {
                error("olcrtc child process exited before becoming ready (exit=${runCatching { p.exitValue() }.getOrNull()})")
            }
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 200)
                    return
                }
            } catch (_: Exception) {
                // not yet listening — back off
            }
            Thread.sleep(100)
        }
        throw RuntimeException("olcrtc SOCKS5 :$socksPort not ready in $timeoutMillis ms")
    }

    fun stop() {
        val process = processRef.getAndSet(null) ?: return
        try {
            process.destroy()
            // Give it a moment to flush, then force-kill if still around.
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.e(TAG, "OlcrtcEngine: stop error: ${e.message}")
        }
        logJob?.cancel()
        logJob = null
        Log.e(TAG, "OlcrtcEngine: stopped")
    }

    fun isRunning(): Boolean = processRef.get()?.isAlive == true
}
