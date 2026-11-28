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
    "tab_scan" to listOf("Scanner", "Scan", "Escanear", "Ler", "مسح", "扫描"),
    "qr_hint" to listOf("Fais-toi scanner pour être ajouté", "Let others scan you to add you", "Deja que te escaneen para añadirte", "Deixa que te leiam para te adicionar", "دع الآخرين يمسحونك لإضافتك", "让对方扫描以添加你"),
    "scan_hint" to listOf("Visez le QR code de l'autre téléphone", "Point at the other phone's QR code", "Apunta al código QR del otro teléfono", "Aponte para o QR code do outro telemóvel", "وجّه إلى رمز QR للهاتف الآخر", "对准另一部手机的二维码"),
    "camera_needed" to listOf("La caméra sert à lire le QR code", "The camera is used to read the QR code", "La cámara sirve para leer el código QR", "A câmara serve para ler o QR code", "تُستخدم الكاميرا لقراءة رمز QR", "相机用于读取二维码"),
    "allow_camera" to listOf("Autoriser la caméra", "Allow camera", "Permitir cámara", "Permitir câmara", "السماح بالكاميرا", "允许相机"),
    "mode_internet" to listOf("Avec Internet", "With internet", "Con Internet", "Com Internet", "مع الإنترنت", "有网络"),
    "mode_internet_hint" to listOf("Messages partout", "Messages anywhere", "Mensajes en cualquier lugar", "Mensagens em qualquer lugar", "رسائل في كل مكان", "随时随地发消息"),
    "mode_offline" to listOf("Sans Internet", "Without internet", "Sin Internet", "Sem Internet", "بدون إنترنت", "无网络"),
    "mode_offline_hint" to listOf("Amis à côté", "Friends nearby", "Amigos cerca", "Amigos por perto", "أصدقاء قريبون", "附近的朋友"),
    "acc_backup_title" to listOf("Sauvegarde du compte", "Account backup", "Account backup", "Account backup", "Account backup", "Account backup"),
    "acc_replace_q" to listOf("Remplacer ce compte ?", "Replace this account?", "Replace this account?", "Replace this account?", "Replace this account?", "Replace this account?"),
    "acc_replace_note" to listOf("Ton identité et tes contacts actuels seront remplacés par ceux de la sauvegarde. Cette action ne peut pas être annulée.", "Your identity and current contacts will be replaced by the backup. This cannot be undone.", "Your identity and current contacts will be replaced by the backup. This cannot be undone.", "Your identity and current contacts will be replaced by the backup. This cannot be undone.", "Your identity and current contacts will be replaced by the backup. This cannot be undone.", "Your identity and current contacts will be replaced by the backup. This cannot be undone."),
    "acc_replace" to listOf("Remplacer", "Replace", "Replace", "Replace", "Replace", "Replace"),
    "acc_pass_hint" to listOf("Mot de passe (12 caractères min.)", "Password (12 characters min.)", "Password (12 characters min.)", "Password (12 characters min.)", "Password (12 characters min.)", "Password (12 characters min.)"),
    "acc_save" to listOf("Sauvegarder le compte", "Back up account", "Back up account", "Back up account", "Back up account", "Back up account"),
    "acc_copy" to listOf("Copier la sauvegarde", "Copy backup", "Copy backup", "Copy backup", "Copy backup", "Copy backup"),
    "acc_restore_title" to listOf("Restaurer un compte", "Restore an account", "Restore an account", "Restore an account", "Restore an account", "Restore an account"),
    "acc_paste_hint" to listOf("Colle la sauvegarde ici", "Paste the backup here", "Paste the backup here", "Paste the backup here", "Paste the backup here", "Paste the backup here"),
    "acc_pass_restore_hint" to listOf("Mot de passe de la sauvegarde", "Backup password", "Backup password", "Backup password", "Backup password", "Backup password"),
    "acc_restore" to listOf("Restaurer le compte", "Restore account", "Restore account", "Restore account", "Restore account", "Restore account"),
    "acc_pass_short" to listOf("Mot de passe trop court", "Password too short", "Password too short", "Password too short", "Password too short", "Password too short"),
    "acc_creating" to listOf("Création en cours…", "Creating…", "Creating…", "Creating…", "Creating…", "Creating…"),
    "acc_created" to listOf("Sauvegarde créée : copie-la et garde-la hors du téléphone", "Backup created: copy it and keep it off the phone", "Backup created: copy it and keep it off the phone", "Backup created: copy it and keep it off the phone", "Backup created: copy it and keep it off the phone", "Backup created: copy it and keep it off the phone"),
    "acc_create_fail" to listOf("Échec de la sauvegarde", "Backup failed", "Backup failed", "Backup failed", "Backup failed", "Backup failed"),
    "acc_copied" to listOf("Sauvegarde copiée", "Backup copied", "Backup copied", "Backup copied", "Backup copied", "Backup copied"),
    "acc_restoring" to listOf("Restauration en cours…", "Restoring…", "Restoring…", "Restoring…", "Restoring…", "Restoring…"),
    "acc_restored" to listOf("Compte restauré. Le service redémarre : ferme puis rouvre l'app.", "Account restored. Service restarting: close and reopen the app.", "Account restored. Service restarting: close and reopen the app.", "Account restored. Service restarting: close and reopen the app.", "Account restored. Service restarting: close and reopen the app.", "Account restored. Service restarting: close and reopen the app."),
    "acc_restore_fail" to listOf("Échec : mot de passe ou sauvegarde incorrect", "Failed: wrong password or backup", "Failed: wrong password or backup", "Failed: wrong password or backup", "Failed: wrong password or backup", "Failed: wrong password or backup"),
    "terms_title" to listOf("Bienvenue dans Samchat", "Welcome to Samchat", "Welcome to Samchat", "Welcome to Samchat", "Welcome to Samchat", "Welcome to Samchat"),
    "terms_sub" to listOf("À lire avant de commencer", "Read this before you start", "Read this before you start", "Read this before you start", "Read this before you start", "Read this before you start"),
    "terms_c1_t" to listOf("Chiffrement de bout en bout", "End-to-end encryption", "End-to-end encryption", "End-to-end encryption", "End-to-end encryption", "End-to-end encryption"),
    "terms_c1_x" to listOf("Tes messages, photos et vocaux sont chiffrés sur ton téléphone avant de partir. Aucun téléphone intermédiaire, aucun serveur ne peut lire le contenu.", "Your messages, photos and voice notes are encrypted on your phone before leaving. No relay phone, no server can read the content.", "Your messages, photos and voice notes are encrypted on your phone before leaving. No relay phone, no server can read the content.", "Your messages, photos and voice notes are encrypted on your phone before leaving. No relay phone, no server can read the content.", "Your messages, photos and voice notes are encrypted on your phone before leaving. No relay phone, no server can read the content.", "Your messages, photos and voice notes are encrypted on your phone before leaving. No relay phone, no server can read the content."),
    "terms_c2_t" to listOf("Réseau maillé", "Mesh network", "Mesh network", "Mesh network", "Mesh network", "Mesh network"),
    "terms_c2_x" to listOf("Sans réseau, tes messages peuvent transiter par d'autres téléphones à portée. Ils ne voient que du contenu chiffré ; ta position n'est pas partagée.", "Without a network, your messages can travel through other nearby phones. They only see encrypted content; your location is not shared.", "Without a network, your messages can travel through other nearby phones. They only see encrypted content; your location is not shared.", "Without a network, your messages can travel through other nearby phones. They only see encrypted content; your location is not shared.", "Without a network, your messages can travel through other nearby phones. They only see encrypted content; your location is not shared.", "Without a network, your messages can travel through other nearby phones. They only see encrypted content; your location is not shared."),
    "terms_c3_t" to listOf("Mode « Avec Internet »", "“With internet” mode", "“With internet” mode", "“With internet” mode", "“With internet” mode", "“With internet” mode"),
    "terms_c3_x" to listOf("Si tu l'actives, le contenu chiffré est déposé sur un relais. Le relais voit l'heure et un identifiant haché du destinataire — jamais le texte en clair.", "If enabled, encrypted content is left on a relay. The relay sees the time and a hashed recipient ID — never the plain text.", "If enabled, encrypted content is left on a relay. The relay sees the time and a hashed recipient ID — never the plain text.", "If enabled, encrypted content is left on a relay. The relay sees the time and a hashed recipient ID — never the plain text.", "If enabled, encrypted content is left on a relay. The relay sees the time and a hashed recipient ID — never the plain text.", "If enabled, encrypted content is left on a relay. The relay sees the time and a hashed recipient ID — never the plain text."),
    "terms_c4_t" to listOf("Ta clé t'appartient", "Your key belongs to you", "Your key belongs to you", "Your key belongs to you", "Your key belongs to you", "Your key belongs to you"),
    "terms_c4_x" to listOf("La clé privée reste dans le téléphone et n'est pas sauvegardée. Désinstaller l'application ou perdre le téléphone efface définitivement ton compte et tes contacts.", "The private key stays on the phone and is not backed up. Uninstalling the app or losing the phone permanently erases your account and contacts.", "The private key stays on the phone and is not backed up. Uninstalling the app or losing the phone permanently erases your account and contacts.", "The private key stays on the phone and is not backed up. Uninstalling the app or losing the phone permanently erases your account and contacts.", "The private key stays on the phone and is not backed up. Uninstalling the app or losing the phone permanently erases your account and contacts.", "The private key stays on the phone and is not backed up. Uninstalling the app or losing the phone permanently erases your account and contacts."),
    "terms_c5_t" to listOf("Données minimales", "Minimal data", "Minimal data", "Minimal data", "Minimal data", "Minimal data"),
    "terms_c5_x" to listOf("Pas de compte à créer, pas de publicité, pas de suivi. Le pseudo, les contacts et l'historique restent sur ton appareil.", "No account to create, no ads, no tracking. Your nickname, contacts and history stay on your device.", "No account to create, no ads, no tracking. Your nickname, contacts and history stay on your device.", "No account to create, no ads, no tracking. Your nickname, contacts and history stay on your device.", "No account to create, no ads, no tracking. Your nickname, contacts and history stay on your device.", "No account to create, no ads, no tracking. Your nickname, contacts and history stay on your device."),
    "terms_c6_t" to listOf("Responsabilité", "Liability", "Liability", "Liability", "Liability", "Liability"),
    "terms_c6_x" to listOf("Samchat est fourni « en l'état », sans garantie de livraison ni de disponibilité. Tu l'utilises sous ta responsabilité et dans le respect des lois applicables.", "Samchat is provided “as is”, with no guarantee of delivery or availability. You use it at your own responsibility and in compliance with applicable laws.", "Samchat is provided “as is”, with no guarantee of delivery or availability. You use it at your own responsibility and in compliance with applicable laws.", "Samchat is provided “as is”, with no guarantee of delivery or availability. You use it at your own responsibility and in compliance with applicable laws.", "Samchat is provided “as is”, with no guarantee of delivery or availability. You use it at your own responsibility and in compliance with applicable laws.", "Samchat is provided “as is”, with no guarantee of delivery or availability. You use it at your own responsibility and in compliance with applicable laws."),
    "terms_footer" to listOf("Tu peux arrêter à tout moment en désinstallant l'application.", "You can stop at any time by uninstalling the app.", "You can stop at any time by uninstalling the app.", "You can stop at any time by uninstalling the app.", "You can stop at any time by uninstalling the app.", "You can stop at any time by uninstalling the app."),
    "terms_check" to listOf("J'ai lu et j'accepte les Conditions d'Utilisation et la Politique de Confidentialité", "I have read and accept the Terms of Use and the Privacy Policy", "I have read and accept the Terms of Use and the Privacy Policy", "I have read and accept the Terms of Use and the Privacy Policy", "I have read and accept the Terms of Use and the Privacy Policy", "I have read and accept the Terms of Use and the Privacy Policy"),
    "terms_start" to listOf("Commencer", "Start", "Start", "Start", "Start", "Start"),
    "privacy_title" to listOf("CONFIDENTIALITÉ", "PRIVACY", "PRIVACY", "PRIVACY", "PRIVACY", "PRIVACY"),
    "privacy_main" to listOf("Tes messages privés et tes fichiers privés sont chiffrés de bout en bout : seuls toi et ton contact pouvez les lire. Le relais Internet ne voit que des données chiffrées et ne peut pas les déchiffrer. Les messages gardés pour un contact absent sont effacés après 24 heures.", "Your private messages and files are end-to-end encrypted: only you and your contact can read them. The internet relay only sees encrypted data and cannot decrypt it. Messages held for an absent contact are erased after 24 hours.", "Your private messages and files are end-to-end encrypted: only you and your contact can read them. The internet relay only sees encrypted data and cannot decrypt it. Messages held for an absent contact are erased after 24 hours.", "Your private messages and files are end-to-end encrypted: only you and your contact can read them. The internet relay only sees encrypted data and cannot decrypt it. Messages held for an absent contact are erased after 24 hours.", "Your private messages and files are end-to-end encrypted: only you and your contact can read them. The internet relay only sees encrypted data and cannot decrypt it. Messages held for an absent contact are erased after 24 hours.", "Your private messages and files are end-to-end encrypted: only you and your contact can read them. The internet relay only sees encrypted data and cannot decrypt it. Messages held for an absent contact are erased after 24 hours."),
    "privacy_public" to listOf("Les messages du groupe ne sont pas chiffrés de bout en bout. La sauvegarde du compte est protégée par ton mot de passe.", "Group messages are not end-to-end encrypted. The account backup is protected by your password.", "Group messages are not end-to-end encrypted. The account backup is protected by your password.", "Group messages are not end-to-end encrypted. The account backup is protected by your password.", "Group messages are not end-to-end encrypted. The account backup is protected by your password.", "Group messages are not end-to-end encrypted. The account backup is protected by your password."),
    "wifi_files" to listOf("Gros fichiers entre voisins.", "Large files between neighbours.", "Large files between neighbours.", "Large files between neighbours.", "Large files between neighbours.", "Large files between neighbours."),
    "wifi_direct_hint" to listOf("Gros fichiers privés.", "Large private files.", "Large private files.", "Large private files.", "Large private files.", "Large private files."),
    "long_range" to listOf("Bluetooth longue portée", "Long-range Bluetooth", "Long-range Bluetooth", "Long-range Bluetooth", "Long-range Bluetooth", "Long-range Bluetooth"),
    "long_range_hint" to listOf("Plus loin, mais plus lent.", "Farther, but slower.", "Farther, but slower.", "Farther, but slower.", "Farther, but slower.", "Farther, but slower."),
    "not_supported" to listOf("Non supporté.", "Not supported.", "Not supported.", "Not supported.", "Not supported.", "Not supported."),
    "bt_title" to listOf("Activer le Bluetooth", "Turn on Bluetooth", "Turn on Bluetooth", "Turn on Bluetooth", "Turn on Bluetooth", "Turn on Bluetooth"),
    "bt_note" to listOf("Le Bluetooth est nécessaire pour envoyer et recevoir les messages hors ligne, sans Internet.", "Bluetooth is needed to send and receive messages offline, without internet.", "Bluetooth is needed to send and receive messages offline, without internet.", "Bluetooth is needed to send and receive messages offline, without internet.", "Bluetooth is needed to send and receive messages offline, without internet.", "Bluetooth is needed to send and receive messages offline, without internet."),
    "bt_enable" to listOf("Activer", "Turn on", "Turn on", "Turn on", "Turn on", "Turn on"),
    "bt_later" to listOf("Plus tard", "Later", "Later", "Later", "Later", "Later"),
    "dbg_title" to listOf("Outils de debug", "Debug tools", "Debug tools", "Debug tools", "Debug tools", "Debug tools"),
    "dbg_peers" to listOf("Pairs vus", "Peers seen", "Peers seen", "Peers seen", "Peers seen", "Peers seen"),
    "dbg_contacts" to listOf("Contacts", "Contacts", "Contacts", "Contacts", "Contacts", "Contacts"),
    "dbg_received" to listOf("Messages reçus", "Messages received", "Messages received", "Messages received", "Messages received", "Messages received"),
    "dbg_sent" to listOf("Envois réussis", "Successful sends", "Successful sends", "Successful sends", "Successful sends", "Successful sends"),
    "dbg_fail" to listOf("Échecs", "Failures", "Failures", "Failures", "Failures", "Failures"),
    "dbg_version" to listOf("Version", "Version", "Version", "Version", "Version", "Version"),
    "dbg_copy" to listOf("Copier l'empreinte", "Copy fingerprint", "Copy fingerprint", "Copy fingerprint", "Copy fingerprint", "Copy fingerprint"),
    "dbg_reset" to listOf("Remettre à zéro", "Reset", "Reset", "Reset", "Reset", "Reset"),
    "dbg_service" to listOf("Service", "Service", "Service", "Serviço", "الخدمة", "服务"),
    "status_received" to listOf("Reçu", "Received", "Recibido", "Recebido", "تم الاستلام", "已收到"),
    "status_read" to listOf("Lu", "Read", "Leído", "Lido", "تمت القراءة", "已读"),
    "status_failed" to listOf("Non envoyé", "Not sent", "No enviado", "Não enviado", "لم يُرسل", "未发送"),
    "status_pending" to listOf("En attente", "Waiting", "En espera", "Em espera", "قيد الانتظار", "等待中"),
    "status_sent" to listOf("Envoyé", "Sent", "Enviado", "Enviado", "تم الإرسال", "已发送"),
    "dbg_log" to listOf("JOURNAL", "LOG", "REGISTRO", "REGISTO", "السجل", "日志"),
    "dbg_copy_log" to listOf("Copier le journal", "Copy log", "Copiar el registro", "Copiar o registo", "نسخ السجل", "复制日志"),
    "all_group" to listOf("Groupe", "Group", "Grupo", "Grupo", "المجموعة", "群组"),
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
    "pair_online" to listOf("Pairs à portée", "Peers in range", "Pares al alcance", "Pares ao alcance", "الأقران في النطاق", "范围内的设备"),
    "you" to listOf("Moi", "Me", "Yo", "Eu", "أنا", "我"),
    "send_fail" to listOf("Envoi impossible", "Send failed", "Envío fallido", "Envio falhou", "فشل الإرسال", "发送失败"),
    "service_off" to listOf("Service non activé : activez le service", "Service not running: turn it on", "Servicio no activado: actívalo", "Serviço não ativado: ative-o", "الخدمة غير مفعّلة: فعّلها", "服务未启用：请开启服务"),
    "photo_ready_short" to listOf("Photo prête : appuie sur ➤", "Photo ready: tap ➤", "Foto lista: toca ➤", "Foto pronta: toque em ➤", "الصورة جاهزة: اضغط ➤", "照片已就绪：点击 ➤"),
    "write" to listOf("Écrire", "Write", "Escribir", "Escrever", "اكتب", "输入"),
    "reading_error" to listOf("Photo de profil illisible", "Profile photo unreadable", "Foto de perfil ilegible", "Foto de perfil ilegível", "الصورة الشخصية غير قابلة للقراءة", "头像无法读取"),
    "saved_status" to listOf("Statut enregistré", "Status saved", "Estado guardado", "Estado guardado", "تم حفظ الحالة", "状态已保存")
)

/** Traduit une clé selon la langue courante. Sans traduction, affiche le français. */
fun t(key: String): String {
    val list = STRINGS[key] ?: return key
    return list[Lang.index()]
}
