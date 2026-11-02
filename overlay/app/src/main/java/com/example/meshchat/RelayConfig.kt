package com.example.meshchat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Réglages des relais, gardés entre les lancements dans les préférences « meshchat ». */
object RelayConfig {
    const val MAX_RELAYS_DEFAULT = 32
    const val MAX_RELAYS_MIN = 1
    const val MAX_RELAYS_MAX = 64
    const val KEEP_HOURS_DEFAULT = 24
    const val KEEP_HOURS_MIN = 1
    const val KEEP_HOURS_MAX = 72
    const val FANOUT_DEFAULT = 4
    const val FANOUT_MIN = 1
    const val FANOUT_MAX = 8

    private const val PREFS = "meshchat"
    private const val KEY_RELAYS = "relay_max"
    private const val KEY_KEEP = "relay_keep_hours"
    private const val KEY_FANOUT = "relay_fanout"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Nombre maximal de sauts d'un message. Lu à chaque envoi. */
    fun maxRelays(context: Context): Int =
        prefs(context).getInt(KEY_RELAYS, MAX_RELAYS_DEFAULT).coerceIn(MAX_RELAYS_MIN, MAX_RELAYS_MAX)

    fun setMaxRelays(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_RELAYS, value.coerceIn(MAX_RELAYS_MIN, MAX_RELAYS_MAX)).apply()
    }

    /** Durée de conservation, en heures, d'un message gardé pour un contact absent. */
    fun keepHours(context: Context): Int =
        prefs(context).getInt(KEY_KEEP, KEEP_HOURS_DEFAULT).coerceIn(KEEP_HOURS_MIN, KEEP_HOURS_MAX)

    fun setKeepHours(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_KEEP, value.coerceIn(KEEP_HOURS_MIN, KEEP_HOURS_MAX)).apply()
    }

    /** Durée de conservation en millisecondes. */
    fun keepMs(context: Context): Long = keepHours(context) * 3_600_000L

    /** Nombre de voisins à qui un relais transmet un message (fanout). */
    fun fanout(context: Context): Int =
        prefs(context).getInt(KEY_FANOUT, FANOUT_DEFAULT).coerceIn(FANOUT_MIN, FANOUT_MAX)

    fun setFanout(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_FANOUT, value.coerceIn(FANOUT_MIN, FANOUT_MAX)).apply()
    }

    /**
     * Choisit au plus [count] voisins au hasard. Si le nombre de voisins est déjà inférieur, ils sont tous retenus.
     * Pas encore branché dans BleMeshManager : c'est la prochaine étape.
     */
    fun <T> pickNeighbors(all: List<T>, count: Int): List<T> =
        if (all.size <= count) all else all.shuffled().take(count)
}

/** Curseurs des relais, affichés dans les outils de debug. Les valeurs s'appliquent aux prochains envois. */
@Composable
fun RelaySettings(context: Context, green: Color, grey: Color, inkColor: Color) {
    var relays by remember { mutableStateOf(RelayConfig.maxRelays(context).toFloat()) }
    var hours by remember { mutableStateOf(RelayConfig.keepHours(context).toFloat()) }
    var fanout by remember { mutableStateOf(RelayConfig.fanout(context).toFloat()) }

    Text("RELAIS", color = grey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    Text("Relais maximum par message : ${relays.toInt()}", color = inkColor, fontSize = 14.sp)
    Slider(
        value = relays,
        onValueChange = {
            relays = it
            RelayConfig.setMaxRelays(context, it.toInt())
        },
        valueRange = RelayConfig.MAX_RELAYS_MIN.toFloat()..RelayConfig.MAX_RELAYS_MAX.toFloat(),
        steps = RelayConfig.MAX_RELAYS_MAX - RelayConfig.MAX_RELAYS_MIN - 1,
        colors = SliderDefaults.colors(thumbColor = green, activeTrackColor = green)
    )
    Text(
        "Nombre de téléphones qu'un message peut traverser. Par défaut : 32.",
        color = grey,
        fontSize = 12.sp
    )

    Spacer(Modifier.height(12.dp))
    Text("Voisins par relais (fanout) : ${fanout.toInt()}", color = inkColor, fontSize = 14.sp)
    Slider(
        value = fanout,
        onValueChange = {
            fanout = it
            RelayConfig.setFanout(context, it.toInt())
        },
        valueRange = RelayConfig.FANOUT_MIN.toFloat()..RelayConfig.FANOUT_MAX.toFloat(),
        steps = RelayConfig.FANOUT_MAX - RelayConfig.FANOUT_MIN - 1,
        colors = SliderDefaults.colors(thumbColor = green, activeTrackColor = green)
    )
    Text(
        "Nombre de voisins à qui chaque téléphone relais transmet un message. Par défaut : 4.",
        color = grey,
        fontSize = 12.sp
    )

    Spacer(Modifier.height(12.dp))
    Text("Conservation pour un absent : ${hours.toInt()} h", color = inkColor, fontSize = 14.sp)
    Slider(
        value = hours,
        onValueChange = {
            hours = it
            RelayConfig.setKeepHours(context, it.toInt())
        },
        valueRange = RelayConfig.KEEP_HOURS_MIN.toFloat()..RelayConfig.KEEP_HOURS_MAX.toFloat(),
        steps = RelayConfig.KEEP_HOURS_MAX - RelayConfig.KEEP_HOURS_MIN - 1,
        colors = SliderDefaults.colors(thumbColor = green, activeTrackColor = green)
    )
    Text(
        "Durée pendant laquelle un téléphone relais garde un fichier privé. Ensuite, il est supprimé.",
        color = grey,
        fontSize = 12.sp
    )
}
