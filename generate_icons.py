import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    os.system("pip install Pillow")
    from PIL import Image, ImageDraw

ICON_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

use_icon = os.path.isfile("icon.jpg")

if use_icon:
    img_base = Image.open("icon.jpg")
    if img_base.mode == "RGBA":
        bg = Image.new("RGB", img_base.size, (0, 0, 0))
        bg.paste(img_base, mask=img_base.split()[3])
        img_base = bg
    elif img_base.mode != "RGB":
        img_base = img_base.convert("RGB")

for density, size in ICON_SIZES.items():
    out_dir = "android/app/src/main/res/mipmap-" + density
    os.makedirs(out_dir, exist_ok=True)

    if use_icon:
        icon = img_base.resize((size, size), Image.LANCZOS)
    else:
        icon = Image.new("RGB", (size, size), (8, 12, 16))
        draw = ImageDraw.Draw(icon)
        draw.ellipse(
            [size // 4, size // 4, 3 * size // 4, 3 * size // 4],
            fill=(0, 212, 255)
        )

    icon.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
    icon.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
    print("Generated " + density + ": " + str(size) + "x" + str(size))

print("Icons done")
