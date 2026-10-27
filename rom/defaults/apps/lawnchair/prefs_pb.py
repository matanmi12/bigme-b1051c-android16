#!/usr/bin/env python3
"""androidx DataStore Preferences codec (preferences_pb) + builder for the InKognito Lawnchair defaults.

Wire format (androidx.datastore.preferences PreferencesProto, protobuf-lite):
  message PreferenceMap { map<string, Value> preferences = 1; }   // entry: 1 = key (string), 2 = value (Value)
  message Value { oneof valueName { bool boolean = 1; float float = 2; int32 integer = 3; int64 long = 4;
                  string string = 5; StringSet string_set = 6; double double = 7; bytes bytes = 8; } }
  message StringSet { repeated string strings = 1; }
Entries are written in the order given, so a decode -> encode round trip of a DataStore file is byte-identical.

Usage:
  prefs_pb.py decode FILE.preferences_pb            -> JSON list [{key, type, value}] on stdout
  prefs_pb.py check  FILE.preferences_pb            -> decode + re-encode, exit 1 unless byte-identical
  prefs_pb.py build  lawnchair-defaults.json OUTDIR -> OUTDIR/preferences.preferences_pb (from "datastore")
                                                      + OUTDIR/com.android.launcher3.prefs.xml (from "shared_prefs")
Only the standard library; no device access.
"""
import json
import os
import struct
import sys
from xml.sax.saxutils import escape, quoteattr

FIELDS = {1: "bool", 2: "float", 3: "int", 4: "long", 5: "string", 6: "string_set", 7: "double", 8: "bytes"}
NUMBERS = {v: k for k, v in FIELDS.items()}


# ---- protobuf primitives ---------------------------------------------------------------------------------
def varint(n):
    if n < 0:
        n += 1 << 64                                       # int32/int64 negatives: 10-byte two's complement
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def read_varint(b, i):
    shift = n = 0
    while True:
        x = b[i]
        i += 1
        n |= (x & 0x7F) << shift
        if not x & 0x80:
            return n, i
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def signed(n, bits):
    return n - (1 << bits) if n >= 1 << (bits - 1) else n


def fields(b):
    """Yield (field number, wire type, raw value) for one message."""
    i = 0
    while i < len(b):
        tag, i = read_varint(b, i)
        num, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = read_varint(b, i)
        elif wt == 1:
            v, i = b[i:i + 8], i + 8
        elif wt == 2:
            n, i = read_varint(b, i)
            v, i = b[i:i + n], i + n
        elif wt == 5:
            v, i = b[i:i + 4], i + 4
        else:
            raise ValueError("unsupported wire type %d" % wt)
        if i > len(b):
            raise ValueError("truncated message")
        yield num, wt, v


def ld(num, payload):
    return varint(num << 3 | 2) + varint(len(payload)) + payload


def f32_text(raw):
    """Shortest decimal that maps back to exactly these float32 bits."""
    x = struct.unpack("<f", raw)[0]
    for p in range(1, 10):
        s = "%.*g" % (p, x)
        if struct.pack("<f", float(s)) == raw:
            return float(s)
    return x


# ---- Value -----------------------------------------------------------------------------------------------
def decode_value(b):
    out = None
    for num, wt, v in fields(b):
        t = FIELDS.get(num)
        if t is None:
            raise ValueError("unknown Value field %d" % num)
        if t == "bool":
            out = (t, bool(v))
        elif t == "float":
            out = (t, f32_text(v))
        elif t == "int":
            out = (t, signed(v & 0xFFFFFFFF, 32))
        elif t == "long":
            out = (t, signed(v, 64))
        elif t == "string":
            out = (t, v.decode("utf-8"))
        elif t == "string_set":
            out = (t, [s.decode("utf-8") for n2, _, s in fields(v) if n2 == 1])
        elif t == "double":
            out = (t, struct.unpack("<d", v)[0])
        elif t == "bytes":
            out = (t, v.hex())
    if out is None:
        raise ValueError("empty Value")
    return out


def encode_value(t, v):
    n = NUMBERS[t]
    if t == "bool":
        return varint(n << 3) + varint(1 if v else 0)
    if t == "float":
        return varint(n << 3 | 5) + struct.pack("<f", float(v))
    if t in ("int", "long"):
        if t == "int" and not -(1 << 31) <= int(v) < 1 << 31:
            raise ValueError("int32 out of range: %r" % v)
        return varint(n << 3) + varint(int(v))
    if t == "string":
        return ld(n, v.encode("utf-8"))
    if t == "string_set":
        return ld(n, b"".join(ld(1, s.encode("utf-8")) for s in v))
    if t == "double":
        return varint(n << 3 | 1) + struct.pack("<d", float(v))
    if t == "bytes":
        return ld(n, bytes.fromhex(v))
    raise ValueError("unknown type " + t)


# ---- PreferenceMap -----------------------------------------------------------------------------------------
def decode(b):
    out = []
    for num, wt, entry in fields(b):
        if num != 1 or wt != 2:
            raise ValueError("unexpected PreferenceMap field %d" % num)
        key = value = None
        for n2, w2, v2 in fields(entry):
            if n2 == 1:
                key = v2.decode("utf-8")
            elif n2 == 2:
                value = decode_value(v2)
        if key is None or value is None:
            raise ValueError("incomplete map entry")
        out.append({"key": key, "type": value[0], "value": value[1]})
    return out


def encode(entries):
    seen, out = set(), bytearray()
    for e in entries:
        if e["key"] in seen:
            raise ValueError("duplicate key " + e["key"])
        seen.add(e["key"])
        out += ld(1, ld(1, e["key"].encode("utf-8")) + ld(2, encode_value(e["type"], e["value"])))
    return bytes(out)


# ---- SharedPreferences XML (the format Android's SharedPreferencesImpl writes) ------------------------------
def shared_prefs_xml(entries):
    lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for e in entries:
        k, t, v = quoteattr(e["key"]), e["type"], e["value"]
        if t == "string":
            lines.append("    <string name=%s>%s</string>" % (k, escape(v, {'"': "&quot;"})))
        elif t == "bool":
            lines.append('    <boolean name=%s value="%s" />' % (k, "true" if v else "false"))
        elif t in ("int", "long"):
            lines.append('    <%s name=%s value="%d" />' % (t, k, int(v)))
        elif t == "float":
            lines.append('    <float name=%s value="%s" />' % (k, repr(float(v))))
        elif t == "string_set":
            lines.append("    <set name=%s>" % k)
            lines += ["        <string>%s</string>" % escape(s) for s in v]
            lines.append("    </set>")
        else:
            raise ValueError("type %s not valid in SharedPreferences" % t)
    lines.append("</map>")
    return "\n".join(lines) + "\n"


def main(argv):
    if len(argv) >= 2 and argv[0] in ("decode", "check"):
        raw = open(argv[1], "rb").read()
        entries = decode(raw)
        if argv[0] == "decode":
            json.dump(entries, sys.stdout, ensure_ascii=False, indent=1)
            print()
            return 0
        same = encode(entries) == raw
        print("round trip %s (%d bytes, %d keys)" % ("byte-identical" if same else "DIFFERS", len(raw), len(entries)))
        return 0 if same else 1
    if len(argv) == 3 and argv[0] == "build":
        spec = json.load(open(argv[1], encoding="utf-8"))
        os.makedirs(argv[2], exist_ok=True)
        pb = encode(spec["datastore"])
        if decode(pb) != [{k: e[k] for k in ("key", "type", "value")} for e in spec["datastore"]]:
            raise SystemExit("self-check failed: encoded datastore does not decode to the spec")
        with open(os.path.join(argv[2], "preferences.preferences_pb"), "wb") as f:
            f.write(pb)
        with open(os.path.join(argv[2], "com.android.launcher3.prefs.xml"), "w", encoding="utf-8") as f:
            f.write(shared_prefs_xml(spec["shared_prefs"]))
        print("wrote %d datastore keys (%d bytes), %d shared prefs keys" % (len(spec["datastore"]), len(pb),
                                                                           len(spec["shared_prefs"])))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
