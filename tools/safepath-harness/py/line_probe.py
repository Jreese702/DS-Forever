import sys, colorsys
import numpy as np
from PIL import Image
im = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.float32) / 255
mx = im.max(2); mn = im.min(2); d = mx - mn
sat = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0); bri = mx
r, g, b = im[..., 0], im[..., 1], im[..., 2]
hue = np.zeros_like(mx)
dd = np.maximum(d, 1e-6)
hue = np.where(mx == r, 60 * (((g - b) / dd) % 6), np.where(mx == g, 60 * ((b - r) / dd + 2), 60 * ((r - g) / dd + 4)))
core = (hue >= 30) & (hue <= 58) & (sat >= 0.7) & (bri <= 0.5)
green = (hue >= 60) & (hue <= 100) & (sat >= 0.4)
R = 4
H, W = core.shape
road = np.zeros_like(core)
def shift(a, dy, dx):
    o = np.zeros_like(a); ys = slice(max(dy, 0), H + min(dy, 0)); yd = slice(max(-dy, 0), H + min(-dy, 0))
    xs = slice(max(dx, 0), W + min(dx, 0)); xd = slice(max(-dx, 0), W + min(-dx, 0)); o[ys, xs] = a[yd, xd]; return o
up = np.zeros_like(core); dn = np.zeros_like(core); lf = np.zeros_like(core); rt = np.zeros_like(core)
for k in range(1, R + 1):
    up |= shift(green, k, 0); dn |= shift(green, -k, 0); lf |= shift(green, 0, k); rt |= shift(green, 0, -k)
road = core & ((up & dn) | (lf & rt))
out = (np.asarray(Image.open(sys.argv[1]).convert('RGB')) * 0.5).astype(np.uint8)
out[road] = (255, 0, 0)
Image.fromarray(out).save(sys.argv[2]); print('road px', int(road.sum()))
