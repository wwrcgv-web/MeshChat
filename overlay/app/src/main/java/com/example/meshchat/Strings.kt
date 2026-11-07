package com.example.meshchat

import android.content.Context

/** Langue courante de l'application, lue dans les préférences. */
object Lang {
    @Volatile var code: String = "fr"

    fun load(context: Context) {
        code = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
            .getString("app_lang", "fr") ?: "fr"
    }

    fun index(): Int = when (code) {
        "en" -> 1
        "es" -> 2
        "pt" -> 3
        "ar" -> 4
        "zh" -> 5
        else -> 0
    }
}

/** Textes de l'interface : clé -> (fr, en, es, pt, ar, zh). Les autres langues affichent le français. */
private val STRINGS: Map<String, List<String>> = mapOf(
    "discussions" to listOf("Discussions", "Chats", "Chats", "Conversas", "المحادثات", "聊天"),
    "contacts" to listOf("Contacts", "Contacts", "Contactos", "Contactos", "جهات الاتصال", "联系人"),
    "search" to listOf("Rechercher", "Search", "Buscar", "Pesquisar", "بحث", "搜索"),
    "all" to listOf("Toutes", "All", "Todas", "Todas", "الكل", "全部"),
    "in_range" to listOf("À portée", "In range", "Al alcance", "Ao alcance", "في النطاق", "范围内"),
    "all_group" to listOf("Tous", "Everyone", "Todos", "Todos", "الجميع", "所有人"),
    "no_message" to listOf("Aucun message", "No messages", "Sin mensajes", "Sem mensagens", "لا توجد رسائل", "没有消息"),
    "no_contact" to listOf("Aucun contact pour l'instant", "No contacts yet", "Aún no hay contactos", "Ainda sem contactos", "لا توجد جهات اتصال بعد", "暂无联系人"),
    "go_contacts" to listOf("Va dans Contacts pour scanner un ami.", "Go to Contacts to scan a friend.", "Ve a Contactos para escanear a un amigo.", "Vá a Contactos para ler um amigo.", "اذهب إلى جهات الاتصال لمسح صديق.", "前往联系人扫描朋友。"),
    "active" to listOf("actif", "active", "activo", "ativo", "نشط", "已启用"),
    "stopped" to listOf("arrêté", "stopped", "detenido", "parado", "متوقف", "已停止"),
    "to_send" to listOf("Écrire", "Write", "Escribir", "Escrever", "اكتب", "输入"),
    "voice" to listOf("Vocal", "Voice", "Voz", "Voz", "صوت", "语音"),
    "photo" to listOf("Photo", "Photo", "Foto", "Foto", "صورة", "照片"),
    "message_for" to listOf("Écris ou envoie un vocal pour commencer.", "Type or send a voice note to start.", "Escribe o envía una nota de voz para empezar.", "Escreva ou envie uma nota de voz para começar.", "اكتب أو أرسل رسالة صوتية للبدء.", "输入或发送语音开始聊天。"),
    "photo_ready" to listOf("Photo prête : appuie sur ➤", "Photo ready: tap ➤", "Foto lista: toca ➤", "Foto pronta: toque em ➤", "الصورة جاهزة: اضغط ➤", "照片已就绪：点击 ➤"),
    "remove" to listOf("Retirer", "Remove", "Quitar", "Remover", "إزالة", "移除"),
    "in_range_status" to listOf("À portée", "In range", "Al alcance", "Ao alcance", "في النطاق", "范围内"),
    "out_of_range" to listOf("Hors de portée", "Out of range", "Fuera de alcance", "Fora de alcance", "خارج النطاق", "超出范围"),
    "all_range" to listOf("à portée", "in range", "al alcance", "ao alcance", "في النطاق", "范围内"),
    "delete_q" to listOf("Supprimer ce message ?", "Delete this message?", "¿Borrar este mensaje?", "Apagar esta mensagem?", "حذف هذه الرسالة؟", "删除此消息？"),
    "delete_note" to listOf("Le message sera retiré de ce téléphone seulement.", "The message will be removed from this phone only.", "El mensaje se borrará solo de este teléfono.", "A mensagem será removida apenas deste telefone.", "ستُحذف الرسالة من هذا الهاتف فقط.", "消息仅从此手机删除。"),
    "delete" to listOf("Supprimer", "Delete", "Borrar", "Apagar", "حذف", "删除"),
    "cancel" to listOf("Annuler", "Cancel", "Cancelar", "Cancelar", "إلغاء", "取消"),
    "close" to listOf("Fermer", "Close", "Cerrar", "Fechar", "إغلاق", "关闭"),
    "my_qr" to listOf("Mon QR code", "My QR code", "Mi código QR", "Meu QR code", "رمز QR الخاص بي", "我的二维码"),
    "my_qr_note" to listOf("L'autre personne le scanne pour t'ajouter.", "The other person scans it to add you.", "La otra persona lo escanea para añadirte.", "A outra pessoa lê-o para o adicionar.", "يمسحه الشخص الآخر لإضافتك.", "对方扫描即可添加你。"),
    "welcome" to listOf("Bienvenue sur Samchat", "Welcome to Samchat", "Bienvenido a Samchat", "Bem-vindo ao Samchat", "مرحبًا بك في Samchat", "欢迎使用 Samchat"),
    "choose_name" to listOf("Choisis le nom que verront les autres téléphones.", "Choose the name other phones will see.", "Elige el nombre que verán los demás teléfonos.", "Escolha o nome que os outros telemóveis vão ver.", "اختر الاسم الذي ستراه الهواتف الأخرى.", "选择其他手机将看到的名称。"),
    "continue" to listOf("Continuer", "Continue", "Continuar", "Continuar", "متابعة", "继续"),
    "your_name" to listOf("ton nom", "your name", "tu nombre", "o teu nome", "اسمك", "你的名字"),
    "photo_choice" to listOf("Ajouter une photo", "Add a photo", "Añadir una foto", "Adicionar uma foto", "إضافة صورة", "添加照片"),
    "what_do" to listOf("Que voulez-vous faire ?", "What would you like to do?", "¿Qué quieres hacer?", "O que quer fazer?", "ماذا تريد أن تفعل؟", "你想做什么？"),
    "take_photo" to listOf("Prendre une photo", "Take a photo", "Hacer una foto", "Tirar uma foto", "التقاط صورة", "拍照"),
    "pick_image" to listOf("Choisir une image", "Choose an image", "Elegir una imagen", "Escolher uma imagem", "اختيار صورة", "选择图片"),
    "saved_voice" to listOf("Enregistrer le vocal", "Save voice note", "Guardar nota de voz", "Guardar nota de voz", "حفظ الرسالة الصوتية", "保存语音"),
    "saved_photo" to listOf("Enregistrer la photo", "Save photo", "Guardar foto", "Guardar foto", "حفظ الصورة", "保存照片"),
    "sent" to listOf("Envoyé", "Sent", "Enviado", "Enviado", "تم الإرسال", "已发送"),
    "waiting" to listOf("En attente", "Waiting", "En espera", "A aguardar", "قيد الانتظار", "等待中"),
    "photo_sent" to listOf("Photo envoyée", "Photo sent", "Foto enviada", "Foto enviada", "تم إرسال الصورة", "照片已发送"),
    "voice_sent" to listOf("Vocal envoyé", "Voice note sent", "Nota de voz enviada", "Nota de voz enviada", "تم إرسال الرسالة الصوتية", "语音已发送"),
    "pair_online" to listOf("Pairs à portée", "Peers in range", "Pares al alcance", "Pares ao alcance", "الأقران في النطاق", "范围内的设备")
)

/** Traduit une clé selon la langue courante. Sans traduction, affiche le français. */
fun t(key: String): String {
    val list = STRINGS[key] ?: return key
    return list[Lang.index()]
}
