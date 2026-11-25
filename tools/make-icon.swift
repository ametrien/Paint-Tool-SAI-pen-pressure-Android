// The app icon: "Pen" by gravisio from Flaticon on a soft light tile.
// Drawing icons created by gravisio - Flaticon: https://www.flaticon.com/free-icons/drawing
//
// Flaticon's free licence asks for that attribution (it is in LICENSES.txt,
// which the app shows under "Licences") and does not let the picture be passed
// on by itself, so it is not in the repository. To redraw the icons:
//
//   curl -o third_party/icon/flaticon-11502485.png https://cdn-icons-png.flaticon.com/512/11502/11502485.png
//   swiftc -O -o /tmp/make-icon tools/make-icon.swift && /tmp/make-icon src/main/res [picture.png]
//
// Writes, for every Android density:
//   mipmap-*/ic_saipen.png             rounded tile + pen (48 dp)
//   mipmap-*/ic_saipen_round.png       the same on a round tile (48 dp)
//   mipmap-*/ic_saipen_foreground.png  the pen alone, for the adaptive icon
//                                      (108 dp layer; Android masks it to the
//                                      launcher's shape over the tile colour)
// The PNGs are committed, so building the APK does not need this script.
import AppKit

let args = CommandLine.arguments
let res = args.count > 1 ? args[1] : "src/main/res"
let penPath = args.count > 2 ? args[2] : "third_party/icon/flaticon-11502485.png"
let pen = NSImage(contentsOfFile: penPath)!
let penRep = NSBitmapImageRep(data: pen.tiffRepresentation!)!
// the drawing's own box inside the PNG (pixels with any ink), so it is
// centred and sized by what is drawn, not by the PNG's empty margins
let art: NSRect = {
    var x0 = penRep.pixelsWide, y0 = penRep.pixelsHigh, x1 = -1, y1 = -1
    for y in 0..<penRep.pixelsHigh { for x in 0..<penRep.pixelsWide
        where (penRep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.05 {
        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
    } }
    let k = pen.size.width / CGFloat(penRep.pixelsWide)
    // bitmap rows run top-down, NSImage coordinates bottom-up
    return NSRect(x: CGFloat(x0) * k, y: pen.size.height - CGFloat(y1 + 1) * k,
                  width: CGFloat(x1 - x0 + 1) * k, height: CGFloat(y1 - y0 + 1) * k)
}()
let tileWhite: CGFloat = 0.97          // #F7F7F7, same tile as before

enum Kind { case tile, round, foreground }

func render(_ kind: Kind, px: Int, to path: String) {
    let side = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px,
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    var glyph: CGFloat
    switch kind {
    case .tile:
        // exactly the Mac icon's geometry
        let inset = side * 0.08
        let rect = NSRect(x: inset, y: inset, width: side - inset * 2, height: side - inset * 2)
        NSColor(calibratedWhite: tileWhite, alpha: 1).setFill()
        NSBezierPath(roundedRect: rect, xRadius: side * 0.18, yRadius: side * 0.18).fill()
        glyph = 0.56
    case .round:
        let inset = side * 0.04
        NSColor(calibratedWhite: tileWhite, alpha: 1).setFill()
        NSBezierPath(ovalIn: NSRect(x: inset, y: inset, width: side - inset * 2, height: side - inset * 2)).fill()
        glyph = 0.54
    case .foreground:
        // inside the 66 dp safe zone of the 108 dp layer, the same share of
        // the visible icon as on the Mac tile
        glyph = 0.40
    }

    let scale = side * glyph / max(art.width, art.height)
    let w = art.width * scale, h = art.height * scale
    pen.draw(in: NSRect(x: (side - w) / 2, y: (side - h) / 2, width: w, height: h),
             from: art, operation: .sourceOver, fraction: 1)

    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

let densities: [(String, CGFloat)] = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]
for (name, scale) in densities {
    let dir = "\(res)/mipmap-\(name)"
    try! FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
    render(.tile, px: Int(48 * scale), to: "\(dir)/ic_saipen.png")
    render(.round, px: Int(48 * scale), to: "\(dir)/ic_saipen_round.png")
    render(.foreground, px: Int(108 * scale), to: "\(dir)/ic_saipen_foreground.png")
}
print("icons written to \(res)")
