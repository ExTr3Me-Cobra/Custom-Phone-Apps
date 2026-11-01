package com.lightcheck.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The ringing alarm, full screen (also over the lock screen). */
class AlarmActivity : ComponentActivity() {

    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(closer, IntentFilter(ACTION_CLOSE), RECEIVER_NOT_EXPORTED)
        val title = intent.getStringExtra("title") ?: "Living-room lights are off"
        val text = intent.getStringExtra("text").orEmpty()
        val snooze = Prefs(this).snoozeMinutes
        setContent {
            Column(
                Modifier.fillMaxSize().background(Color.Black).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("💡", fontSize = 72.sp)
                Spacer(Modifier.height(20.dp))
                Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(text, color = Color(0xFFBBBBC4), fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(48.dp))
                Button(
                    onClick = { send(AlarmActionReceiver.DISMISS) },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text("Dismiss", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { send(AlarmActionReceiver.SNOOZE) },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Snooze $snooze min (checks again then)", color = Color.White, fontSize = 16.sp) }
            }
        }
    }

    private fun send(action: String) {
        sendBroadcast(Intent(this, AlarmActionReceiver::class.java).setAction(action))
        finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(closer) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CLOSE = "com.lightcheck.app.CLOSE_ALARM"
    }
}

val Amber = Color(0xFFFFC247)
