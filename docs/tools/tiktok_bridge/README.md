# Jembatan chat TikTok (contoh, BELUM DIUJI)

Aplikasi tidak memanggil TikTok langsung. Provider "WebSocket (skema VILICHT JSON)" terhubung ke WebSocket pilihan Anda
yang mengirim satu objek JSON per pesan:

| type | field |
|---|---|
| comment | `id?`, `user`, `text` |
| gift | `id?`, `user`, `gift`, `count?` |
| follow / share / join | `user` |
| like | `user`, `count?` |
| viewers | `count` |
| status | `message` |

`bridge.js` adalah contoh untuk menghasilkan skema itu dari library tidak resmi `tiktok-live-connector`.
Batasan: bukan API resmi TikTok, bisa rusak saat TikTok berubah, risiko ketentuan layanan ada pada Anda,
dan nama event berbeda antar versi library. Aplikasi menolak `ws://` ke alamat publik (hanya `wss://` atau jaringan lokal),
dan token dikirim lewat header `Authorization`, bukan URL.
