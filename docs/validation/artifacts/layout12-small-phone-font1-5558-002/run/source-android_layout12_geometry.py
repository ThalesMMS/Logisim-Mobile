"""Observe two painted image landmarks without using accessibility target bounds.

The input tuple is the existing decode_android_png() output. All coordinates in
the result are screenshot pixels, except the explicitly named world fields.
"""

import math


MARKERS = (
    {"id": "layout-magenta", "rgb": (224, 32, 160), "world": (100, 100, 132, 124)},
    {"id": "layout-cyan", "rgb": (16, 176, 208), "world": (260, 180, 300, 212)},
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def _blob(points):
    require(points, "The painted landmark is absent")
    remaining = set(points)
    pending = [remaining.pop()]
    while pending:
        x, y = pending.pop()
        for candidate in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if candidate in remaining:
                remaining.remove(candidate)
                pending.append(candidate)
    require(not remaining, "The landmark color has multiple disconnected painted regions")
    left, right = min(p[0] for p in points), max(p[0] for p in points) + 1
    top, bottom = min(p[1] for p in points), max(p[1] for p in points) + 1
    require(len(points) >= 16, "The painted landmark has insufficient pixels")
    require(len(points) / ((right - left) * (bottom - top)) >= .97,
            "The painted landmark is not a complete solid rectangle")
    return (left, top, right, bottom)


def measure_viewport(decoded, canvas):
    """Return a transform fitted to two independently colored solid image bodies.

    The canvas is an observed complete canvas rectangle in the screenshot. It is
    used only as the crop/window center. Expanded component AX bounds are unused.
    """
    width, height, channels, pixels = decoded
    require(channels in (3, 4) and len(pixels) == width * height * channels, "Invalid decoded screenshot")
    require(len(canvas) == 4 and all(type(v) is int for v in canvas), "Canvas bounds must be literal pixel integers")
    left, top, right, bottom = canvas
    require(0 <= left < right <= width and 0 <= top < bottom <= height, "Canvas is outside the screenshot")
    matches = [set(), set()]
    for y in range(top, bottom):
        for x in range(left, right):
            offset = (y * width + x) * channels
            rgb = pixels[offset:offset + 3]
            for index, marker in enumerate(MARKERS):
                if all(abs(rgb[c] - marker["rgb"][c]) <= 8 for c in range(3)):
                    if channels == 4:
                        require(pixels[offset + 3] >= 250, "Painted landmark must be opaque")
                    matches[index].add((x, y))
    observations = []
    for marker, points in zip(MARKERS, matches):
        rect = _blob(points)
        require(rect[0] > left and rect[1] > top and rect[2] < right and rect[3] < bottom,
                "A painted landmark touches the canvas edge and may be clipped")
        world = marker["world"]
        observations.append({"id": marker["id"], "paintedBounds": rect, "pixelCount": len(points),
                             "screenCenter": ((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2),
                             "worldCenter": ((world[0] + world[2]) / 2, (world[1] + world[3]) / 2)})
    a, b = observations
    world_delta = tuple(b["worldCenter"][c] - a["worldCenter"][c] for c in (0, 1))
    screen_delta = tuple(b["screenCenter"][c] - a["screenCenter"][c] for c in (0, 1))
    extent = sum(d * d for d in world_delta)
    scale = sum(world_delta[c] * screen_delta[c] for c in (0, 1)) / extent
    require(math.isfinite(scale) and scale > 0, "Painted landmark order or scale is invalid")
    residual = max(abs(screen_delta[c] - scale * world_delta[c]) for c in (0, 1))
    require(residual <= 2, "The two painted centers do not describe one unrotated isotropic viewport")
    for marker, observed in zip(MARKERS, observations):
        world, rect = marker["world"], observed["paintedBounds"]
        require(abs((rect[2] - rect[0]) - scale * (world[2] - world[0])) <= 2.5 and
                abs((rect[3] - rect[1]) - scale * (world[3] - world[1])) <= 2.5,
                "A painted landmark has wrong dimensions or is partially missing")
    pan = tuple(sum(o["screenCenter"][c] - scale * o["worldCenter"][c] for o in observations) / 2
                for c in (0, 1))
    canvas_center = ((left + right) / 2, (top + bottom) / 2)
    return {"canvas": canvas, "scalePixelsPerWorldUnit": scale, "globalPanPixels": pan,
            "worldCenter": tuple((canvas_center[c] - pan[c]) / scale for c in (0, 1)),
            "centerResidualPixels": residual, "markers": observations}


def compare_viewports(before, after):
    """Require retained scale and world center, with explicit raster tolerances.

    Scale tolerance is two pixels over the literal marker-center separation.
    World-center tolerance is three pixels at the larger observed scale. This
    does not establish any unobserved interval or identify the cause of failure.
    """
    centers = [m["worldCenter"] for m in before["markers"]]
    span = math.dist(*centers)
    first, second = before["scalePixelsPerWorldUnit"], after["scalePixelsPerWorldUnit"]
    scale_drift = abs(second - first) * span
    center_drift = math.dist(before["worldCenter"], after["worldCenter"]) * max(first, second)
    require(scale_drift <= 2, "Painted circuit scale changed across resize")
    require(center_drift <= 3, "Painted circuit world center changed across resize")
    return {"status": "PASS", "scaleDriftPixelsOverMarkerSpan": scale_drift,
            "worldCenterDriftPixels": center_drift,
            "tolerancePixels": {"scaleOverMarkerSpan": 2, "worldCenter": 3}}
