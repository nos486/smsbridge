package com.nos486.smsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * Figures out which SIM slot (0-based) an SMS arrived on. There is no single standard extra
 * across vendors, so we try the AOSP ones first and then the common OEM variants.
 */
object SimSlotResolver {
    private val SLOT_KEYS = listOf(
        "android.telephony.extra.SLOT_INDEX", "slot", "slot_id", "slotId", "simSlot", "sim_slot",
        "slotIdx", "simId", "sim_id", "phone", "phone_id", "slot_index",
    )
    private val SUB_KEYS = listOf(
        "android.telephony.extra.SUBSCRIPTION_INDEX", "subscription", "subscription_id", "subId", "sub_id",
    )

    fun fromIntent(context: Context, intent: Intent): Int? {
        val extras = intent.extras ?: return null
        val subId = SUB_KEYS.firstNotNullOfOrNull { key -> extras.intOrNull(key)?.takeIf { it >= 0 } }
        if (subId != null) fromSubscriptionId(context, subId)?.let { return it }
        return SLOT_KEYS.firstNotNullOfOrNull { key -> extras.intOrNull(key)?.takeIf { it in 0..7 } }
    }

    @SuppressLint("MissingPermission")
    fun fromSubscriptionId(context: Context, subId: Int): Int? {
        if (subId < 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val slot = SubscriptionManager.getSlotIndex(subId)
            if (slot >= 0) return slot
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { it >= 0 }
        }.getOrNull()
    }

    private fun android.os.Bundle.intOrNull(key: String): Int? {
        if (!containsKey(key)) return null
        @Suppress("DEPRECATION")
        return when (val v = get(key)) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }
}
