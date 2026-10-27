#!/usr/bin/env python3
"""Independent reference decoder for VOXCRYPT .vxc files (format version 1).

Written only from docs/FORMAT_VXC.md, to cross check the Kotlin implementation.
Supports password protected files. Requires: pip install cryptography

Usage: python3 tools/vxc_decrypt.py input.vxc output.audio
The password is read from the terminal (never from the command line).
"""
import getpass
import hashlib
import struct
import sys

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def decrypt(data: bytes, password: bytes):
    if data[:8] != b"VOXCRYPT":
        raise ValueError("not a VOXCRYPT file")
    version = data[8]
    if version != 1:
        raise ValueError(f"unsupported version {version}")
    (hlen,) = struct.unpack(">H", data[9:11])
    if not 25 <= hlen <= 1024:
        raise ValueError("bad header length")
    header = data[:hlen]
    suite, protection, flags = header[11], header[12], header[13]
    (segment,) = struct.unpack(">I", header[14:18])
    prefix = header[18:25]
    if suite != 1 or flags != 0 or not 4096 <= segment <= 1048576:
        raise ValueError("invalid parameters")
    if protection != 1:
        raise ValueError("only password files can be opened outside the original device")
    (iterations,) = struct.unpack(">I", header[25:29])
    salt_len = header[29]
    salt = header[30:30 + salt_len]
    if 30 + salt_len != hlen or not 100_000 <= iterations <= 10_000_000 or not 16 <= salt_len <= 64:
        raise ValueError("invalid parameters")
    key = hashlib.pbkdf2_hmac("sha256", password, salt, iterations, 32)
    aead = AESGCM(key)
    body = data[hlen:]
    seg_ct = segment + 16
    plain = bytearray()
    pos, counter = 0, 0
    while True:
        chunk = body[pos:pos + seg_ct]
        if len(chunk) < 16:
            raise ValueError("truncated")
        pos += len(chunk)
        last = pos >= len(body)
        nonce = prefix + struct.pack(">I", counter) + (b"\x01" if last else b"\x00")
        plain += aead.decrypt(nonce, chunk, header)
        if last:
            break
        counter += 1
    (meta_len,) = struct.unpack(">I", plain[:4])
    meta = plain[4:4 + meta_len]
    audio = bytes(plain[4 + meta_len:])
    off = 1
    (n,) = struct.unpack(">H", meta[off:off + 2]); name = meta[off + 2:off + 2 + n].decode(); off += 2 + n
    (n,) = struct.unpack(">H", meta[off:off + 2]); mime = meta[off + 2:off + 2 + n].decode(); off += 2 + n
    created, duration, length = struct.unpack(">qqq", meta[off:off + 24])
    return {"name": name, "mime": mime, "created": created, "duration_ms": duration, "length": length}, audio


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], "rb") as f:
        blob = f.read()
    pwd = getpass.getpass("Mot de passe : ").encode()
    try:
        meta, audio = decrypt(blob, pwd)
    except InvalidTag:
        sys.exit("Echec d'authentification : mot de passe incorrect ou fichier altéré.")
    with open(sys.argv[2], "wb") as f:
        f.write(audio)
    print(meta)
