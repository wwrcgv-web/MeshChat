package com.example.meshchat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Écran de partage et de lecture du QR code, en deux onglets.
 * La caméra ne tourne que sur l'onglet « Scanner » : elle est mise en pause dès qu'on le quitte.
 */
@Composable
fun QrScreen(
    context: Context,
    onClose: () -> Unit,
    onScanned: (String) -> Unit
) {
    var tab by remember { mutableStateOf(0) }   // 0 = mon QR, 1 = scanner
    var autorisation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val demandeCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { accordee -> autorisation = accordee }

    val scanActuel by rememberUpdatedState(onScanned)
    val vue = remember { mutableStateOf<BarcodeView?>(null) }

    DisposableEffect(Unit) {
        onDispose { vue.value?.pause() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = SamchatColors.Background) {
        Column(Modifier.fillMaxSize()) {
            // En-tête : titre à gauche, croix à droite
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp)
            ) {
                Text(
                    t("contacts"),
                    color = SamchatColors.Green,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = SamchatColors.Green, fontSize = 30.sp)
                }
            }

            // Onglets de même largeur, avec trait vert sous l'actif
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Onglet(t("my_qr"), tab == 0, Modifier.weight(1f)) { tab = 0 }
                Onglet(t("tab_scan"), tab == 1, Modifier.weight(1f)) { tab = 1 }
            }

            if (tab == 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)
                ) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        t("qr_hint"),
                        color = SamchatColors.Green,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(24.dp))
                    val contenu = remember { QrShare.myContent(context) }
                    if (contenu != null) {
                        Box(
                            Modifier
                                .size(270.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(14.dp)
                        ) {
                            Image(
                                bitmap = remember(contenu) { QrShare.bitmap(contenu) }.asImageBitmap(),
                                contentDescription = t("my_qr"),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Text("—", color = SamchatColors.Grey, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(
                        "@${MeshState.pseudo}",
                        color = SamchatColors.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    if (autorisation) {
                        AndroidView(
                            factory = { ctx ->
                                BarcodeView(ctx).apply {
                                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                                    decodeContinuous(object : BarcodeCallback {
                                        override fun barcodeResult(result: BarcodeResult?) {
                                            val texte = result?.text ?: return
                                            scanActuel(texte)
                                        }

                                        override fun possibleResultPoints(points: MutableList<com.google.zxing.ResultPoint>?) {
                                        }
                                    })
                                    vue.value = this
                                    resume()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Cadre de visée
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(240.dp)
                                .border(3.dp, SamchatColors.Green, RoundedCornerShape(18.dp))
                        )
                        Text(
                            t("scan_hint"),
                            color = SamchatColors.White,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        ) {
                            Text(t("camera_needed"), color = SamchatColors.White, fontSize = 16.sp)
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { demandeCamera.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = SamchatColors.Green, contentColor = Color.Black)
                            ) { Text(t("allow_camera"), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

/** Un onglet : texte centré, trait vert épais sous l'onglet actif. */
@Composable
private fun Onglet(texte: String, actif: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            texte,
            color = if (actif) SamchatColors.Green else SamchatColors.Grey,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(if (actif) SamchatColors.Green else SamchatColors.Field)
        )
    }
}
