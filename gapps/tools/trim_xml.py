#!/usr/bin/env python3
"""Write a subset of a MindTheGapps permissions/sysconfig XML.

Usage: python3 -I trim_xml.py <mode> <in.xml> <out.xml> <source-label> <pkg> [<pkg> ...]

mode:
  privapp    keep <privapp-permissions package=...> blocks for the given packages
  hiddenapi  keep <hidden-api-whitelisted-app package=...> for the given packages
  sysconfig  keep elements whose package= is a given package, every
             <allow-implicit-broadcast>, and <backup-transport-whitelisted-service>
             whose service belongs to a given package

Only parses data; never executes anything from the input.
"""
import sys
import xml.etree.ElementTree as ET


def keep(mode, el, pkgs):
    pkg = el.get("package")
    if mode == "privapp":
        return el.tag == "privapp-permissions" and pkg in pkgs
    if mode == "hiddenapi":
        return el.tag == "hidden-api-whitelisted-app" and pkg in pkgs
    if mode == "sysconfig":
        if pkg is not None:
            return pkg in pkgs
        if el.tag == "allow-implicit-broadcast":
            return True
        if el.tag == "backup-transport-whitelisted-service":
            return el.get("service", "").split("/")[0] in pkgs
        return False
    raise SystemExit("unknown mode " + mode)


def main():
    if len(sys.argv) < 6:
        raise SystemExit(__doc__)
    mode, src, dst, label = sys.argv[1:5]
    pkgs = set(sys.argv[5:])
    root = ET.parse(src).getroot()
    kept = [el for el in list(root) if keep(mode, el, pkgs)]
    dropped = len(list(root)) - len(kept)
    new = ET.Element(root.tag, root.attrib)
    new.append(ET.Comment(
        " Subset of %s: only entries for %s. Generated offline; entries for packages "
        "not shipped in this module were removed. " % (label, ", ".join(sorted(pkgs)))))
    for el in kept:
        el.tail = None
        new.append(el)
    ET.indent(new, space="    ")
    body = ET.tostring(new, encoding="unicode")
    with open(dst, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n' + body + "\n")
    print("%s: kept %d, dropped %d top-level elements" % (dst, len(kept), dropped))
    for el in kept:
        print("   kept <%s %s>" % (el.tag, " ".join('%s="%s"' % kv for kv in el.attrib.items())))


if __name__ == "__main__":
    main()
