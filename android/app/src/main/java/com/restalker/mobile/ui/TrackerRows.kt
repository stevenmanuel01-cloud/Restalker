package com.restalker.mobile.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restalker.mobile.Trackers

/** 1.1.8 (owner, 2026-11-24: "trackers on the phone - this should allow user to add/link additional trackers they might already be
 *  using"): Settings, "Trackers on this phone". The user allows Restalker to read notifications once (Android's own switch), then
 *  chooses WHICH apps are their trackers. Only those apps' alerts are read, only for a watched product, and nothing of them is kept. */
@Composable
fun TrackerRows() {
    val ctx = LocalContext.current
    val tick by produceState(0L) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1500) } }
    val allowed = remember(tick) { Trackers.allowed(ctx) }
    val apps = remember(tick) { Trackers.apps() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("trackers")) {
        Text("Do you already get restock alerts from another app - a Discord server, a tracker app, a channel? Restalker can listen to " +
            "them: when one of the apps you choose below alerts for a product you watch, this phone looks at that product at once " +
            "(and buys it if it is in stock at or under your cap).", fontSize = 13.sp, color = Look.Muted, lineHeight = 18.sp)
        Text("Only the apps you choose are read, and only to see whether an alert is about a product you watch. An alert's words are " +
            "never kept or sent anywhere. If you share with the Restalker crowd, only the product and the tracker's name are shared " +
            "(to measure how fast each tracker is).", fontSize = 13.sp, color = Look.Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (!allowed) {
        Row(Modifier.fillMaxWidth().clickable { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }
            .padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 24.dp).testTag("trackers-allow"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow Restalker to read alerts", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Opens Android's \"Notification access\" - switch Restalker on there, then come back and choose your tracker apps", fontSize = 13.sp, color = Look.Muted)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Look.Muted)
        }
    } else {
        if (apps.isEmpty()) Text("Allowed. No other app has shown a notification yet - each app appears here when it does. Then switch on the ones that are your trackers.",
            fontSize = 13.sp, color = Look.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("trackers-none"))
        apps.forEach { (pkg, label, on) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontSize = 15.sp)
                Switch(on, { v -> Trackers.choose(pkg, label, v) }, Modifier.testTag("tracker-$pkg"), colors = Look.sw())
            }
        }
        Text("Alerts of your tracker apps heard: ${Trackers.heardN}, about a product you watch: ${Trackers.matchedN}" + (if (Trackers.last.isNotEmpty()) " (last: ${Trackers.last})" else ""),
            fontSize = 12.sp, color = Look.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("trackers-count"))
    }
}
