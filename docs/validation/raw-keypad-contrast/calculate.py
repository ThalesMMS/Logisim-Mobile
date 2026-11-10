#!/usr/bin/env python3
"""Report contrast for the reviewed opaque key-label paints; not a native test."""
import json
from pathlib import Path

def luminance(argb):
    assert argb >> 24 == 255
    channels=[((argb >> shift) & 255) / 255 for shift in (16,8,0)]
    linear=[c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return sum(weight * value for weight,value in zip((0.2126,0.7152,0.0722),linear))

def ratio(foreground,background):
    values=sorted((luminance(foreground),luminance(background)))
    return (values[1] + 0.05) / (values[0] + 0.05)

PAINTS=[
    ("raw numeric, unpressed",0xFF00AADC,0xFFFFFFFF,0xFF000000,True),
    ("raw numeric, pressed",0xFF0078AA,0xFFFFFFFF,0xFFFFFFFF,False),
    ("raw function, unpressed",0xFFDC2828,0xFFFFFFFF,0xFFFFFFFF,False),
    ("raw function, pressed",0xFFAA1E1E,0xFFFFFFFF,0xFFFFFFFF,False),
    ("PMOD, unpressed",0xFF1E508C,0xFFFFFFFF,0xFFFFFFFF,False),
    ("PMOD, pressed",0xFF0F2D55,0xFFFFFFFF,0xFFFFFFFF,False),
]

def report():
    rows=[]
    for state,background,before,after,changed in PAINTS:
        a,b=ratio(before,background),ratio(after,background)
        rows.append({"state":state,"background_argb":f"0x{background:08X}","before_foreground_argb":f"0x{before:08X}","after_foreground_argb":f"0x{after:08X}","before_ratio":a,"after_ratio":b,"changed":changed,"role":"Meaningful key-label text; unpressed is not an inactive-control exemption.","benchmark_normal_text":4.5,"after_meets_normal_text":b>=4.5,"after_meets_large_text_3":b>=3})
    return {"algorithm":"Opaque integer sRGB channels/255; linearize at0.04045; luminance0.2126R+0.7152G+0.0722B; (Lmax+0.05)/(Lmin+0.05). Compare unrounded values.","rows":rows,"scope":"Normal and large keypad sizes share the same paint colors; circuit units/sp do not establish typographic pt. No native or pixel qualification inferred."}

if __name__=="__main__":
    print(json.dumps(report(),indent=2))
