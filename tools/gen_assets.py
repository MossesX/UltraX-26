#!/usr/bin/env python3
"""Generates the built-in sticker / parallax vector drawables (flat cartoon style). Run: python3 tools/gen_assets.py"""
import math, os, sys
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
INK = "#1F2937"

def f(v): return ("%.1f" % v).rstrip("0").rstrip(".")
def ellipse(cx, cy, rx, ry, fill, stroke=None, sw=0, alpha=1.0):
    d = f"M{f(cx-rx)},{f(cy)} A{f(rx)},{f(ry)} 0 1 0 {f(cx+rx)},{f(cy)} A{f(rx)},{f(ry)} 0 1 0 {f(cx-rx)},{f(cy)} Z"
    return path(d, fill, stroke, sw, alpha)
def circle(cx, cy, r, fill, stroke=None, sw=0, alpha=1.0): return ellipse(cx, cy, r, r, fill, stroke, sw, alpha)
def rect(x, y, w, h, fill, rx=0, stroke=None, sw=0, alpha=1.0):
    if rx <= 0: return poly([(x,y),(x+w,y),(x+w,y+h),(x,y+h)], fill, stroke, sw, alpha)
    r = min(rx, w/2, h/2)
    d = (f"M{f(x+r)},{f(y)} L{f(x+w-r)},{f(y)} A{f(r)},{f(r)} 0 0 1 {f(x+w)},{f(y+r)} L{f(x+w)},{f(y+h-r)} A{f(r)},{f(r)} 0 0 1 {f(x+w-r)},{f(y+h)} "
         f"L{f(x+r)},{f(y+h)} A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y+h-r)} L{f(x)},{f(y+r)} A{f(r)},{f(r)} 0 0 1 {f(x+r)},{f(y)} Z")
    return path(d, fill, stroke, sw, alpha)
def poly(pts, fill, stroke=None, sw=0, alpha=1.0):
    d = "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts) + " Z"
    return path(d, fill, stroke, sw, alpha)
def path(d, fill, stroke=None, sw=0, alpha=1.0):
    s = f'    <path android:fillColor="{fill}"' if fill else '    <path android:fillColor="#00000000"'
    if stroke: s += f' android:strokeColor="{stroke}" android:strokeWidth="{f(sw)}" android:strokeLineJoin="round" android:strokeLineCap="round"'
    if alpha < 1.0: s += f' android:fillAlpha="{alpha}"'
    return s + f'\n        android:pathData="{d}" />'
def star(cx, cy, r_out, r_in, n, fill, stroke=None, sw=0, rot=-90):
    pts = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot + i * 180.0 / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts, fill, stroke, sw)
def heart(cx, cy, s, fill, stroke=None, sw=0):
    d = (f"M{f(cx)},{f(cy+s*0.9)} C{f(cx-s*1.6)},{f(cy-s*0.2)} {f(cx-s*0.9)},{f(cy-s*1.1)} {f(cx)},{f(cy-s*0.3)} "
         f"C{f(cx+s*0.9)},{f(cy-s*1.1)} {f(cx+s*1.6)},{f(cy-s*0.2)} {f(cx)},{f(cy+s*0.9)} Z")
    return path(d, fill, stroke, sw)
def write(name, w, h, body):
    xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           f'    android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{w}" android:viewportHeight="{h}">\n' + "\n".join(body) + "\n</vector>\n")
    with open(os.path.join(OUT, name + ".xml"), "w") as fp: fp.write(xml)

A = {}   # name -> (w, h, [paths])
def add(name, w, h, *paths): A[name] = (w, h, list(paths))

# ---------------- Headwear (200x200; brim near the bottom) ----------------
add("fx_cop_hat", 200, 200,
    poly([(30,150),(170,150),(160,90),(100,70),(40,90)], "#1E3A8A", INK, 4),
    rect(28,145,144,26,"#111827",6,INK,3), ellipse(100,182,86,14,"#0B0F19",INK,3),
    star(100,120,22,10,5,"#FBBF24",INK,2), rect(60,168,80,6,"#FBBF24",3))
add("fx_wizard_hat", 200, 200,
    path("M100,8 C120,60 150,100 165,150 L35,150 C60,100 80,50 100,8 Z", "#6D28D9", INK, 4),
    ellipse(100,160,92,20,"#4C1D95",INK,3), rect(40,142,120,18,"#F59E0B",5,INK,2), rect(88,140,24,22,"#FDE68A",3,INK,2),
    star(115,80,9,4,5,"#FDE68A"), star(85,110,7,3,5,"#FDE68A"), star(125,125,6,3,5,"#FDE68A"))
add("fx_crown", 200, 200,
    poly([(30,175),(170,175),(180,90),(140,125),(100,70),(60,125),(20,90)], "#F59E0B", INK, 4),
    rect(30,165,140,14,"#B45309",4), circle(100,80,10,"#EF4444",INK,2), circle(30,98,8,"#3B82F6",INK,2), circle(170,98,8,"#3B82F6",INK,2),
    circle(60,150,8,"#10B981",INK,2), circle(140,150,8,"#10B981",INK,2))
add("fx_top_hat", 200, 200,
    rect(48,20,104,130,"#111827",6,INK,4), ellipse(100,160,90,16,"#111827",INK,3), rect(48,120,104,22,"#B91C1C",3), rect(48,20,104,10,"#374151",4))
add("fx_party_hat", 200, 200,
    poly([(100,15),(160,180),(40,180)], "#EC4899", INK, 4), poly([(70,98),(130,98),(139,122),(61,122)], "#FDE047"),
    poly([(56,140),(144,140),(153,165),(47,165)], "#38BDF8"), circle(100,16,14,"#FDE047",INK,3), ellipse(100,182,62,10,"#BE185D",INK,2))
add("fx_cowboy_hat", 200, 200,
    path("M10,140 C40,175 160,175 190,140 C185,130 170,132 160,135 L150,80 C140,60 60,60 50,80 L40,135 C30,132 15,130 10,140 Z", "#92400E", INK, 4),
    path("M50,80 C70,70 130,70 150,80 L152,92 C130,100 70,100 48,92 Z", "#78350F"), rect(48,118,104,12,"#3F2A14",3), path("M95,62 L105,62 L108,95 L92,95 Z", "#78350F"))
add("fx_santa_hat", 200, 200,
    path("M40,150 C40,80 90,20 170,40 C120,60 110,110 130,150 Z", "#DC2626", INK, 4), circle(172,42,18,"#FFFFFF",INK,3),
    rect(30,140,120,34,"#FFFFFF",14,INK,3))
add("fx_chef_hat", 200, 200,
    path("M45,150 C10,150 10,90 45,90 C40,50 90,30 105,60 C130,25 190,60 165,95 C195,100 190,150 155,150 Z", "#FFFFFF", INK, 4),
    rect(45,140,110,38,"#F3F4F6",6,INK,3), rect(60,150,80,4,"#D1D5DB"))
add("fx_viking_helmet", 200, 200,
    path("M40,160 C40,60 160,60 160,160 Z", "#6B7280", INK, 4), rect(38,150,124,24,"#4B5563",6,INK,3), rect(96,70,8,90,"#9CA3AF"),
    path("M40,110 C10,100 0,60 15,30 C25,60 30,90 55,100 Z", "#FDE68A", INK, 3), path("M160,110 C190,100 200,60 185,30 C175,60 170,90 145,100 Z", "#FDE68A", INK, 3),
    circle(70,135,5,"#D1D5DB"), circle(130,135,5,"#D1D5DB"))
add("fx_pirate_hat", 200, 200,
    path("M10,150 C30,110 60,80 100,75 C140,80 170,110 190,150 C150,135 120,130 100,132 C80,130 50,135 10,150 Z", "#111827", INK, 4),
    path("M30,150 L170,150 L160,175 L40,175 Z", "#111827", INK, 3), rect(40,158,120,8,"#F59E0B"),
    circle(100,110,16,"#FFFFFF",INK,2), circle(93,107,4,INK), circle(107,107,4,INK), rect(96,116,8,6,INK), path("M78,130 L122,142 M122,130 L78,142", None, "#FFFFFF", 6))
add("fx_grad_cap", 200, 200,
    poly([(100,60),(195,100),(100,140),(5,100)], "#111827", INK, 4), rect(60,120,80,45,"#1F2937",6,INK,3),
    path("M100,100 L150,110 L150,150", None, "#F59E0B", 5), circle(150,155,8,"#F59E0B"))
add("fx_beanie", 200, 200,
    path("M35,150 C35,70 165,70 165,150 Z", "#DC2626", INK, 4), rect(30,140,140,36,"#B91C1C",8,INK,3),
    circle(100,66,18,"#FCA5A5",INK,3), path("M60,90 L60,140 M80,80 L80,140 M100,76 L100,140 M120,80 L120,140 M140,90 L140,140", None, "#991B1B", 4))
add("fx_tiara", 200, 200,
    path("M20,160 C60,140 80,110 100,70 C120,110 140,140 180,160 C150,150 120,145 100,145 C80,145 50,150 20,160 Z", "#E5E7EB", INK, 3),
    circle(100,78,12,"#A78BFA",INK,2), circle(65,120,7,"#F9A8D4",INK,2), circle(135,120,7,"#F9A8D4",INK,2))
add("fx_halo", 200, 200,
    ellipse(100,100,90,34,"#FDE047",None,0,0.35), ellipse(100,100,80,28,"#FDE047","#F59E0B",6), ellipse(100,100,62,17,"#00000000","#FFFFFF",3))
add("fx_devil_horns", 200, 200,
    path("M40,190 C20,150 25,100 50,70 C50,120 60,150 75,185 Z", "#DC2626", INK, 4), path("M160,190 C180,150 175,100 150,70 C150,120 140,150 125,185 Z", "#DC2626", INK, 4))
add("fx_bunny_ears", 200, 200,
    ellipse(62,95,28,88,"#FFFFFF",INK,4), ellipse(62,100,14,62,"#F9A8D4"), ellipse(138,95,28,88,"#FFFFFF",INK,4), ellipse(138,100,14,62,"#F9A8D4"))
add("fx_cat_ears", 200, 200,
    poly([(20,190),(35,60),(95,150)], "#6B7280", INK, 4), poly([(38,170),(45,95),(80,145)], "#F9A8D4"),
    poly([(180,190),(165,60),(105,150)], "#6B7280", INK, 4), poly([(162,170),(155,95),(120,145)], "#F9A8D4"))
add("fx_dog_ears", 200, 200,
    path("M45,40 C10,60 5,140 30,190 C55,170 70,120 60,45 Z", "#92400E", INK, 4), path("M155,40 C190,60 195,140 170,190 C145,170 130,120 140,45 Z", "#92400E", INK, 4))
add("fx_headphones", 200, 200,
    path("M35,120 C35,40 165,40 165,120", None, INK, 14), rect(15,105,44,70,"#374151",14,INK,4), rect(141,105,44,70,"#374151",14,INK,4),
    rect(25,118,24,44,"#EF4444",8), rect(151,118,24,44,"#EF4444",8))
add("fx_propeller_cap", 200, 200,
    path("M35,150 C35,80 165,80 165,150 Z", "#3B82F6", INK, 4), path("M100,80 C120,80 150,100 160,150 L100,150 Z", "#EF4444"),
    path("M40,110 C60,90 90,82 100,80 L100,150 L36,150 Z", "#FDE047"), ellipse(100,156,72,14,"#1E40AF",INK,3),
    rect(96,40,8,40,"#6B7280"), path("M30,40 L170,44 L170,52 L30,48 Z", "#EF4444", INK, 3), circle(100,46,8,"#FDE047",INK,2))
add("fx_alien_antennae", 200, 200,
    path("M70,190 C60,140 30,110 25,60", None, "#22C55E", 10), path("M130,190 C140,140 170,110 175,60", None, "#22C55E", 10),
    circle(25,52,16,"#86EFAC",INK,3), circle(175,52,16,"#86EFAC",INK,3))
add("fx_flower_crown", 200, 200,
    path("M10,130 C60,100 140,100 190,130", None, "#15803D", 10),
    *[circle(x, y, 14, c, INK, 2) for (x, y, c) in [(30,128,"#F472B6"),(65,110,"#FDE047"),(100,102,"#FB7185"),(135,110,"#A78BFA"),(170,128,"#F472B6")]],
    *[circle(x, y, 5, "#FDE68A") for (x, y) in [(30,128),(65,110),(100,102),(135,110),(170,128)]])
add("fx_ninja_band", 200, 200,
    rect(10,90,180,34,"#111827",6,INK,3), rect(70,96,60,22,"#9CA3AF",4,INK,2), path("M20,100 C5,130 0,160 8,190 L22,188 C16,160 22,130 30,110 Z", "#111827", INK, 3))
add("fx_sparkles", 200, 200,
    star(60,60,26,9,4,"#FDE047",None,0), star(140,50,18,6,4,"#FDE047",None,0), star(160,130,24,8,4,"#FDE047",None,0), star(50,140,16,5,4,"#FFFFFF",None,0), star(105,110,12,4,4,"#FFFFFF",None,0))

# ---------------- Eyewear (240x100, aspect 2.4; eyes at y=50, centers x=70 and x=170) ----------------
add("fx_aviators", 240, 100,
    path("M22,38 C22,80 60,90 78,70 C88,58 92,45 118,40 L122,40 C148,45 152,58 162,70 C180,90 218,80 218,38 L200,30 L40,30 Z", "#FBBF24", INK, 3),
    path("M30,40 C32,72 62,82 76,64 C84,54 88,46 112,44 Z", "#1E3A8A", None, 0, 0.85), path("M210,40 C208,72 178,82 164,64 C156,54 152,46 128,44 Z", "#1E3A8A", None, 0, 0.85),
    path("M0,34 L26,34 M214,34 L240,34", None, INK, 5))
add("fx_nerd_glasses", 240, 100,
    rect(18,22,90,60,"#111827",16), rect(28,30,70,44,"#93C5FD",10,None,0,0.55), rect(132,22,90,60,"#111827",16), rect(142,30,70,44,"#93C5FD",10,None,0,0.55),
    rect(104,44,32,10,"#111827",3), path("M0,40 L20,40 M220,40 L240,40", None, INK, 8))
add("fx_heart_glasses", 240, 100,
    heart(70,50,34,"#EC4899",INK,3), heart(170,50,34,"#EC4899",INK,3), heart(70,52,24,"#FBCFE8",None,0), heart(170,52,24,"#FBCFE8",None,0),
    rect(100,44,40,8,"#EC4899",3), path("M0,40 L34,40 M206,40 L240,40", None, INK, 6))
add("fx_star_glasses", 240, 100,
    star(70,50,44,20,5,"#FDE047",INK,3), star(170,50,44,20,5,"#FDE047",INK,3), star(70,52,30,13,5,"#FEF3C7"), star(170,52,30,13,5,"#FEF3C7"),
    rect(100,46,40,8,"#F59E0B",3), path("M0,40 L30,40 M210,40 L240,40", None, INK, 6))
add("fx_pixel_shades", 240, 100,
    rect(10,30,220,10,"#111827"), rect(20,40,90,10,"#111827"), rect(30,50,70,10,"#111827"), rect(40,60,50,10,"#111827"), rect(50,70,30,10,"#111827"),
    rect(130,40,90,10,"#111827"), rect(140,50,70,10,"#111827"), rect(150,60,50,10,"#111827"), rect(160,70,30,10,"#111827"), rect(0,30,10,10,"#111827"), rect(230,30,10,10,"#111827"))
add("fx_monocle", 100, 100,
    circle(50,45,36,"#93C5FD",None,0,0.5), circle(50,45,36,"#00000000","#F59E0B",7), path("M78,70 C90,80 92,95 85,100", None, "#F59E0B", 4))
add("fx_eye_patch", 100, 100,
    path("M0,30 L100,22", None, INK, 6), ellipse(50,55,34,30,"#111827",INK,3), ellipse(42,48,10,6,"#374151"))
add("fx_cyborg_eye", 100, 100,
    circle(50,50,42,"#374151",INK,3), circle(50,50,30,"#7F1D1D"), circle(50,50,18,"#EF4444"), circle(50,50,8,"#FCA5A5"), path("M50,8 L50,20 M50,80 L50,92 M8,50 L20,50 M80,50 L92,50", None, "#EF4444", 4))
add("fx_vr_headset", 240, 100,
    rect(10,14,220,72,"#F3F4F6",26,INK,4), rect(24,26,192,48,"#111827",18), rect(40,36,60,28,"#1E40AF",8), rect(140,36,60,28,"#1E40AF",8),
    path("M0,50 L12,50 M228,50 L240,50", None, INK, 10))
add("fx_alien_eyes", 240, 100,
    path("M14,54 C30,20 100,10 108,44 C110,70 60,96 14,54 Z", "#111827"), path("M226,54 C210,20 140,10 132,44 C130,70 180,96 226,54 Z", "#111827"),
    ellipse(52,40,10,5,"#FFFFFF",None,0,0.6), ellipse(188,40,10,5,"#FFFFFF",None,0,0.6))
add("fx_hero_mask", 240, 100,
    path("M10,40 C40,10 90,10 118,30 L122,30 C150,10 200,10 230,40 C230,75 200,92 168,84 C150,80 135,70 120,58 C105,70 90,80 72,84 C40,92 10,75 10,40 Z", "#111827", INK, 3),
    path("M40,42 C56,30 84,32 96,48 C84,60 56,60 40,42 Z", "#FFFFFF"), path("M200,42 C184,30 156,32 144,48 C156,60 184,60 200,42 Z", "#FFFFFF"))

# ---------------- Facial hair ----------------
add("fx_mustache_handlebar", 240, 100,
    path("M120,48 C100,20 60,20 40,45 C30,58 10,60 4,44 C2,60 20,74 40,66 C70,56 90,66 120,60 C150,66 170,56 200,66 C220,74 238,60 236,44 C230,60 210,58 200,45 C180,20 140,20 120,48 Z", "#111827", None, 0))
add("fx_mustache_chevron", 240, 100,
    path("M120,30 C90,22 60,32 30,70 C60,60 90,62 120,58 C150,62 180,60 210,70 C180,32 150,22 120,30 Z", "#78350F", INK, 2))
add("fx_goatee", 200, 200,
    path("M60,60 C60,140 90,190 100,195 C110,190 140,140 140,60 C120,80 80,80 60,60 Z", "#78350F", INK, 3))
add("fx_beard_full", 200, 200,
    path("M20,20 C20,120 50,190 100,198 C150,190 180,120 180,20 C160,50 150,60 120,60 L80,60 C50,60 40,50 20,20 Z", "#92400E", INK, 4),
    path("M70,75 C85,95 115,95 130,75 C120,70 80,70 70,75 Z", "#F9A8D4"))
add("fx_beard_wizard", 200, 200,
    path("M30,10 C20,80 30,160 100,198 C170,160 180,80 170,10 C150,40 120,50 100,52 C80,50 50,40 30,10 Z", "#F3F4F6", INK, 4),
    path("M60,110 L60,150 M100,120 L100,180 M140,110 L140,150", None, "#D1D5DB", 6), path("M70,40 C85,60 115,60 130,40 C118,36 82,36 70,40 Z", "#F9A8D4"))
add("fx_beard_pirate", 200, 200,
    path("M25,15 C20,100 40,170 100,195 C160,170 180,100 175,15 C150,50 130,58 100,58 C70,58 50,50 25,15 Z", "#111827", INK, 4),
    circle(70,170,6,"#F59E0B"), circle(130,170,6,"#F59E0B"), circle(100,190,6,"#EF4444"), path("M72,40 C86,58 114,58 128,40 C116,36 84,36 72,40 Z", "#F9A8D4"))

# ---------------- Faces & noses ----------------
add("fx_clown_nose", 200, 200, circle(100,100,80,"#DC2626",INK,4), ellipse(70,70,22,14,"#FCA5A5",None,0,0.8))
add("fx_pig_nose", 200, 200, ellipse(100,100,90,62,"#F9A8D4",INK,4), ellipse(65,100,18,26,"#BE185D"), ellipse(135,100,18,26,"#BE185D"))
add("fx_dog_nose", 200, 200,
    path("M40,40 C40,10 160,10 160,40 C160,70 120,95 100,100 C80,95 40,70 40,40 Z", "#111827"), ellipse(75,35,14,8,"#4B5563",None,0,0.7),
    path("M100,100 L100,120 C100,150 70,165 60,150 C80,150 90,140 90,120 L110,120 C110,140 120,150 140,150 C130,165 100,150 100,120", None, INK, 6),
    path("M78,125 C78,190 122,190 122,125 Z", "#F472B6", INK, 3))
add("fx_cat_nose", 240, 100,
    poly([(108,40),(132,40),(120,60)], "#F472B6", INK, 3), path("M120,60 L120,72 M120,72 C112,84 100,84 96,78 M120,72 C128,84 140,84 144,78", None, INK, 4),
    path("M96,50 L6,30 M96,60 L4,60 M96,70 L10,92 M144,50 L234,30 M144,60 L236,60 M144,70 L230,92", None, "#F3F4F6", 4),
    path("M96,50 L6,30 M96,60 L4,60 M96,70 L10,92 M144,50 L234,30 M144,60 L236,60 M144,70 L230,92", None, INK, 1.5))
add("fx_skull_mask", 200, 200,
    path("M100,8 C170,8 190,70 180,120 C176,150 160,165 140,168 L135,196 L65,196 L60,168 C40,165 24,150 20,120 C10,70 30,8 100,8 Z", "#F3F4F6", INK, 4),
    ellipse(66,100,24,28,"#111827"), ellipse(134,100,24,28,"#111827"), poly([(100,120),(112,150),(88,150)], "#111827"),
    path("M72,172 L72,194 M86,170 L86,196 M100,170 L100,196 M114,170 L114,196 M128,172 L128,194", None, INK, 4))
add("fx_robot_faceplate", 200, 200,
    rect(20,10,160,185,"#9CA3AF",28,INK,4,0.92), rect(34,50,132,44,"#0F172A",14,INK,3), rect(44,60,112,24,"#22D3EE",8,None,0,0.9),
    rect(56,120,88,30,"#4B5563",8,INK,2), path("M70,135 L130,135", None, "#22D3EE", 5),
    *[circle(x, y, 5, "#4B5563", INK, 1.5) for (x, y) in [(32,24),(168,24),(32,180),(168,180),(32,102),(168,102)]],
    rect(80,160,40,22,"#374151",6,INK,2), path("M88,171 L112,171", None, "#EF4444", 4))
add("fx_astronaut_helmet", 200, 200,
    circle(100,100,96,"#F3F4F6",INK,3,0.95), circle(100,100,80,"#DBEAFE",None,0,0.28), circle(100,100,80,"#00000000","#F3F4F6",8),
    path("M40,60 C55,30 85,20 110,24", None, "#FFFFFF", 6), rect(20,150,160,40,"#E5E7EB",12,INK,3,0.95), rect(60,160,80,20,"#1D4ED8",6), circle(100,170,6,"#EF4444"))
add("fx_vampire_fangs", 200, 200,
    path("M40,30 C60,10 140,10 160,30 L150,50 L50,50 Z", "#FFFFFF", INK, 3), poly([(58,50),(78,50),(70,110)], "#FFFFFF", INK, 3), poly([(122,50),(142,50),(130,110)], "#FFFFFF", INK, 3),
    path("M62,100 L74,112 L70,120 Z", "#DC2626"))
add("fx_bandit_mask", 240, 100,
    path("M0,10 C60,0 180,0 240,10 L240,40 C200,70 160,100 120,100 C80,100 40,70 0,40 Z", "#DC2626", INK, 3),
    *[circle(x, y, 5, "#FFFFFF") for (x, y) in [(40,30),(80,20),(120,18),(160,20),(200,30),(60,52),(120,58),(180,52)]])

# ---------------- Costume pieces ----------------
add("fx_bow_tie", 240, 100,
    path("M120,50 L20,10 C10,10 10,90 20,90 Z", "#DC2626", INK, 3), path("M120,50 L220,10 C230,10 230,90 220,90 Z", "#DC2626", INK, 3), rect(100,30,40,40,"#B91C1C",10,INK,3))
add("fx_neck_tie", 80, 200,
    poly([(20,0),(60,0),(52,28),(28,28)], "#1E3A8A", INK, 3), poly([(28,28),(52,28),(72,150),(40,196),(8,150)], "#1E3A8A", INK, 3), path("M30,60 L60,100 M22,110 L50,150", None, "#3B82F6", 4))
add("fx_police_badge", 200, 200,
    star(100,100,92,62,7,"#F59E0B",INK,4), circle(100,100,54,"#FDE68A",INK,3), circle(100,100,40,"#1E3A8A"), star(100,100,22,9,5,"#FDE68A"), rect(60,150,80,16,"#1E3A8A",4,INK,2))
add("fx_stethoscope", 200, 200,
    path("M60,10 C60,80 100,100 100,120 M140,10 C140,80 100,100 100,120 M100,120 L100,150 C100,170 130,175 150,165", None, "#374151", 8),
    circle(60,10,10,"#9CA3AF",INK,3), circle(140,10,10,"#9CA3AF",INK,3), circle(160,170,24,"#9CA3AF",INK,4), circle(160,170,14,"#4B5563"))
add("fx_astronaut_collar", 240, 100,
    path("M0,100 C10,40 60,10 120,10 C180,10 230,40 240,100 Z", "#F3F4F6", INK, 3), path("M40,100 C50,60 80,42 120,42 C160,42 190,60 200,100 Z", "#E5E7EB", INK, 2),
    rect(0,60,50,14,"#1D4ED8",4), rect(190,60,50,14,"#DC2626",4), circle(40,30,12,"#1D4ED8",INK,2), circle(200,30,12,"#DC2626",INK,2), rect(96,12,48,10,"#9CA3AF",3))
add("fx_hero_logo", 200, 200,
    path("M100,10 L180,40 C180,120 140,170 100,190 C60,170 20,120 20,40 Z", "#DC2626", INK, 4), path("M100,30 L164,54 C164,116 132,158 100,172 C68,158 36,116 36,54 Z", "#FDE047"),
    path("M80,60 L120,60 L104,100 L124,100 L76,150 L88,110 L70,110 Z", "#DC2626"))
add("fx_lei", 200, 200,
    path("M20,40 C40,120 80,160 100,165 C120,160 160,120 180,40", None, "#15803D", 10),
    *[circle(x, y, 13, c, INK, 2) for (x, y, c) in [(24,48,"#F472B6"),(40,90,"#FDE047"),(62,125,"#FB7185"),(90,155,"#A78BFA"),(120,158,"#F472B6"),(146,130,"#FDE047"),(166,92,"#FB7185"),(178,50,"#A78BFA")]])
add("fx_scarf", 200, 200,
    path("M20,40 C60,90 140,90 180,40 L185,70 C140,120 60,120 15,70 Z", "#DC2626", INK, 3), path("M60,100 L40,190 L75,185 L85,110 Z", "#DC2626", INK, 3),
    path("M25,50 C60,90 140,90 175,50", None, "#FFFFFF", 5), path("M60,160 L75,160", None, "#FFFFFF", 4))
add("fx_gold_chain", 200, 200,
    path("M10,20 C30,110 80,150 100,150 C120,150 170,110 190,20", None, "#F59E0B", 10), path("M10,20 C30,110 80,150 100,150 C120,150 170,110 190,20", None, "#FDE68A", 3),
    path("M100,150 L100,175", None, "#F59E0B", 6), circle(100,185,14,"#F59E0B",INK,2), circle(100,185,6,"#FDE68A"))
add("fx_lab_coat_collar", 240, 100,
    path("M0,100 C10,50 50,20 120,20 C190,20 230,50 240,100 Z", "#FFFFFF", INK, 3), path("M120,20 L80,100 L100,100 L120,45 L140,100 L160,100 Z", "#93C5FD", INK, 2),
    rect(30,70,40,14,"#DBEAFE",3,INK,1.5), circle(120,60,4,INK))

# ---------------- Behind the person ----------------
add("fx_cape", 200, 200,
    path("M60,10 C30,60 15,130 10,195 L190,195 C185,130 170,60 140,10 C120,40 80,40 60,10 Z", "#DC2626", INK, 4), path("M75,20 C60,80 45,140 40,195 M125,20 C140,80 155,140 160,195", None, "#991B1B", 4))
add("fx_angel_wings", 240, 100,
    path("M120,90 C90,90 40,70 8,20 C30,15 50,25 60,40 C55,20 70,5 90,10 C95,25 100,40 105,50 C110,30 118,15 120,10 Z", "#FFFFFF", INK, 3),
    path("M120,90 C150,90 200,70 232,20 C210,15 190,25 180,40 C185,20 170,5 150,10 C145,25 140,40 135,50 C130,30 122,15 120,10 Z", "#FFFFFF", INK, 3),
    path("M20,30 C50,45 80,60 118,80 M220,30 C190,45 160,60 122,80", None, "#E5E7EB", 3))
add("fx_bat_wings", 240, 100,
    path("M120,90 C90,95 40,80 4,30 L30,40 L20,10 L55,30 L60,5 L90,35 L100,10 L120,50 Z", "#111827", INK, 3),
    path("M120,90 C150,95 200,80 236,30 L210,40 L220,10 L185,30 L180,5 L150,35 L140,10 L120,50 Z", "#111827", INK, 3), path("M30,40 C70,60 100,75 118,82 M210,40 C170,60 140,75 122,82", None, "#374151", 3))
add("fx_sun_rays", 200, 200, *[poly([(100 + 30*math.cos(math.radians(a-6)), 100 + 30*math.sin(math.radians(a-6))), (100 + 98*math.cos(math.radians(a)), 100 + 98*math.sin(math.radians(a))), (100 + 30*math.cos(math.radians(a+6)), 100 + 30*math.sin(math.radians(a+6)))], "#FDE047", None, 0, 0.85) for a in range(0, 360, 30)])

# ---------------- Accessories ----------------
add("fx_earring_hoop", 100, 100, circle(50,50,38,"#00000000","#F59E0B",9), circle(50,50,38,"#00000000","#FDE68A",3))
add("fx_speech_bubble", 200, 200,
    path("M20,20 L180,20 C190,20 190,30 190,30 L190,120 C190,130 180,130 180,130 L70,130 L30,175 L40,130 L20,130 C10,130 10,120 10,120 L10,30 C10,20 20,20 20,20 Z", "#FFFFFF", INK, 4),
    circle(65,75,10,INK), circle(100,75,10,INK), circle(135,75,10,INK))
add("fx_thought_bubble", 200, 200,
    ellipse(110,70,80,55,"#FFFFFF",INK,4), circle(55,140,16,"#FFFFFF",INK,3), circle(30,172,10,"#FFFFFF",INK,3), circle(80,70,8,INK), circle(110,70,8,INK), circle(140,70,8,INK))
add("fx_butterfly", 200, 200,
    path("M100,100 C60,40 10,40 20,90 C30,130 70,120 100,100 Z", "#F97316", INK, 3), path("M100,100 C140,40 190,40 180,90 C170,130 130,120 100,100 Z", "#F97316", INK, 3),
    path("M100,100 C70,130 30,150 40,170 C50,185 85,150 100,100 Z", "#FB923C", INK, 3), path("M100,100 C130,130 170,150 160,170 C150,185 115,150 100,100 Z", "#FB923C", INK, 3),
    rect(95,60,10,90,"#111827",5), path("M100,60 L85,40 M100,60 L115,40", None, INK, 4), circle(55,85,8,"#FDE047"), circle(145,85,8,"#FDE047"))
add("fx_hearts", 200, 200, heart(50,70,22,"#EF4444",INK,2), heart(120,40,16,"#F472B6",INK,2), heart(160,110,20,"#EF4444",INK,2), heart(90,140,14,"#F472B6",INK,2))
add("fx_rec_badge", 240, 100, rect(4,10,232,80,"#111827",22,None,0,0.75), circle(44,50,18,"#EF4444"),
    path("M80,30 L80,70 M80,30 L100,30 C114,30 114,50 100,50 L80,50 M100,50 L114,70 M128,30 L128,70 L160,70 M128,30 L160,30 M128,50 L152,50 M204,30 L184,30 C170,30 170,70 184,70 L204,70", None, "#FFFFFF", 9))
add("fx_live_badge", 240, 100, rect(4,10,232,80,"#DC2626",22,None,0,0.9),
    path("M40,30 L40,70 L70,70 M90,30 L90,70 M110,30 L128,70 L146,30 M170,30 L170,70 L200,70 M170,30 L200,30 M170,50 L192,50", None, "#FFFFFF", 9))
add("fx_film_frame", 200, 200,
    path("M10,50 L10,10 L50,10 M150,10 L190,10 L190,50 M190,150 L190,190 L150,190 M50,190 L10,190 L10,150", None, "#FFFFFF", 6))

# ---------------- Parallax scene layers (320x180) ----------------
def sky(c1, c2, extra=()):
    return [rect(0,0,320,180,c1), rect(0,90,320,90,c2, 0, None, 0, 0.6), *extra]
add("bg_mountains_far", 320, 180, *sky("#7DD3FC", "#BAE6FD"), circle(250,45,22,"#FDE68A"), path("M0,140 L40,90 L80,120 L120,70 L170,130 L210,85 L250,120 L290,95 L320,130 L320,180 L0,180 Z", "#93C5FD"))
add("bg_mountains_mid", 320, 180, path("M0,150 L50,95 L90,135 L140,80 L190,140 L240,100 L290,140 L320,120 L320,180 L0,180 Z", "#475569"), path("M140,80 L128,100 L152,100 Z", "#F8FAFC"), path("M50,95 L40,108 L60,108 Z", "#F8FAFC"))
add("bg_mountains_near", 320, 180, path("M0,160 C60,140 120,150 180,140 C240,130 290,150 320,145 L320,180 L0,180 Z", "#166534"),
    *[poly([(x, 178), (x+14, 178), (x+7, 130)], "#14532D") for x in range(-6, 330, 28)], rect(0,168,320,12,"#0EA5E9",0,None,0,0.7))
add("bg_city_sky", 320, 180, rect(0,0,320,180,"#0B1026"), *[circle((i*53)%320, (i*37)%110, 1.2, "#FFFFFF") for i in range(40)], circle(260,40,20,"#FDE68A"), circle(252,34,14,"#0B1026",None,0,0.0))
add("bg_city_far", 320, 180, *[rect(x, 180-h, w, h, "#1E293B") for (x,w,h) in [(0,30,80),(34,22,110),(60,40,70),(104,18,130),(126,36,95),(166,26,120),(196,44,80),(244,30,140),(278,42,90)]],
    *[rect(x, y, 4, 5, "#FDE047", 0, None, 0, 0.8) for x in range(6, 316, 16) for y in range(70, 170, 18) if (x*7+y) % 5 == 0])
add("bg_city_near", 320, 180, *[rect(x, 180-h, w, h, "#0F172A") for (x,w,h) in [(-10,60,60),(54,40,40),(120,70,55),(200,50,35),(260,70,65)]],
    *[rect(x, 180-h+8, 6, 8, "#38BDF8", 0, None, 0, 0.9) for (x,h) in [(4,60),(20,60),(36,60),(130,55),(150,55),(170,55),(270,65),(290,65),(310,65)]], rect(0,174,320,6,"#020617"))
add("bg_space_stars", 320, 180, rect(0,0,320,180,"#02040F"), *[circle((i*61)%320, (i*43)%180, 0.8 + (i%3)*0.5, "#FFFFFF") for i in range(90)])
add("bg_space_planet", 320, 180, circle(230,60,46,"#F97316"), ellipse(230,60,70,12,"#FDE68A",None,0,0.7), circle(215,50,10,"#FB923C"), circle(70,130,16,"#94A3B8"), circle(64,124,4,"#64748B"))
add("bg_space_window", 320, 180, path("M0,0 L320,0 L320,180 L0,180 Z M30,20 C30,10 40,8 50,8 L270,8 C280,8 290,10 290,20 L290,160 C290,170 280,172 270,172 L50,172 C40,172 30,170 30,160 Z", "#334155"),
    rect(0,0,320,180,"#00000000",0,"#1E293B",12), *[circle(x, y, 3, "#0F172A") for (x, y) in [(16,16),(304,16),(16,164),(304,164)]], rect(120,2,80,6,"#22D3EE",3), rect(40,168,60,6,"#EF4444",3,None,0,0.8))
add("bg_library_wall", 320, 180, rect(0,0,320,180,"#78350F"), *[rect(0, y, 320, 3, "#5B2A0E") for y in range(0,180,36)], rect(0,150,320,30,"#3F1D0B"))
add("bg_library_shelves", 320, 180,
    *[rect(x, 20 + s*44, 6 + (i%3)*3, 38, c) for s in range(3) for i, (x, c) in enumerate([(20+i*13, ["#B91C1C","#1D4ED8","#15803D","#F59E0B","#7C3AED","#0E7490","#BE185D","#4B5563"][i % 8]) for i in range(22)])],
    *[rect(0, 58 + s*44, 320, 5, "#451A03") for s in range(3)])
add("bg_library_desk", 320, 180, rect(0,140,320,40,"#92400E"), rect(0,138,320,6,"#B45309"), rect(40,120,60,20,"#1E3A8A",2), rect(44,116,52,6,"#F8FAFC",1), circle(260,110,12,"#FDE68A"), rect(254,120,12,20,"#374151",2))
add("bg_beach_sky", 320, 180, rect(0,0,320,180,"#7DD3FC"), rect(0,60,320,60,"#FDE68A",0,None,0,0.35), circle(70,50,24,"#FDE047"), ellipse(200,40,40,10,"#FFFFFF",None,0,0.9), ellipse(250,60,30,8,"#FFFFFF",None,0,0.8))
add("bg_beach_sea", 320, 180, rect(0,100,320,80,"#0EA5E9"), *[path(f"M0,{110+i*14} C40,{104+i*14} 80,{116+i*14} 120,{110+i*14} C160,{104+i*14} 200,{116+i*14} 240,{110+i*14} C280,{104+i*14} 300,{116+i*14} 320,{110+i*14}", None, "#BAE6FD", 2, 0.7) for i in range(4)], path("M0,150 C100,140 220,160 320,150 L320,180 L0,180 Z", "#FDE68A"))
add("bg_beach_palm", 320, 180, path("M40,180 C50,120 60,80 80,40", None, "#78350F", 10),
    *[path(f"M80,40 C{80+dx},{40+dy} {80+dx*1.8},{40+dy*1.2} {80+dx*2.4},{40+dy*2.2}", None, "#15803D", 9) for (dx, dy) in [(30,-10),(35,10),(20,25),(-25,-8),(-30,12),(-15,25)]], circle(84,48,7,"#92400E"), circle(74,50,6,"#92400E"))
add("bg_office_wall", 320, 180, rect(0,0,320,180,"#E2E8F0"), rect(60,20,200,90,"#BAE6FD",4,"#94A3B8",3), rect(0,120,320,60,"#CBD5E1"), *[rect(x, 24, 4, 82, "#94A3B8") for x in (126, 194)])
add("bg_office_mid", 320, 180, rect(20,70,90,60,"#94A3B8",4), rect(28,76,74,44,"#0F172A",3), rect(230,60,60,120,"#64748B",4), *[rect(236, y, 48, 4, "#334155") for y in range(70,170,20)], circle(200,100,18,"#22C55E"), rect(196,118,8,30,"#78350F"))
add("bg_office_desk", 320, 180, rect(0,140,320,40,"#F1F5F9"), rect(0,136,320,8,"#CBD5E1"), rect(120,110,80,30,"#1E293B",3), rect(126,116,68,20,"#38BDF8",2), rect(60,126,30,12,"#F59E0B",2), rect(240,124,20,14,"#FFFFFF",2,"#94A3B8",2))

os.makedirs(OUT, exist_ok=True)
for name, (w, h, body) in A.items(): write(name, w, h, body)
print(f"wrote {len(A)} drawables to {OUT}")
