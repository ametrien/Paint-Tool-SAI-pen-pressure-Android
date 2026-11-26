#!/usr/bin/env python3
"""Pull the thumbnail out of a .sai2 file as a normal JPEG.

Format as found (SAI Ver.2, 'SAI-CANVAS-TYPE0'):
  file 0x40..: chunk table, 16 bytes per entry: tag[4], u32, u64 file offset
  'thum' chunk: u32 w, u32 h, 'jssf', u16 w, u16 h, u16 ?(3 = components?),
                64-byte luma quant table, 64-byte chroma quant table (zigzag),
                then one entry per row of 8x8 blocks ((h + 7) // 8 of them): u16 length
                + baseline JPEG scan data (standard Annex K Huffman, YCbCr 4:4:4, DC
                reset per row). No end marker: 0 to 3 zero bytes follow.
"""
import struct, sys

STD = [  # (class/id, counts, values) - JPEG Annex K tables
 (0x00, [0,1,5,1,1,1,1,1,1,0,0,0,0,0,0,0], bytes(range(12))),
 (0x10, [0,2,1,3,3,2,4,3,5,5,4,4,0,0,1,0x7d], bytes.fromhex(
  "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa")),
 (0x01, [0,3,1,1,1,1,1,1,1,1,1,0,0,0,0,0], bytes(range(12))),
 (0x11, [0,2,1,2,4,4,3,4,7,5,4,4,0,1,2,0x77], bytes.fromhex(
  "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa")),
]

def seg(marker, body):
    return bytes([0xff, marker]) + struct.pack('>H', len(body) + 2) + body

def thum_chunk(d):
    if not d.startswith(b'SAI-CANVAS-TYPE0'):
        raise ValueError('not a SAI2 canvas')
    off, table = 0x40, {}
    while off + 16 <= len(d) and d[off:off+4].isalpha():
        table[d[off:off+4]] = struct.unpack_from('<Q', d, off + 8)[0]
        off += 16
    start = table[b'thum']
    end = min([v for v in table.values() if v > start] + [len(d)])
    return d[start:end]

def to_jpeg(th):
    w, h = struct.unpack_from('<II', th, 0)
    if th[8:12] != b'jssf':
        raise ValueError('unknown thumbnail encoding %r' % th[8:12])
    out = b'\xff\xd8' + seg(0xdb, b'\x00' + th[0x12:0x52] + b'\x01' + th[0x52:0x92])
    out += seg(0xc0, struct.pack('>BHHB', 8, h, w, 3) + bytes([1,0x11,0, 2,0x11,1, 3,0x11,1]))
    for tc, counts, vals in STD:
        out += seg(0xc4, bytes([tc]) + bytes(counts) + vals)
    out += seg(0xdd, struct.pack('>H', (w + 7) // 8))
    out += seg(0xda, bytes([3, 1,0x00, 2,0x11, 3,0x11, 0, 63, 0]))
    off, rows = 0x92, []
    for _ in range((h + 7) // 8):
        n = struct.unpack_from('<H', th, off)[0]; off += 2
        if n == 0 or off + n > len(th):
            raise ValueError('row %d of %d cut short' % (len(rows), (h + 7) // 8))
        rows.append(th[off:off+n]); off += n
    for i, r in enumerate(rows):
        out += r + (bytes([0xff, 0xd0 + i % 8]) if i < len(rows) - 1 else b'')
    return out + b'\xff\xd9'

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    open(dst, 'wb').write(to_jpeg(thum_chunk(open(src, 'rb').read())))
