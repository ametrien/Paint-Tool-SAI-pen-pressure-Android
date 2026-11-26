#!/usr/bin/env python3
"""Makes tests/fixtures/sai2-thumb.sai2: a tiny made-up .sai2 file with only
a 'thum' chunk, for Sai2ThumbTest. Nobody's drawing is in it: the picture is
four flat colours (red, green, blue, white quarters), 37 x 21 pixels (neither
a multiple of 8, so the edge blocks are partial).

How SAI Ver.2 stores the thumbnail (worked out from real files, see
Sai2Thumb.java): a baseline JPEG, YCbCr 4:4:4, the standard Huffman tables,
with all its headers taken out. The two quantisation tables stay, and the
scan is cut at each row of 8x8 blocks (DC prediction starts over on each
row), each row stored as u16 length + bytes. This script makes such a JPEG
with cjpeg (a restart marker after each block row) and takes it apart the
same way.

    python3 -I tools/make-sai2-fixture.py tests/fixtures/sai2-thumb.sai2
"""
import struct, subprocess, sys

W, H = 37, 21

def picture():
    px = bytearray()
    for y in range(H):
        for x in range(W):
            top, left = y < H // 2, x < W // 2
            px += {(True, True): b'\xff\x00\x00', (True, False): b'\x00\xff\x00',
                   (False, True): b'\x00\x00\xff', (False, False): b'\xff\xff\xff'}[(top, left)]
    return b'P6\n%d %d\n255\n' % (W, H) + bytes(px)

def jpeg():
    return subprocess.run(['cjpeg', '-baseline', '-quality', '95', '-sample', '1x1',
                           '-restart', '1', '-dct', 'int'],
                          input=picture(), capture_output=True, check=True).stdout

def take_apart(j):
    """-> (luma table, chroma table, [row bytes])"""
    i, tables, scan = 2, {}, None
    while scan is None:
        assert j[i] == 0xff
        marker, n = j[i + 1], struct.unpack_from('>H', j, i + 2)[0]
        body = j[i + 4:i + 2 + n]
        if marker == 0xdb:
            k = 0
            while k < len(body):
                assert body[k] >> 4 == 0, '8-bit tables only'
                tables[body[k] & 15] = body[k + 1:k + 65]
                k += 65
        if marker == 0xda:
            scan = j[i + 2 + n:]
        i += 2 + n
    scan = scan[:scan.rindex(b'\xff\xd9')]
    rows, start, k = [], 0, 0
    while k < len(scan) - 1:
        if scan[k] == 0xff and 0xd0 <= scan[k + 1] <= 0xd7:
            rows.append(scan[start:k])
            start = k = k + 2
        else:
            k += 1
    rows.append(scan[start:])
    assert len(rows) == (H + 7) // 8, len(rows)
    return tables[0], tables[1], rows

def sai2(luma, chroma, rows):
    thum = struct.pack('<II', W, H) + b'jssf' + struct.pack('<HHH', W, H, 3) + luma + chroma
    for r in rows:
        thum += struct.pack('<H', len(r)) + r
    thum += struct.pack('<H', 0)
    head = b'SAI-CANVAS-TYPE0' + bytes(0x40 - 16)
    table_end = 0x40 + 2 * 16
    head += b'thum' + struct.pack('<IQ', 0, table_end)
    head += b'intg' + struct.pack('<IQ', 0, table_end + len(thum))
    return head + thum

if __name__ == '__main__':
    open(sys.argv[1], 'wb').write(sai2(*take_apart(jpeg())))
