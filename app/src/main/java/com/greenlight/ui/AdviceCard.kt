package com.greenlight.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.greenlight.model.GlosaAction
import com.greenlight.model.GlosaAdvice
import com.greenlight.model.TimingSource
import kotlin.math.roundToInt

/** The big readable number. Everything else on this screen is secondary to it. */
@Composable
fun AdviceCard(advice: GlosaAdvice, currentMps: Double, modifier: Modifier = Modifier) {
    val target = when (advice.action) {
        GlosaAction.NO_ADVICE -> Neutral
        GlosaAction.STOP_EXPECTED -> StopRed
        else -> if (advice.confidence >= 0.75) GoGreen else WaitAmber
    }
    val colour by animateColorAsState(target, label = "adviceColour")
    val confidence by animateFloatAsState(advice.confidence.toFloat(), label = "confidence")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colour),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = headline(advice),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = bigNumber(advice),
                    color = Color.White,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = " " + unit(advice),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Text(
                text = subline(advice, currentMps),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp,
            )

            advice.bandMps?.let { band ->
                Text(
                    text = "window ${(band.min * 3.6).roundToInt()}–${(band.max * 3.6).roundToInt()} km/h",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (advice.action != GlosaAction.NO_ADVICE) {
                Box(modifier = Modifier.padding(top = 14.dp).fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { confidence },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        sourceLabel(advice.source),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                    Text(
                        "${(advice.confidence * 100).roundToInt()}% confidence",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun headline(a: GlosaAdvice) = when (a.action) {
    GlosaAction.HOLD -> "HOLD"
    GlosaAction.SPEED_UP -> "PICK UP TO"
    GlosaAction.EASE_OFF -> "EASE OFF TO"
    GlosaAction.STOP_EXPECTED -> "RED ON ARRIVAL"
    GlosaAction.NO_ADVICE -> "NO ADVICE"
}

private fun bigNumber(a: GlosaAdvice) = when (a.action) {
    GlosaAction.STOP_EXPECTED -> a.timeToGreenSec?.roundToInt()?.toString() ?: "—"
    GlosaAction.NO_ADVICE -> "—"
    else -> ((a.targetMps ?: 0.0) * 3.6).roundToInt().toString()
}

private fun unit(a: GlosaAdvice) = when (a.action) {
    GlosaAction.STOP_EXPECTED -> "s to green"
    else -> "km/h"
}

private fun subline(a: GlosaAdvice, currentMps: Double): String {
    if (a.action == GlosaAction.NO_ADVICE) return a.note ?: "waiting"
    val parts = mutableListOf("${a.distanceMeters.roundToInt()} m ahead")
    parts += "now ${(currentMps * 3.6).roundToInt()} km/h"
    a.etaSeconds?.let { parts += "eta ${it.roundToInt()} s" }
    if (a.signalsCleared > 1) parts += "clears ${a.signalsCleared} lights"
    return parts.joinToString(" · ")
}

private fun sourceLabel(s: TimingSource) = when (s) {
    TimingSource.LIVE_SPAT -> "live signal feed"
    TimingSource.MANUAL -> "manual timing"
    TimingSource.LEARNED -> "learned from your trips"
    TimingSource.NONE -> "no timing source"
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
