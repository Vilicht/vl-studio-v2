// CONTOH JEMBATAN (BELUM DIUJI) — mengubah event library tidak resmi `tiktok-live-connector` menjadi skema
// "VILICHT Chat JSON v1" lewat WebSocket, untuk dipakai provider "WebSocket (skema VILICHT JSON)" di aplikasi.
//
// PERINGATAN: TikTok TIDAK punya API publik resmi untuk membaca komentar LIVE. Library di atas adalah proyek
// reverse-engineering tidak resmi; bisa berhenti bekerja kapan saja dan pemakaiannya tunduk pada ketentuan layanan
// TikTok (risiko Anda sendiri). Nama event/field berbeda antar versi library: periksa README versi yang Anda pasang
// dan sesuaikan fungsi `pick*` di bawah. Skrip ini hanya titik awal.
//
// Pakai:  npm i tiktok-live-connector ws
//         TT_USER=namaakun TOKEN=rahasia node bridge.js        (di PC/Raspberry di jaringan yang sama dgn HP)
// Aplikasi: Endpoint ws://IP-PC:8787 (ws:// hanya diizinkan untuk jaringan lokal), Token = nilai TOKEN.
const { WebSocketServer } = require('ws');
const tl = require('tiktok-live-connector');

const USER = process.env.TT_USER, TOKEN = process.env.TOKEN || '', PORT = +(process.env.PORT || 8787);
if (!USER) { console.error('Set TT_USER'); process.exit(1); }

const wss = new WebSocketServer({ port: PORT, verifyClient: ({ req }) => !TOKEN || req.headers['authorization'] === `Bearer ${TOKEN}` });
const send = (o) => { const s = JSON.stringify(o); wss.clients.forEach(c => c.readyState === 1 && c.send(s)); };

const pickUser = (d) => d?.user?.uniqueId ?? d?.uniqueId ?? d?.nickname ?? '';
const Conn = tl.TikTokLiveConnection ?? tl.WebcastPushConnection;      // v2 / v1
const conn = new Conn(USER);
const ev = tl.WebcastEvent ?? {};

conn.on(ev.CHAT ?? 'chat', d => send({ type: 'comment', id: String(d.msgId ?? ''), user: pickUser(d), text: d.comment ?? '' }));
conn.on(ev.GIFT ?? 'gift', d => send({ type: 'gift', id: String(d.msgId ?? ''), user: pickUser(d),
  gift: d.giftName ?? d.giftDetails?.giftName ?? 'Gift', count: d.repeatCount ?? 1 }));
conn.on(ev.ROOM_USER ?? 'roomUser', d => { if (typeof d.viewerCount === 'number') send({ type: 'viewers', count: d.viewerCount }); });
conn.on(ev.FOLLOW ?? 'follow', d => send({ type: 'follow', user: pickUser(d) }));

(function connect() {
  conn.connect().then(() => send({ type: 'status', message: 'Jembatan tersambung ke LIVE' }))
    .catch(e => { console.error('connect gagal:', e?.message ?? e); setTimeout(connect, 10_000); });
})();
