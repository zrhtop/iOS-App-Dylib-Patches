#!/usr/bin/env python3
"""Patch HomePod Plus v1.5 NSD into a single-flight resolve queue.

Android NsdManager.FAILURE_ALREADY_ACTIVE (3) frequently occurs if the old
code resolves every onServiceFound callback simultaneously. This patch
deduplicates, serializes, retries transient busy errors with a cooldown, and
retains the independent raw mDNS receiver + manual IP fallback.
"""
import argparse
from pathlib import Path


def replace_exact(text: str, old: str, new: str, name: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{name}: expected exactly 1 occurrence, found {count}")
    return text.replace(old, new, 1)


def patch(text: str) -> str:
    text = replace_exact(text,
        "import android.os.Build",
        """import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock""",
        "NSD handler imports")

    text = replace_exact(text,
        "    private val nsdListeners = ArrayList<NsdManager.DiscoveryListener>()",
        """    private val nsdListeners = ArrayList<NsdManager.DiscoveryListener>()

    // All queue state is accessed on the main Handler. Vendor Android TV NSD
    // implementations often reject simultaneous resolveService calls with
    // FAILURE_ALREADY_ACTIVE (code 3).
    private val nsdHandler = Handler(Looper.getMainLooper())
    private val nsdResolveQueue = ArrayDeque<NsdServiceInfo>()
    private val nsdQueuedKeys = HashSet<String>()
    private val nsdRecentMs = HashMap<String, Long>()
    private val nsdBusyRetries = HashMap<String, Int>()
    private var nsdActiveKey: String? = null
    private var nsdCooldownUntilMs = 0L""",
        "NSD single-flight fields")

    text = replace_exact(text,
        """                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val before = devices.size
                        devices.entries.removeAll { e ->
                            e.value.name == serviceInfo.serviceName && e.value.type == serviceInfo.serviceType
                        }
                        if (devices.size != before) {
                            listener(devices.values.toList())
                        }
                    }""",
        """                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        nsdHandler.post {
                            if (!running) return@post
                            synchronized(this@MdnsDiscoverer) {
                                val before = devices.size
                                devices.entries.removeAll { e ->
                                    e.value.name == serviceInfo.serviceName && e.value.type == serviceInfo.serviceType
                                }
                                if (devices.size != before) listener(devices.values.toList())
                            }
                        }
                    }""",
        "serialize service-loss callbacks")

    text = replace_exact(text,
        """    private fun stopNsd() {
        val nm = nsdManager ?: return""",
        """    private fun stopNsd() {
        nsdHandler.removeCallbacksAndMessages(null)
        nsdResolveQueue.clear()
        nsdQueuedKeys.clear()
        nsdRecentMs.clear()
        nsdBusyRetries.clear()
        nsdActiveKey = null
        nsdCooldownUntilMs = 0L
        val nm = nsdManager ?: return""",
        "reset NSD queue on discovery stop")

    begin = text.index("    private fun handleNsdFound(info: NsdServiceInfo) {")
    end = text.index("    // ---- mDNS wire helpers", begin)
    old = text[begin:end]
    if "nm.resolveService(" not in old or "Nsd 解析失败" not in old:
        raise RuntimeError("Unknown upstream NSD source; refusing to patch.")

    new = """    private fun handleNsdFound(info: NsdServiceInfo) {
        // Discovery callbacks may run on binder threads. Queue mutations and
        // resolveService() calls are dispatched through one handler instead.
        nsdHandler.post { enqueueNsd(info) }
    }

    private fun nsdKey(info: NsdServiceInfo): String =
        info.serviceType + "|" + info.serviceName

    private fun enqueueNsd(info: NsdServiceInfo) {
        if (!running || nsdManager == null) return
        val key = nsdKey(info)
        if (key == nsdActiveKey || key in nsdQueuedKeys) return
        val now = SystemClock.elapsedRealtime()
        val previous = nsdRecentMs[key]
        if (previous != null && now - previous < 12000L) return
        if (nsdResolveQueue.size >= 64) return
        nsdQueuedKeys.add(key)
        nsdResolveQueue.addLast(info)
        resolveNextNsd()
    }

    /** Only one Android NsdManager resolveService request is in flight. */
    private fun resolveNextNsd() {
        if (!running || nsdActiveKey != null) return
        val manager = nsdManager ?: return
        if (nsdResolveQueue.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now < nsdCooldownUntilMs) {
            nsdHandler.postDelayed({ resolveNextNsd() }, nsdCooldownUntilMs - now)
            return
        }
        val info = nsdResolveQueue.removeFirst()
        val key = nsdKey(info)
        nsdQueuedKeys.remove(key)
        nsdActiveKey = key
        nsdRecentMs[key] = now

        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    nsdHandler.post {
                        if (!running || nsdActiveKey != key) return@post
                        nsdActiveKey = null
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            val attempts = (nsdBusyRetries[key] ?: 0) + 1
                            nsdBusyRetries[key] = attempts
                            // Give the vendor NSD stack time to finish the
                            // previous request before processing another.
                            nsdCooldownUntilMs =
                                SystemClock.elapsedRealtime() + 1200L
                            if (attempts <= 3) {
                                nsdRecentMs.remove(key)
                                nsdHandler.postDelayed({
                                    if (running) enqueueNsd(info)
                                }, 1400L)
                            } else {
                                log("NSD 解析持续忙碌，已启用 mDNS 发现作为备用：" +
                                    serviceInfo.serviceName)
                            }
                        } else {
                            log("NSD 解析未成功：" + serviceInfo.serviceName +
                                "（错误码 " + errorCode + "），继续使用 mDNS 发现")
                        }
                        resolveNextNsd()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    nsdHandler.post {
                        if (!running || nsdActiveKey != key) return@post
                        nsdActiveKey = null
                        nsdBusyRetries.remove(key)
                        publishNsdDevice(serviceInfo)
                        resolveNextNsd()
                    }
                }
            })
        } catch (e: Exception) {
            nsdActiveKey = null
            log("NSD 解析异常：" + (e.message ?: "未知错误") + "；尝试其他设备")
            resolveNextNsd()
        }
    }

    @Synchronized
    private fun publishNsdDevice(serviceInfo: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= 34) {
            serviceInfo.hostAddresses
                ?.firstOrNull { it is Inet4Address }
                ?.hostAddress
                ?: serviceInfo.hostAddresses?.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            serviceInfo.host?.hostAddress
        } ?: return
        if (host.isBlank() || serviceInfo.port !in 1..65535) return
        val txt = HashMap<String, String>()
        for ((k, v) in serviceInfo.attributes) {
            txt[k.lowercase()] = String(v, Charsets.UTF_8)
        }
        val type = serviceInfo.serviceType ?: "_airplay._tcp."
        val dev = AirplayDevice(
            name = serviceInfo.serviceName,
            host = host,
            port = serviceInfo.port,
            model = txt["model"] ?: txt["am"],
            sf = txt["sf"]?.toLongOrNull(16),
            txt = txt,
            type = type
        )
        val key = type + "|" + dev.name + "|" + dev.host + ":" + dev.port
        val changed = devices[key] != dev
        devices[key] = dev
        if (changed) listener(devices.values.toList())
    }

"""
    text = text[:begin] + new + text[end:]
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    file = Path(args.project) / "app/src/main/java/com/homepodplus/app/airplay/MdnsDiscoverer.kt"
    if not file.is_file():
        raise SystemExit(f"Missing patched upstream file: {file}")
    content = file.read_text(encoding="utf-8-sig")
    updated = patch(content)
    file.write_text(updated, encoding="utf-8")
    print("NSD serialized resolution and code-3 retry patch applied")


if __name__ == "__main__":
    main()
