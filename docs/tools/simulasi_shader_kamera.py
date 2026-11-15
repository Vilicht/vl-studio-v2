"""
Simulasi CPU fragment shader LayerFilterRender untuk layer kamera yang memenuhi canvas.
Tujuan: membuktikan pemetaan frame -> canvas (lama vs baru) tanpa perangkat. Ini EMULASI, bukan uji di HP.

Jalankan:  python3 docs/tools/simulasi_shader_kamera.py     (butuh numpy)

Fakta dari source RootEncoder 2.7.0 (RtmpStream/StreamBase.prepareVideo):
  rotation 90/270 -> glInterface.setEncoderSize(height, width), jadi frame filter (FBO) = ukuran canvas portrait,
  encoder MediaFormat = (height, width) juga, dan GlStreamInterface tidak memutar isi (streamOrientation = 0).
"""
import numpy as np


def make_sensor(w=320, h=180, top="left"):
    img = np.full((h, w, 3), 0.2, np.float32)
    yy, xx = np.mgrid[0:h, 0:w]
    cx, cy, r = w / 2, h / 2, h * 0.32
    img[((xx - cx) ** 2 + (yy - cy) ** 2) < r * r] = (0.1, 0.8, 0.2)           # lingkaran hijau
    if top == "left":    m = (xx < w * 0.12) & (abs(yy - cy) < h * 0.08)
    elif top == "right": m = (xx > w * 0.88) & (abs(yy - cy) < h * 0.08)
    else:                m = (yy < h * 0.12) & (abs(xx - cx) < w * 0.05)
    img[m] = (1.0, 0.1, 0.1)                                                   # penanda "atas dunia"
    return img


def render(canvas, swap_axes, quarter, top, fit=0):
    cw, ch = canvas
    sensor = make_sensor(top=top)
    sh, sw, _ = sensor.shape
    vx, vy = np.meshgrid((np.arange(cw) + .5) / cw, (np.arange(ch) + .5) / ch)
    n = np.stack([vy, vx], -1) if swap_axes else np.stack([vx, 1 - vy], -1)
    t = n.copy()                                           # layer = seluruh canvas -> l = n
    ca = (sh / sw) if quarter % 2 else (sw / sh)           # rasio konten setelah koreksi putaran
    ratio = ca / (cw / ch)
    if fit == 0: cs = np.array([1, 1 / ratio]) if ratio >= 1 else np.array([ratio, 1])
    else:        cs = np.array([ratio, 1]) if ratio >= 1 else np.array([1, 1 / ratio])
    t = (t - (1 - cs) * .5) / cs
    inside = ((t >= 0) & (t <= 1)).all(-1)
    tx, ty = t[..., 0], t[..., 1]
    if quarter == 1:   tx, ty = ty, 1 - tx
    elif quarter == 2: tx, ty = 1 - tx, 1 - ty
    elif quarter == 3: tx, ty = 1 - ty, tx
    x = np.clip((tx * sw).astype(int), 0, sw - 1)
    y = np.clip((ty * sh).astype(int), 0, sh - 1)          # uST (flip Y) + (t.x, 1-t.y) = baris data top-origin
    out = np.where(inside[..., None], sensor[y, x], 0)
    return out[::-1]                                       # urutan GL (bawah dulu) -> urutan layar (atas dulu)


def report(label, img, expect=(0.5, 0.06)):
    h, w, _ = img.shape
    g = (img[..., 1] > .6) & (img[..., 0] < .3)
    r = (img[..., 0] > .8) & (img[..., 1] < .3)
    gy, gx = np.nonzero(g); ry, rx = np.nonzero(r)
    gw, gh = gx.max() - gx.min() + 1, gy.max() - gy.min() + 1
    mx, my = rx.mean() / w, ry.mean() / h
    round_ok = abs(gw / gh - 1) < .06
    up_ok = abs(mx - expect[0]) < .12 and abs(my - expect[1]) < .12
    print(f"{label:48s} lingkaran {gw}x{gh} ({gw/gh:.2f}) {'OK' if round_ok else 'GEPENG'} | "
          f"penanda ({mx:.2f},{my:.2f}) {'TEGAK' if up_ok else 'SALAH ARAH'}")


if __name__ == "__main__":
    P, L = (108, 192), (192, 108)                          # skala 1:10 dari 1080x1920 / 1920x1080
    print("Canvas PORTRAIT")
    report("belakang (sensor 90)  KODE LAMA (tukar sumbu)", render(P, True, 1, "left"))
    report("belakang (sensor 90)  KODE BARU", render(P, False, 1, "left"))
    report("depan (sensor 270)    KODE BARU", render(P, False, 3, "right"))
    report("belakang, mode Penuhi KODE BARU", render(P, False, 1, "left", fit=1))
    print("Canvas LANDSCAPE")
    report("sensor 0              KODE BARU", render(L, False, 0, "top"))
    report("sensor 90             KODE BARU", render(L, False, 1, "left"))
