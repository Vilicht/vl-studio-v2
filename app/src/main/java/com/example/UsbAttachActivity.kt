package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.service.StreamingService

/**
 * Perantara tanpa tampilan untuk USB_DEVICE_ATTACHED (webcam USB dicolok).
 *
 * Tugasnya hanya menerima tawaran sistem agar izin USB perangkat bisa diingat. Studio TIDAK dipaksa ke depan
 * bila service sudah berjalan: aplikasi ini dipakai sambil bermain game, dan menarik layar Studio ke depan
 * saat kamera dicolok akan mengganggu permainan. Daftar kamera diperbarui sendiri oleh ViewModel dan
 * sumber kamera USB tersambung otomatis lewat deteksi library UVC.
 *
 * Bila belum ada service (aplikasi tertutup), Studio dibuka supaya pengguna melihat hasil pilihannya.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (StreamingService.activeServiceInstance == null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        finish()
    }
}
