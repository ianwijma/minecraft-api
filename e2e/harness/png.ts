/**
 * Minimal zero-dependency PNG codec (8-bit, non-interlaced, color types
 * 2=RGB and 6=RGBA) on node:zlib. Sufficient for MAPI screenshots and
 * baselines; anything else throws rather than decoding wrongly.
 */
import { inflateSync, deflateSync } from 'node:zlib';

export interface RgbaImage {
    width: number;
    height: number;
    /** width*height*4 bytes, row-major, no padding. */
    data: Buffer;
}

const SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
        }
        table[n] = c;
    }
    return table;
})();

function crc32(buf: Buffer): number {
    let crc = 0xffffffff;
    for (let i = 0; i < buf.length; i++) {
        crc = CRC_TABLE[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function paeth(a: number, b: number, c: number): number {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    if (pa <= pb && pa <= pc) return a;
    if (pb <= pc) return b;
    return c;
}

export function decodePng(buf: Buffer): RgbaImage {
    if (buf.length < 8 || !buf.subarray(0, 8).equals(SIG)) {
        throw new Error('not a PNG (bad signature)');
    }
    let offset = 8;
    let width = 0;
    let height = 0;
    let colorType = -1;
    const idat: Buffer[] = [];
    while (offset + 12 <= buf.length) {
        const length = buf.readUInt32BE(offset);
        const type = buf.toString('ascii', offset + 4, offset + 8);
        const data = buf.subarray(offset + 8, offset + 8 + length);
        if (type === 'IHDR') {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            const bitDepth = data[8];
            colorType = data[9];
            const interlace = data[12];
            if (bitDepth !== 8) throw new Error(`unsupported bit depth ${bitDepth}`);
            if (colorType !== 2 && colorType !== 6) {
                throw new Error(`unsupported color type ${colorType} (want 2 or 6)`);
            }
            if (interlace !== 0) throw new Error('interlaced PNG unsupported');
        } else if (type === 'IDAT') {
            idat.push(data);
        } else if (type === 'IEND') {
            break;
        }
        offset += 12 + length;
    }
    if (width === 0 || height === 0) throw new Error('missing IHDR');
    const raw = inflateSync(Buffer.concat(idat));
    const bpp = colorType === 6 ? 4 : 3;
    const stride = width * bpp;
    const out = Buffer.alloc(width * height * 4);
    const prev = Buffer.alloc(stride);
    const recon = Buffer.alloc(stride);
    let src = 0;
    for (let y = 0; y < height; y++) {
        const filter = raw[src++];
        for (let i = 0; i < stride; i++) {
            const x = raw[src + i];
            const left = i >= bpp ? recon[i - bpp] : 0;
            const up = prev[i];
            const upLeft = i >= bpp ? prev[i - bpp] : 0;
            let v: number;
            switch (filter) {
                case 0: v = x; break;
                case 1: v = x + left; break;
                case 2: v = x + up; break;
                case 3: v = x + ((left + up) >> 1); break;
                case 4: v = x + paeth(left, up, upLeft); break;
                default: throw new Error(`bad filter ${filter} on row ${y}`);
            }
            recon[i] = v & 0xff;
        }
        src += stride;
        for (let x = 0; x < width; x++) {
            const s = x * bpp;
            const d = (y * width + x) * 4;
            out[d] = recon[s];
            out[d + 1] = recon[s + 1];
            out[d + 2] = recon[s + 2];
            out[d + 3] = bpp === 4 ? recon[s + 3] : 255;
        }
        recon.copy(prev, 0, 0, stride);
    }
    return { width, height, data: out };
}

function chunk(type: string, data: Buffer): Buffer {
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const out = Buffer.alloc(12 + data.length);
    out.writeUInt32BE(data.length, 0);
    body.copy(out, 4);
    out.writeUInt32BE(crc32(body), 8 + data.length);
    return out;
}

export function encodePng(img: RgbaImage): Buffer {
    const { width, height, data } = img;
    if (data.length !== width * height * 4) {
        throw new Error(`RGBA length ${data.length} != ${width}*${height}*4`);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8;  // bit depth
    ihdr[9] = 6;  // color type RGBA
    // compression 0, filter 0, interlace 0
    const raw = Buffer.alloc(height * (1 + width * 4));
    let src = 0;
    let dst = 0;
    for (let y = 0; y < height; y++) {
        raw[dst++] = 0; // filter: none
        data.copy(raw, dst, src, src + width * 4);
        src += width * 4;
        dst += width * 4;
    }
    return Buffer.concat([
        SIG,
        chunk('IHDR', ihdr),
        chunk('IDAT', deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0)),
    ]);
}

/** Crop to a rectangle (clamped to bounds). */
export function cropImage(img: RgbaImage, x: number, y: number,
                          width: number, height: number): RgbaImage {
    const cx = Math.max(0, Math.min(x, img.width));
    const cy = Math.max(0, Math.min(y, img.height));
    const cw = Math.max(0, Math.min(width, img.width - cx));
    const ch = Math.max(0, Math.min(height, img.height - cy));
    const out = Buffer.alloc(cw * ch * 4);
    for (let row = 0; row < ch; row++) {
        img.data.copy(out, row * cw * 4,
            ((cy + row) * img.width + cx) * 4,
            ((cy + row) * img.width + cx + cw) * 4);
    }
    return { width: cw, height: ch, data: out };
}
