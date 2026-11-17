"""Game-like load: full-screen random noise that changes every frame (high bitrate) with a moving bar
and a frame counter on top, so dropped or repeated frames are visible on the client.
usage: game.py <seconds>"""
import os
import sys
import time
import tkinter as tk

seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 60
W, H = 960, 540
root = tk.Tk()
root.attributes("-fullscreen", True)
root.attributes("-topmost", True)
sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
canvas = tk.Canvas(root, width=sw, height=sh, bg="black", highlightthickness=0)
canvas.pack()
# Noise in 2x2 pixel blocks: a busy game-like picture without the 2 MB+ IDR frames of per-pixel noise
frames = [tk.PhotoImage(data=b"P6 %d %d 255\n" % (W // 2, H // 2) + os.urandom(W * H * 3 // 4), format="PPM").zoom(2)
          for _ in range(6)]
items = [canvas.create_image(x, y, image=frames[0], anchor="nw") for x in range(0, sw, W) for y in range(0, sh, H)]
bar = canvas.create_rectangle(0, 0, 120, sh, fill="white", outline="")
box = canvas.create_rectangle(sw // 2 - 300, sh // 2 - 110, sw // 2 + 300, sh // 2 + 110, fill="black", outline="")
label = canvas.create_text(sw // 2, sh // 2, text="", fill="red", font=("Consolas", 120, "bold"))
start = time.perf_counter()
n = 0


def tick():
    global n
    if time.perf_counter() - start > seconds:
        root.destroy()
        return
    n += 1
    for i, it in enumerate(items):
        canvas.itemconfigure(it, image=frames[(n + i) % len(frames)])
    x = (n * 21) % (sw - 120)
    canvas.coords(bar, x, 0, x + 120, sh)
    canvas.tag_raise(bar)
    canvas.tag_raise(box)
    canvas.tag_raise(label)
    canvas.itemconfigure(label, text="%d" % n)
    if n % 120 == 1:
        root.lift()
        root.attributes("-topmost", True)
    root.after(8, tick)


tick()
root.mainloop()
