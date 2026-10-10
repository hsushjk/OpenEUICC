package im.angry.openeuicc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import im.angry.openeuicc.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider bridge that exposes OpenEUICC's LPA capabilities to
 * the root shell via `content call`.
 *
 * All responses are Bundles with at least:
 *   ok: Boolean
 *   data: String?   (JSON payload for read ops)
 *   error: String?  (when ok == false)
 */
class EsimBridgeProvider : ContentProvider() {

    companion object {
        private const val TAG = "EsimBridgeProvider"
        private val SLOT_SCAN_RANGE = 0..3
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "EsimBridgeProvider created")
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        Log.i(TAG, "call: method=$method arg=$arg extras=$extras")
        return try {
            dispatch(method, arg, extras)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in method $method", t)
            bundle("ok" to false, "error" to (t.message ?: t.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------

    private fun dispatch(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        "ping" -> bundle("ok" to true, "data" to "pong")
        "getEID" -> withService { opGetEID(it, extras) }
        "listProfiles" -> withService { opListProfiles(it, extras) }
        "listNotifications" -> withService { opListNotifications(it, extras) }
        "listSlots" -> withService { opListSlots(it) }
        "enableProfile" -> withService { opEnableProfile(it, arg, extras, enable = true) }
        "disableProfile" -> withService { opEnableProfile(it, arg, extras, enable = false) }
        "deleteProfile" -> withService { opDeleteProfile(it, arg, extras) }
        "renameProfile" -> withService { opRenameProfile(it, arg, extras) }
        "deleteNotification" -> withService { opDeleteNotification(it, arg, extras) }
        "handleNotification" -> withService { opHandleNotification(it, arg, extras) }
        "downloadProfile" -> withService { opDownloadProfile(it, arg, extras) }
        else -> bundle("ok" to false, "error" to "Unknown method: $method")
    }

    // ---------------------------------------------------------------------
    // Service binding
    // ---------------------------------------------------------------------

    private fun <T> withService(fn: suspend (EuiccChannelManagerService) -> T): T {
        val ctx: Context = context
            ?: throw IllegalStateException("Provider not attached to a context")

        val (binder, unbind) = runBlocking {
            ctx.bindServiceSuspended(
                Intent(ctx, EuiccChannelManagerService::class.java),
                Context.BIND_AUTO_CREATE
            )
        }

        val service = (binder as? EuiccChannelManagerService.LocalBinder)?.service
            ?: run {
                unbind()
                throw RuntimeException("Could not bind to EuiccChannelManagerService")
            }

        try {
            return runBlocking { fn(service) }
        } finally {
            unbind()
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun bundle(vararg pairs: Pair<String, Any?>): Bundle {
        val b = Bundle()
        for ((k, v) in pairs) {
            when (v) {
                null -> { /* skip */ }
                is String -> b.putString(k, v)
                is Int -> b.putInt(k, v)
                is Long -> b.putLong(k, v)
                is Boolean -> b.putBoolean(k, v)
                else -> b.putString(k, v.toString())
            }
        }
        return b
    }

    private fun slotOf(extras: Bundle?): Int = extras?.getInt("slotId", -1) ?: -1

    /**
     * Find a (slotId, portId) pair that has a valid LPA channel.
     * If preferredSlotId >= 0, only that slot is tried.
     * Otherwise, slots 0..3 are scanned and the first valid channel wins.
     */
    private suspend fun resolveSlotAndPort(
        cm: EuiccChannelManager,
        preferredSlotId: Int
    ): Pair<Int, Int>? {
        val slots = if (preferredSlotId >= 0) listOf(preferredSlotId) else SLOT_SCAN_RANGE.toList()
        for (slot in slots) {
            try {
                val port = cm.findFirstAvailablePort(slot)
                if (port < 0) continue

                var valid = false
                try {
                    cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
                        valid = ch.valid
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "probe slot=$slot port=$port failed: ${t.message}")
                    continue
                }
                if (valid) return slot to port
            } catch (t: Throwable) {
                Log.d(TAG, "findFirstAvailablePort($slot) failed: ${t.message}")
            }
        }
        return null
    }

    private suspend fun requireSlotAndPort(
        cm: EuiccChannelManager, preferredSlotId: Int
    ): Pair<Int, Int> = resolveSlotAndPort(cm, preferredSlotId)
        ?: throw RuntimeException("No valid eUICC channel found (tried slots " +
                (if (preferredSlotId >= 0) preferredSlotId else SLOT_SCAN_RANGE) + ")")

    // ---------------------------------------------------------------------
    // Read operations
    // ---------------------------------------------------------------------

    private suspend fun opGetEID(svc: EuiccChannelManagerService, extras: Bundle?): Bundle {
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))
        val eid = cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
            ch.lpa.eID
        }
        return bundle("ok" to true, "data" to eid)
    }

    private suspend fun opListProfiles(svc: EuiccChannelManagerService, extras: Bundle?): Bundle {
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))
        val profiles = cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
            ch.lpa.profiles
        }
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(JSONObject().apply {
                put("iccid", p.iccid)
                put("name", p.name)
                put("nickName", p.nickName)
                put("providerName", p.providerName)
                put("isdpAID", p.isdpAID)
                put("state", p.state.name.lowercase())
                put("profileClass", p.profileClass.name.lowercase())
            })
        }
        return bundle("ok" to true, "data" to arr.toString())
    }

    private suspend fun opListNotifications(
        svc: EuiccChannelManagerService, extras: Bundle?
    ): Bundle {
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))
        val notifications = cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
            ch.lpa.notifications
        }
        val arr = JSONArray()
        for (n in notifications) {
            arr.put(JSONObject().apply {
                put("seqNumber", n.seqNumber)
                put("profileManagementOperation", n.profileManagementOperation.name.lowercase())
                put("notificationAddress", n.notificationAddress)
                put("iccid", n.iccid)
            })
        }
        return bundle("ok" to true, "data" to arr.toString())
    }

    private suspend fun opListSlots(svc: EuiccChannelManagerService): Bundle {
        val cm = svc.euiccChannelManager
        val arr = JSONArray()
        for (slot in SLOT_SCAN_RANGE) {
            try {
                val port = cm.findFirstAvailablePort(slot)
                if (port < 0) continue
                var valid = false
                var eid: String? = null
                try {
                    cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
                        valid = ch.valid
                        if (valid) {
                            try { eid = ch.lpa.eID } catch (_: Throwable) {}
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "listSlots probe slot=$slot port=$port failed: ${t.message}")
                }
                arr.put(JSONObject().apply {
                    put("slotId", slot)
                    put("portId", port)
                    put("valid", valid)
                    if (eid != null) put("eid", eid)
                })
            } catch (t: Throwable) {
                Log.d(TAG, "listSlots slot=$slot failed: ${t.message}")
            }
        }
        return bundle("ok" to true, "data" to arr.toString())
    }

    // ---------------------------------------------------------------------
    // Write operations
    // ---------------------------------------------------------------------

    private suspend fun opEnableProfile(
        svc: EuiccChannelManagerService,
        arg: String?,
        extras: Bundle?,
        enable: Boolean
    ): Bundle {
        val iccid = arg ?: throw IllegalArgumentException("iccid (arg) required")
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))

        svc.waitForForegroundTask()
        val error = svc.launchProfileSwitchTask(
            slot, port, EuiccChannel.SecureElementId.DEFAULT,
            iccid, enable, 30_000L
        ).stateFlow.waitDone()

        return interpretSwitchResult(error, enable)
    }

    private fun interpretSwitchResult(error: Throwable?, enable: Boolean): Bundle {
        if (error == null) return bundle("ok" to true)
        if (error is EuiccChannelManagerService.SwitchingProfilesRefreshException) {
            return bundle(
                "ok" to true,
                "warning" to ("Profile " + (if (enable) "enabled" else "disabled") +
                        " but modem refresh failed; toggle airplane mode to apply")
            )
        }
        throw RuntimeException("Switch failed: ${error.javaClass.simpleName}: ${error.message}")
    }

    private suspend fun opDeleteProfile(
        svc: EuiccChannelManagerService, arg: String?, extras: Bundle?
    ): Bundle {
        val iccid = arg ?: throw IllegalArgumentException("iccid (arg) required")
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))

        svc.waitForForegroundTask()
        val error = svc.launchProfileDeleteTask(
            slot, port, EuiccChannel.SecureElementId.DEFAULT, iccid
        ).stateFlow.waitDone()
        if (error != null) throw RuntimeException("Delete failed: ${error.message}")
        return bundle("ok" to true)
    }

    private suspend fun opRenameProfile(
        svc: EuiccChannelManagerService, arg: String?, extras: Bundle?
    ): Bundle {
        val iccid = arg ?: throw IllegalArgumentException("iccid (arg) required")
        val name = extras?.getString("name")
            ?: throw IllegalArgumentException("name (extra) required")
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))

        svc.waitForForegroundTask()
        val error = svc.launchProfileRenameTask(
            slot, port, EuiccChannel.SecureElementId.DEFAULT, iccid, name
        ).stateFlow.waitDone()
        if (error != null) throw RuntimeException("Rename failed: ${error.message}")
        return bundle("ok" to true)
    }

    private suspend fun opDeleteNotification(
        svc: EuiccChannelManagerService, arg: String?, extras: Bundle?
    ): Bundle {
        val seq = arg?.toLongOrNull()
            ?: throw IllegalArgumentException("seqNumber (arg) required, as decimal")
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))

        val ok = cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
            ch.lpa.deleteNotification(seq)
        }
        if (!ok) throw RuntimeException("deleteNotification returned false")
        return bundle("ok" to true)
    }

    private suspend fun opHandleNotification(
        svc: EuiccChannelManagerService, arg: String?, extras: Bundle?
    ): Bundle {
        val seq = arg?.toLongOrNull()
            ?: throw IllegalArgumentException("seqNumber (arg) required, as decimal")
        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))

        val ok = cm.withEuiccChannel(slot, port, EuiccChannel.SecureElementId.DEFAULT) { ch ->
            ch.lpa.handleNotification(seq)
        }
        if (!ok) throw RuntimeException("handleNotification returned false")
        return bundle("ok" to true)
    }

    private suspend fun opDownloadProfile(
        svc: EuiccChannelManagerService, arg: String?, extras: Bundle?
    ): Bundle {
        val address = arg ?: throw IllegalArgumentException("address (arg) required")
        val matchingId = extras?.getString("matchingId")
        val confirmationCode = extras?.getString("confirmationCode")
        val imei = extras?.getString("imei")

        val cm = svc.euiccChannelManager
        val (slot, port) = requireSlotAndPort(cm, slotOf(extras))

        svc.waitForForegroundTask()
        val handle = svc.launchProfileDownloadTask(
            slot, port, EuiccChannel.SecureElementId.DEFAULT,
            ProfileDownloadInput(address, matchingId, imei, confirmationCode)
        )

        // Auto-confirm: watch for ConfirmingDownload and send `true` on backChannel.
        val watcherScope = CoroutineScope(Dispatchers.IO)
        val watcher = watcherScope.launch {
            try {
                handle.stateFlow.collect { state ->
                    if (state is EuiccChannelManagerService.ForegroundTaskState.InProgress) {
                        val ctx = state.context
                        if (ctx is ProfileDownloadState.ConfirmingDownload) {
                            try { handle.backChannel.send(true) } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) { /* flow completed */ }
        }

        val error = try {
            handle.stateFlow.waitDone()
        } finally {
            watcherScope.cancel()
        }

        if (error != null) {
            throw RuntimeException("Download failed: ${error.message ?: error.javaClass.simpleName}")
        }
        return bundle("ok" to true)
    }
}
