package com.example.ui.theme

/**
 * Warna merek VILICHT dalam ARGB Int untuk kode non-Compose (jendela melayang berbasis View, renderer bitmap).
 * HARUS selaras dengan Color.kt; ubah keduanya bersamaan.
 */
object BrandColors {
    const val BG_DEEP = 0xFF0F0D14.toInt()     // CharcoalMain
    const val PANEL_DEEP = 0xFF16131E.toInt()  // CharcoalSecondary
    const val PANEL = 0xFF1D1A27.toInt()       // CharcoalPanel
    const val SURFACE = 0xFF272333.toInt()     // CharcoalSurface
    const val BORDER = 0xFF3A3548.toInt()      // CharcoalBorder
    const val TEXT = 0xFFF3F0FA.toInt()        // TextPrimary
    const val TEXT_SECONDARY = 0xFFB1ABC4.toInt()
    const val TEXT_MUTED = 0xFF8E88A3.toInt()  // sedikit lebih terang dari TextMuted agar terbaca di panel kecil
    const val ACCENT = 0xFFA855F7.toInt()      // ChampagneGold (nilai ungu)
    const val ACCENT_LIGHT = 0xFFC084FC.toInt()
    const val GREEN = 0xFF5B8A64.toInt()       // StatusGreen
    const val AMBER = 0xFFC49B4B.toInt()       // StatusAmber
    const val RED = 0xFFB24A4A.toInt()         // StatusRed
}
