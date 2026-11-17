package com.example.meshchat

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.MeshService

/** Adresse où sont publiées les versions de Samchat (GitHub Releases). */
private const val RELEASES_URL = "https://github.com/wwrcgv-web/MeshChat/releases"

private val LANGUAGES = listOf(
    "fr" to "Français", "en" to "English", "es" to "Español", "pt" to "Português",
    "ar" to "العربية", "zh" to "中文",
    "bm" to "Bambara", "dyu" to "Dioula", "wo" to "Wolof", "ha" to "Hausa",
    "mos" to "Mooré", "ff" to "Pulaar", "snk" to "Soninké", "song" to "Songhaï",
    "mnk" to "Mandinka", "fon" to "Fon", "ee" to "Ewe", "yo" to "Yoruba",
    "ig" to "Igbo", "ak" to "Akan", "srr" to "Sérère", "tmh" to "Tamasheq"
)

private val TEXTS: Map<String, List<String>> = mapOf(
    "settings" to listOf("Paramètres", "Settings", "Ajustes", "Definições", "الإعدادات", "设置"),
    "profile" to listOf("Mon profil", "My profile", "Mi perfil", "Meu perfil", "ملفي الشخصي", "我的资料"),
    "profile_hint" to listOf("Photo, nom, identifiant et statut.", "Photo, name, ID and status.", "Foto, nombre, ID y estado.", "Foto, nome, ID e estado.", "الصورة والاسم والمعرّف والحالة.", "头像、名称、ID 和状态。"),
    "appearance" to listOf("Apparence", "Appearance", "Apariencia", "Aparência", "المظهر", "外观"),
    "appearance_hint" to listOf("Thème clair, sombre ou automatique.", "Light, dark or automatic theme.", "Tema claro, oscuro o automático.", "Tema claro, escuro ou automático.", "سمة فاتحة أو داكنة أو تلقائية.", "浅色、深色或自动主题。"),
    "connection" to listOf("Connexion", "Connection", "Conexión", "Ligação", "الاتصال", "连接"),
    "connection_hint" to listOf("Service, Bluetooth, Wi-Fi et Push-to-Talk.", "Service, Bluetooth, Wi-Fi and Push-to-Talk.", "Servicio, Bluetooth, Wi-Fi y Push-to-Talk.", "Serviço, Bluetooth, Wi-Fi e Push-to-Talk.", "الخدمة والبلوتوث والواي فاي والتحدث المباشر.", "服务、蓝牙、Wi-Fi 和按键通话。"),
    "relay" to listOf("Réseau et relais", "Network and relay", "Red y relevo", "Rede e retransmissão", "الشبكة والترحيل", "网络与中继"),
    "relay_hint" to listOf("Relais maximum, voisins, conservation.", "Max relays, neighbours, retention.", "Máximo de relevos, vecinos, conservación.", "Máximo de retransmissões, vizinhos, retenção.", "أقصى عدد من الترحيلات والجيران والاحتفاظ.", "最多中继、邻居、保留时间。"),
    "contacts" to listOf("Contacts", "Contacts", "Contactos", "Contactos", "جهات الاتصال", "联系人"),
    "contacts_hint" to listOf("Mon QR code, scanner, liste des contacts.", "My QR code, scan, contact list.", "Mi código QR, escanear, lista de contactos.", "Meu QR code, ler, lista de contactos.", "رمز QR الخاص بي، المسح، قائمة الجهات.", "我的二维码、扫描、联系人列表。"),
    "share" to listOf("Partage", "Share", "Compartir", "Partilhar", "مشاركة", "分享"),
    "share_hint" to listOf("Envoyer l'application à un proche.", "Send the app to someone.", "Enviar la app a alguien.", "Enviar a app a alguém.", "أرسل التطبيق إلى شخص قريب.", "把应用发送给亲友。"),
    "privacy" to listOf("Confidentialité et aide", "Privacy and help", "Privacidad y ayuda", "Privacidade e ajuda", "الخصوصية والمساعدة", "隐私与帮助"),
    "privacy_hint" to listOf("Confidentialité, mises à jour, langue et aide.", "Privacy, updates, language and help.", "Privacidad, actualizaciones, idioma y ayuda.", "Privacidade, atualizações, idioma e ajuda.", "الخصوصية والتحديثات واللغة والمساعدة.", "隐私、更新、语言与帮助。"),
    "diag" to listOf("Diagnostic avancé", "Advanced diagnostics", "Diagnóstico avanzado", "Diagnóstico avançado", "تشخيص متقدم", "高级诊断"),
    "diag_hint" to listOf("Outils de debug et informations techniques.", "Debug tools and technical info.", "Herramientas de depuración e información técnica.", "Ferramentas de depuração e informação técnica.", "أدوات التصحيح والمعلومات التقنية.", "调试工具和技术信息。"),
    "name" to listOf("NOM D'UTILISATEUR", "USERNAME", "NOMBRE DE USUARIO", "NOME DE UTILIZADOR", "اسم المستخدم", "用户名"),
    "save_name" to listOf("Enregistrer le nom", "Save name", "Guardar nombre", "Guardar nome", "حفظ الاسم", "保存名称"),
    "photo" to listOf("Changer la photo", "Change photo", "Cambiar foto", "Mudar foto", "تغيير الصورة", "更换头像"),
    "status" to listOf("STATUT (60 caractères)", "STATUS (60 characters)", "ESTADO (60 caracteres)", "ESTADO (60 caracteres)", "الحالة (60 حرفًا)", "状态（60 个字符）"),
    "save_status" to listOf("Enregistrer le statut", "Save status", "Guardar estado", "Guardar estado", "حفظ الحالة", "保存状态"),
    "id" to listOf("IDENTIFIANT", "ID", "ID", "ID", "المعرّف", "ID"),
    "lang" to listOf("LANGUE", "LANGUAGE", "IDIOMA", "IDIOMA", "اللغة", "语言"),
    "lang_note" to listOf(
        "Les autres langues seront ajoutées une à une après relecture.",
        "Other languages will be added one by one after review.",
        "Otros idiomas se añadirán uno a uno tras revisión.",
        "Outros idiomas serão adicionados um a um após revisão.",
        "ستُضاف اللغات الأخرى واحدة تلو الأخرى بعد المراجعة.",
        "其他语言将在审校后逐一添加。"
    ),
    "help_body" to listOf(
        "Samchat fonctionne sans internet. Gardez le Bluetooth allumé et restez à quelques mètres de vos contacts. Pour ajouter un contact, scannez son QR code. Appuyez longtemps sur un message pour le supprimer.",
        "Samchat works without internet. Keep Bluetooth on and stay a few metres from your contacts. To add a contact, scan their QR code. Long-press a message to delete it.",
        "Samchat funciona sin internet. Mantén el Bluetooth activado y quédate a pocos metros de tus contactos. Para añadir un contacto, escanea su código QR. Mantén pulsado un mensaje para borrarlo.",
        "O Samchat funciona sem internet. Mantenha o Bluetooth ligado e fique a poucos metros dos seus contactos. Para adicionar um contacto, leia o QR code dele. Mantenha premida uma mensagem para a apagar.",
        "يعمل Samchat بدون إنترنت. أبقِ البلوتوث مفعّلاً وابقَ على بعد بضعة أمتار من جهات اتصالك. لإضافة جهة اتصال، امسح رمز QR الخاص بها. اضغط مطوّلاً على رسالة لحذفها.",
        "Samchat 无需互联网即可使用。请保持蓝牙开启，并与联系人保持几米的距离。添加联系人请扫描其二维码。长按消息可将其删除。"
    ),
    "help_head" to listOf("AIDE", "HELP", "AYUDA", "AJUDA", "المساعدة", "帮助"),
    "update" to listOf("MISE À JOUR", "UPDATE", "ACTUALIZACIÓN", "ATUALIZAÇÃO", "التحديث", "更新"),
    "update_btn" to listOf("Vérifier les mises à jour", "Check for updates", "Buscar actualizaciones", "Verificar atualizações", "التحقق من التحديثات", "检查更新"),
    "update_note" to listOf("Ouvre la page de téléchargement de la dernière version.", "Opens the download page for the latest version.", "Abre la página de descarga de la última versión.", "Abre a página de transferência da versão mais recente.", "يفتح صفحة تنزيل أحدث إصدار.", "打开最新版本的下载页面。"),
    "scan" to listOf("Scanner un QR code", "Scan a QR code", "Escanear un código QR", "Ler um QR code", "مسح رمز QR", "扫描二维码"),
    "myqr" to listOf("Mon QR code", "My QR code", "Mi código QR", "Meu QR code", "رمز QR الخاص بي", "我的二维码"),
    "share_btn" to listOf("Partager Samchat", "Share Samchat", "Compartir Samchat", "Partilhar Samchat", "مشاركة Samchat", "分享 Samchat"),
    "debug_btn" to listOf("Ouvrir les outils de debug", "Open debug tools", "Abrir herramientas de depuración", "Abrir ferramentas de depuração", "فتح أدوات التصحيح", "打开调试工具"),
    "inst" to listOf("Installée depuis", "Installed from", "Instalada desde", "Instalada a partir de", "مثبّت من", "安装来源"),
    "peers" to listOf("Pairs à portée", "Peers in range", "Pares al alcance", "Pares ao alcance", "الأقران في النطاق", "范围内的设备"),
    "bg" to listOf("Service en arrière-plan", "Background service", "Servicio en segundo plano", "Serviço em segundo plano", "الخدمة في الخلفية", "后台服务"),
    "bg_note" to listOf("S'arrête à la fermeture de l'app si désactivé.", "Stops when the app closes if turned off.", "Se detiene al cerrar la app si está desactivado.", "Para ao fechar a app se estiver desativado.", "تتوقف عند إغلاق التطبيق إذا تم تعطيلها.", "关闭后，应用退出时停止。"),
    "ble" to listOf("Bluetooth", "Bluetooth", "Bluetooth", "Bluetooth", "البلوتوث", "蓝牙"),
    "ble_note" to listOf("Messages par Bluetooth.", "Messages over Bluetooth.", "Mensajes por Bluetooth.", "Mensagens por Bluetooth.", "الرسائل عبر البلوتوث.", "通过蓝牙发送消息。"),
    "scan_send" to listOf("Scan pendant les envois", "Scan during sends", "Escanear durante envíos", "Procurar durante envios", "المسح أثناء الإرسال", "发送时扫描"),
    "scan_send_note" to listOf("À couper si les envois échouent.", "Turn off if sends fail.", "Desactivar si los envíos fallan.", "Desligar se os envios falharem.", "أوقفه إذا فشلت الإرسالات.", "发送失败时请关闭。"),
    "live" to listOf("Parole en direct", "Live talk", "Habla en directo", "Fala em direto", "التحدث المباشر", "实时语音"),
    "live_note" to listOf("Maintenir le micro pour parler. Un appui bref envoie un vocal.", "Hold the mic to talk. A short tap sends a voice note.", "Mantén el micrófono para hablar. Un toque breve envía una nota de voz.", "Mantenha o microfone para falar. Um toque breve envia uma nota de voz.", "اضغط مطولاً على الميكروفون للتحدث. النقرة القصيرة ترسل رسالة صوتية.", "按住麦克风说话。短按发送语音消息。")
)

private fun langIndex(lang: String): Int = when (lang) {
    "en" -> 1
    "es" -> 2
    "pt" -> 3
    "ar" -> 4
    "zh" -> 5
    else -> 0
}

private fun tr(lang: String, key: String): String {
    val list = TEXTS[key] ?: return key
    return list[langIndex(lang)]
}

@Composable
private fun HubIcon(kind: Int) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val c = SamchatColors.Green
        val st = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        when (kind) {
            0 -> {
                drawCircle(c, w * 0.18f, p(0.5f, 0.32f), style = st)
                drawArc(c, 0f, 180f, false, p(0.2f, 0.55f), Size(w * 0.6f, h * 0.6f), style = st)
            }
            1 -> {
                drawCircle(c, w * 0.4f, p(0.5f, 0.5f), style = st)
                drawArc(c, -90f, 180f, true, p(0.1f, 0.1f), Size(w * 0.8f, h * 0.8f))
            }
            2 -> {
                for (r in listOf(0.2f, 0.35f, 0.5f)) {
                    drawArc(c, 225f, 90f, false, Offset(w * (0.5f - r), h * (0.85f - r)), Size(w * 2 * r, w * 2 * r), style = st)
                }
                drawCircle(c, w * 0.06f, p(0.5f, 0.85f))
            }
            3 -> {
                drawLine(c, p(0.5f, 0.15f), p(0.15f, 0.8f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.15f, 0.8f), p(0.85f, 0.8f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.85f, 0.8f), p(0.5f, 0.15f), strokeWidth = st.width, cap = StrokeCap.Round)
                listOf(p(0.5f, 0.15f), p(0.15f, 0.8f), p(0.85f, 0.8f)).forEach { drawCircle(c, w * 0.1f, it) }
            }
            4 -> {
                drawCircle(c, w * 0.15f, p(0.38f, 0.3f), style = st)
                drawArc(c, 0f, 180f, false, p(0.1f, 0.55f), Size(w * 0.56f, h * 0.56f), style = st)
                drawCircle(c, w * 0.12f, p(0.72f, 0.36f), style = st)
                drawArc(c, 0f, 140f, false, p(0.56f, 0.6f), Size(w * 0.38f, h * 0.38f), style = st)
            }
            5 -> {
                drawLine(c, p(0.5f, 0.68f), p(0.5f, 0.14f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.5f, 0.14f), p(0.3f, 0.34f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.5f, 0.14f), p(0.7f, 0.34f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.2f, 0.5f), p(0.2f, 0.88f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.2f, 0.88f), p(0.8f, 0.88f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.8f, 0.88f), p(0.8f, 0.5f), strokeWidth = st.width, cap = StrokeCap.Round)
            }
            6 -> {
                drawCircle(c, w * 0.42f, p(0.5f, 0.5f), style = st)
                drawLine(c, p(0.08f, 0.5f), p(0.92f, 0.5f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawOval(c, p(0.3f, 0.08f), Size(w * 0.4f, h * 0.84f), style = st)
            }
            else -> {
                listOf(0.25f to 0.3f, 0.5f to 0.7f, 0.75f to 0.45f).forEach { (y, x) ->
                    drawLine(c, p(0.12f, y), p(0.88f, y), strokeWidth = st.width, cap = StrokeCap.Round)
                    drawCircle(SamchatColors.Card, w * 0.12f, p(x, y))
                    drawCircle(c, w * 0.1f, p(x, y), style = st)
                }
            }
        }
    }
}

@Composable
private fun HubChevron() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.12f
        drawLine(SamchatColors.Grey, Offset(w * 0.32f, h * 0.15f), Offset(w * 0.7f, h * 0.5f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(SamchatColors.Grey, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.32f, h * 0.85f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun NavRow(kind: Int, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = SamchatColors.Card
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(SamchatColors.Green.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { HubIcon(kind) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = SamchatColors.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(hint, color = SamchatColors.Grey, fontSize = 14.sp)
            }
            HubChevron()
        }
    }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
            Text("←", color = SamchatColors.White, fontSize = 26.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text(title, color = SamchatColors.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HubLabel(text: String) {
    Text(text, color = SamchatColors.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun HubButton(text: String, selected: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SamchatColors.GreenSoft else SamchatColors.Field,
            contentColor = SamchatColors.White,
            disabledContainerColor = SamchatColors.Field,
            disabledContentColor = SamchatColors.Grey
        )
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun hubFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = SamchatColors.Field,
    unfocusedContainerColor = SamchatColors.Field,
    focusedTextColor = SamchatColors.White,
    unfocusedTextColor = SamchatColors.White,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = SamchatColors.Green
)

@Composable
private fun HubSwitch(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = SamchatColors.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(note, color = SamchatColors.Grey, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = SamchatColors.Green,
                checkedThumbColor = SamchatColors.White,
                uncheckedTrackColor = SamchatColors.Field,
                uncheckedThumbColor = SamchatColors.Grey
            )
        )
    }
}

@Composable
private fun BigAvatar(bitmap: Bitmap?, letter: String, size: Int) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape).background(SamchatColors.Field), contentAlignment = Alignment.Center) {
            Text(letter, color = SamchatColors.White, fontSize = (size * 0.38).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Réglages : liste à icônes et chevrons, puis une page par rubrique. Seul panneau de l'application. */
@Composable
fun SettingsHub(
    context: Context,
    myName: String,
    myAvatar: Bitmap?,
    peers: List<MeshPeer>,
    contacts: List<MeshPeer>,
    theme: String,
    version: String,
    installer: String,
    onTheme: (String) -> Unit,
    onSaveName: (String) -> Unit,
    onSaveStatus: (String) -> Unit,
    onChangePhoto: () -> Unit,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onShare: () -> Unit,
    onDebug: () -> Unit,
    onBack: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences("meshchat", Context.MODE_PRIVATE) }
    var page by remember { mutableStateOf(0) }
    var lang by remember { mutableStateOf(prefs.getString("app_lang", "fr") ?: "fr") }
    var nameInput by remember(myName) { mutableStateOf(myName) }
    var statusInput by remember { mutableStateOf(ProfileStore.myStatus(context)) }
    var runBg by remember { mutableStateOf(AppState.runInBackground) }
    var radioBle by remember { mutableStateOf(prefs.getBoolean("radio_ble", true)) }
    var scanSend by remember { mutableStateOf(prefs.getBoolean(BleMeshManager.PREF_SCAN_SEND, true)) }
    var radioAware by remember { mutableStateOf(prefs.getBoolean("radio_wifi_aware", true)) }
    var radioDirect by remember { mutableStateOf(prefs.getBoolean("radio_wifi_direct", false)) }
    var radioLong by remember { mutableStateOf(prefs.getBoolean("radio_long_range",
