import importlib.util
from pathlib import Path
import runpy
import struct
import sys
import tempfile
import unittest
import zlib

SPEC = importlib.util.spec_from_file_location("geometry", Path(__file__).parents[1] / "android_layout12_geometry.py")
geometry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(geometry)
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
decode_png = runpy.run_path(str(ROOT / 'scripts/test-android-assets.py'))['decode_android_png']


def png(width, height, pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(b''.join(b'\0' + pixels[y * width * 4:(y + 1) * width * 4]
                                              for y in range(height)))) + chunk(b'IEND', b''))


def scene(scale=1, pan=(20, 140), canvas=(20, 100, 420, 500), *, mutate=None, anisotropy=(1, 1)):
    width, height = 700, 600
    pixels = bytearray([239, 239, 239, 255] * width * height)
    rects = []
    for marker in geometry.MARKERS:
        box = marker['world']
        rect = tuple(round(box[c % 2] * scale * anisotropy[c % 2] + pan[c % 2]) if c < 2 else
                     round(box[c] * scale * anisotropy[c % 2] + pan[c % 2]) for c in range(4))
        rects.append(rect)
        for y in range(max(0, rect[1]), min(height, rect[3])):
            for x in range(max(0, rect[0]), min(width, rect[2])):
                start = (y * width + x) * 4
                pixels[start:start + 4] = bytes((*marker['rgb'], 255))
    if mutate:
        mutate(pixels, width, height, rects)
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'synthetic.png'
        path.write_bytes(png(width, height, pixels))
        return decode_png(path), canvas


def paint(pixels, width, rectangle, color=(239, 239, 239, 255)):
    for y in range(rectangle[1], rectangle[3]):
        for x in range(rectangle[0], rectangle[2]):
            start = (y * width + x) * 4
            pixels[start:start + 4] = bytes(color)


class PaintedGeometryTests(unittest.TestCase):
    def observe(self, *args, **kwargs):
        return geometry.measure_viewport(*scene(*args, **kwargs))

    def test_known_literal_transform(self):
        result = self.observe()
        self.assertEqual(1, result['scalePixelsPerWorldUnit'])
        self.assertEqual((20, 140), result['globalPanPixels'])
        self.assertEqual((200, 160), result['worldCenter'])
        self.assertEqual((120, 240, 152, 264), result['markers'][0]['paintedBounds'])

    def test_same_world_center_on_resized_canvas(self):
        before = self.observe()
        after = self.observe(pan=(120, 40), canvas=(20, 40, 620, 360))
        self.assertEqual((200, 160), after['worldCenter'])
        self.assertEqual('PASS', geometry.compare_viewports(before, after)['status'])

    def test_half_scale_is_observed_from_painted_shapes(self):
        result = self.observe(scale=.5, pan=(120, 220))
        self.assertEqual(.5, result['scalePixelsPerWorldUnit'])
        self.assertEqual((200, 160), result['worldCenter'])

    def test_missing_second_landmark_fails(self):
        with self.assertRaisesRegex(AssertionError, 'absent'):
            self.observe(mutate=lambda p,w,h,r: paint(p, w, r[1]))

    def test_duplicate_color_region_fails(self):
        with self.assertRaisesRegex(AssertionError, 'multiple'):
            self.observe(mutate=lambda p,w,h,r: paint(p, w, (60, 180, 72, 192), (*geometry.MARKERS[0]['rgb'],255)))

    def test_partly_missing_solid_body_fails(self):
        with self.assertRaisesRegex(AssertionError, 'dimensions|isotropic'):
            self.observe(mutate=lambda p,w,h,r: paint(p, w, (r[0][0],r[0][1],r[0][0]+12,r[0][3])))

    def test_body_with_large_hole_fails(self):
        with self.assertRaisesRegex(AssertionError, 'complete solid'):
            self.observe(mutate=lambda p,w,h,r: paint(p, w, (r[0][0]+8,r[0][1]+8,r[0][0]+20,r[0][1]+16)))

    def test_clipping_at_canvas_edge_fails(self):
        with self.assertRaisesRegex(AssertionError, 'canvas edge'):
            self.observe(canvas=(120,100,420,500))

    def test_swapped_landmark_colors_fail(self):
        def swap(p,w,h,r):
            paint(p,w,r[0],(*geometry.MARKERS[1]['rgb'],255))
            paint(p,w,r[1],(*geometry.MARKERS[0]['rgb'],255))
        with self.assertRaisesRegex(AssertionError, 'order or scale'):
            self.observe(mutate=swap)

    def test_anisotropic_resize_fails(self):
        with self.assertRaisesRegex(AssertionError, 'isotropic'):
            self.observe(anisotropy=(1.15,.85))

    def test_changed_scale_with_same_world_center_fails(self):
        with self.assertRaisesRegex(AssertionError, 'scale changed'):
            geometry.compare_viewports(self.observe(), self.observe(scale=.5,pan=(120,220)))

    def test_changed_pan_fails(self):
        with self.assertRaisesRegex(AssertionError, 'world center changed'):
            geometry.compare_viewports(self.observe(), self.observe(pan=(30,140)))

    def test_color_outside_canvas_is_ignored(self):
        result = self.observe(mutate=lambda p,w,h,r: paint(p,w,(0,0,12,12),(*geometry.MARKERS[0]['rgb'],255)))
        self.assertEqual((200,160),result['worldCenter'])

    def test_transparent_body_fails(self):
        with self.assertRaisesRegex(AssertionError, 'opaque'):
            self.observe(mutate=lambda p,w,h,r: paint(p,w,r[0],(*geometry.MARKERS[0]['rgb'],100)))

    def test_nonintegral_or_outside_canvas_fails(self):
        decoded, _ = scene()
        for canvas in ((0.5,0,400,400),(-1,0,400,400),(0,0,800,400)):
            with self.subTest(canvas=canvas), self.assertRaises(AssertionError):
                geometry.measure_viewport(decoded,canvas)


if __name__ == '__main__':
    unittest.main()
