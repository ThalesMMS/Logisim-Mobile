// Compare an output Pin's glyph with known 0/1 reference Pins in the same PNG.
import Foundation
import CoreGraphics
import ImageIO

struct GlyphError: Error, CustomStringConvertible {
    let description: String
    init(_ message: String) { description = message }
}

struct Glyph {
    let mask: [Bool]
    let aspect: Double
    let height: Int
    let inkFraction: Double
}

let normalizedWidth = 32
let normalizedHeight = 48

func glyph(_ image: CGImage, _ roi: [String: Double]) throws -> Glyph {
    guard let x = roi["x"], let y = roi["y"], let w = roi["width"], let h = roi["height"],
          [x, y, w, h].allSatisfy({ $0.isFinite }), min(x, y) >= 0, min(w, h) > 0,
          x + w <= 1, y + h <= 1 else { throw GlyphError("Invalid normalized glyph ROI") }
    let bounds = CGRect(x: (x * Double(image.width)).rounded(), y: (y * Double(image.height)).rounded(),
                        width: (w * Double(image.width)).rounded(), height: (h * Double(image.height)).rounded())
    guard bounds.width >= 8, bounds.height >= 12, let crop = image.cropping(to: bounds) else {
        throw GlyphError("Glyph crop is too small or outside the image")
    }
    let width = crop.width, height = crop.height
    var pixels = [UInt8](repeating: 255, count: width * height * 4)
    guard let context = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8,
                                  bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        throw GlyphError("Cannot decode the PNG crop")
    }
    context.setFillColor(CGColor(gray: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
    var mask = [Bool](repeating: false, count: width * height)
    var left = width, right = -1, top = height, bottom = -1, ink = 0
    for row in 0..<height {
        for column in 0..<width {
            let offset = (row * width + column) * 4
            // Ink is distance from white. Its hue never encodes a logic value.
            if min(pixels[offset], pixels[offset + 1], pixels[offset + 2]) < 170 {
                mask[row * width + column] = true
                left = min(left, column); right = max(right, column)
                top = min(top, row); bottom = max(bottom, row); ink += 1
            }
        }
    }
    let fraction = Double(ink) / Double(width * height)
    guard right >= left, bottom >= top, ink >= 16, fraction >= 0.025, fraction <= 0.65 else {
        throw GlyphError("Blank, solid or insufficient glyph ink")
    }
    let glyphWidth = right - left + 1, glyphHeight = bottom - top + 1
    let aspect = Double(glyphWidth) / Double(glyphHeight)
    guard glyphHeight >= 10, aspect >= 0.12, aspect <= 1.1 else {
        throw GlyphError("Crop does not contain a single digit-sized glyph")
    }
    var normalized = [Bool](repeating: false, count: normalizedWidth * normalizedHeight)
    for row in 0..<normalizedHeight {
        for column in 0..<normalizedWidth {
            let sourceX = left + min(glyphWidth - 1, Int((Double(column) + 0.5) * Double(glyphWidth) / Double(normalizedWidth)))
            let sourceY = top + min(glyphHeight - 1, Int((Double(row) + 0.5) * Double(glyphHeight) / Double(normalizedHeight)))
            normalized[row * normalizedWidth + column] = mask[sourceY * width + sourceX]
        }
    }
    return Glyph(mask: normalized, aspect: aspect, height: glyphHeight, inkFraction: fraction)
}

func similarity(_ first: Glyph, _ second: Glyph) -> Double {
    guard abs(first.aspect - second.aspect) <= 0.12,
          Double(first.height) / Double(second.height) >= 0.75,
          Double(first.height) / Double(second.height) <= 1.25 else { return 0 }
    var best = 0.0
    for dy in -1...1 {
        for dx in -1...1 {
            var intersection = 0, firstCount = 0, secondCount = 0
            for row in 0..<normalizedHeight {
                for column in 0..<normalizedWidth {
                    let a = first.mask[row * normalizedWidth + column]
                    let shiftedX = column + dx, shiftedY = row + dy
                    let b = shiftedX >= 0 && shiftedX < normalizedWidth && shiftedY >= 0 && shiftedY < normalizedHeight
                        && second.mask[shiftedY * normalizedWidth + shiftedX]
                    if a { firstCount += 1 }; if b { secondCount += 1 }; if a && b { intersection += 1 }
                }
            }
            best = max(best, 2 * Double(intersection) / Double(firstCount + secondCount))
        }
    }
    return best
}

do {
    guard CommandLine.arguments.count == 3,
          let request = try JSONSerialization.jsonObject(with: Data(CommandLine.arguments[2].utf8)) as? [String: [String: Double]],
          let targetRoi = request["target"], let lowRoi = request["low"], let highRoi = request["high"],
          let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL, nil),
          CGImageSourceGetCount(source) == 1, let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        throw GlyphError("Expected one PNG and JSON target/low/high glyph ROIs")
    }
    let low = try glyph(image, lowRoi), high = try glyph(image, highRoi), target = try glyph(image, targetRoi)
    let referenceSimilarity = similarity(low, high)
    guard referenceSimilarity <= 0.75 else { throw GlyphError("The 0/1 reference glyphs are not distinct") }
    let zero = similarity(target, low), one = similarity(target, high)
    let winning = max(zero, one), margin = abs(zero - one)
    let value = winning >= 0.9 && margin >= 0.15 ? (zero > one ? "0" : "1") : "unknown"
    let result: [String: Any] = ["engine": "same-frame-glyph-reference-v1", "value": value,
                                 "scores": ["0": zero, "1": one], "margin": margin,
                                 "minimumSimilarity": 0.9, "minimumMargin": 0.15,
                                 "referenceSimilarity": referenceSimilarity,
                                 "referenceInkFractions": ["0": low.inkFraction, "1": high.inkFraction],
                                 "targetInkFraction": target.inkFraction]
    print(String(decoding: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), as: UTF8.self))
} catch {
    let result: [String: Any] = ["engine": "same-frame-glyph-reference-v1", "value": "unknown", "error": String(describing: error)]
    print(String(decoding: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), as: UTF8.self))
    exit(1)
}
