"""Renders the Recon Drone mesh set to PNG, without launching Minecraft.

This is the feedback loop for the OBJ pipeline. `verify_mesh.py` proves the
mesh is well formed; this proves it looks like a drone. It also builds the
entity texture sheet straight from the manifest, which is exactly what the
real texture generator does, so a packing mistake shows up here first.

The two render types the real renderer uses are both modelled here, because
the difference between them is a visible property rather than a detail: the
airframe is shaded, and the lens and tail lamp are composited additively from
their own sheet, the way `RenderType#eyes` composites them in game. Every face
is sampled from the sheet its UVs were packed against - the emissive parts have
their own sheet at their own resolution.

Outputs
-------
    <out>/drone-preview.png   four orthographic-ish views on a studio backdrop
    <out>/drone-texture.png   the body sheet, exactly as the manifest packs it
    <out>/drone-glow.png      the emissive sheet

Usage
-----
    python tools/preview_mesh.py [model_dir] [out_dir]

Both default to the shipped mesh set and `build/preview`, so a bare run just works.
"""

from __future__ import annotations

import json
import math
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

# The palette lives with the mesh generator and nowhere else. A hand-copied
# second copy lived here for a while and drifted: it still had the old near
# black GLASS, so the preview showed a canopy that the real texture no longer
# painted.
from generate_drone_mesh import U  # noqa: E402

# The sheet painter is imported rather than reimplemented, for the same reason: a second copy of
# the painting loop drifted in exactly the way a second copy always does. See build_sheet below.
import generate_textures  # noqa: E402


# ------------------------------------------------------------------------ PNG

def write_png(path, width, height, rows):
    """rows: list of bytearray, each width*3 bytes, top row first."""
    raw = b"".join(b"\x00" + bytes(row) for row in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(blob)


# ------------------------------------------------------------------- texture

def build_sheet(manifest, glow, red=False):
    """Paints a sheet exactly the way the game's texture generator does.

    This used to carry its own copy of the painting loop, with a one pixel inset border at a
    slightly different threshold (w > 2 here, w >= 5 there) and a slightly different edge colour.
    That was fine while both were "a border plus some noise" and actively harmful once the game
    texture went flat: the preview kept drawing a grid of tiles that no longer existed, so it
    disagreed with the only thing it exists to predict. It now calls the real painter, and the two
    cannot drift again.
    """
    canvas = generate_textures.build_entity_sheet(manifest, glow, red)
    return [bytes(channel for r, g, b, _ in row for channel in (r, g, b)) for row in canvas.px]


def sheet_size(manifest, glow):
    return tuple(manifest["glow_sheet"] if glow else manifest["sheet"])


def load_texture(manifest, glow, scale, red=False):
    """Builds the sheet at 1:1 and returns (pixels, width, height) upscaled."""
    rows = build_sheet(manifest, glow, red)
    width, height = sheet_size(manifest, glow)
    pixels = [bytes(r) for r in rows]
    if scale > 1:
        big = []
        for row in pixels:
            expanded = bytearray()
            for col in range(width):
                expanded += row[col * 3:col * 3 + 3] * scale
            for _ in range(scale):
                big.append(bytes(expanded))
        pixels, width, height = big, width * scale, height * scale
    return pixels, width, height


# ---------------------------------------------------------------------- maths

def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def unit(v):
    n = math.sqrt(dot(v, v)) or 1.0
    return (v[0] / n, v[1] / n, v[2] / n)


# ------------------------------------------------------------------ OBJ input

def parse_obj(path):
    """Returns (vertices, faces) where faces are (group, [(vi, uv)...], normal)."""
    vertices = []
    uvs = []
    normals = []
    faces = []
    group = ""
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            kind = parts[0]
            if kind == "v":
                vertices.append(tuple(float(v) for v in parts[1:4]))
            elif kind == "vt":
                uvs.append((float(parts[1]), float(parts[2])))
            elif kind == "vn":
                normals.append(tuple(float(v) for v in parts[1:4]))
            elif kind == "g":
                group = " ".join(parts[1:])
            elif kind == "f":
                corners = []
                for token in parts[1:]:
                    bits = token.split("/")
                    vi = int(bits[0]) - 1
                    ti = int(bits[1]) - 1 if len(bits) > 1 and bits[1] else 0
                    ni = int(bits[2]) - 1 if len(bits) > 2 and bits[2] else 0
                    corners.append((vertices[vi], uvs[ti] if uvs else (0.0, 0.0),
                                    normals[ni] if normals else (0.0, 1.0, 0.0)))
                faces.append((group, corners))
    return vertices, faces


# ------------------------------------------------------------------ renderer

class View:
    def __init__(self, eye, target, up, fov, width, height, scale):
        self.width = width
        self.height = height
        self.scale = scale
        forward = unit(sub(target, eye))
        right = unit(cross(forward, up))
        true_up = cross(right, forward)
        self.eye = eye
        self.basis = (right, true_up, forward)
        self.focal = 1.0 / math.tan(math.radians(fov) / 2.0)

    def to_view(self, point):
        right, up, forward = self.basis
        rel = sub(point, self.eye)
        return (dot(right, rel), dot(up, rel), dot(forward, rel))

    def project(self, view_point):
        x, y, z = view_point
        z = max(z, 1e-4)
        aspect = self.width / self.height
        # focal already holds 1/tan(fov/2), so multiply rather than divide
        ndc_x = x * self.focal / z
        ndc_y = y * self.focal / z
        sx = (ndc_x / aspect * 0.5 + 0.5) * self.width
        sy = (0.5 - ndc_y * 0.5) * self.height
        return sx, sy, z


# Studio lighting for the preview. The key comes from above the front-left, which is what makes
# the nose and the arm tops read; the old light sat *behind* the airframe, so the front views came
# out as a dark cut-out. Fill from the rear-right keeps the shaded sides from going black, and the
# narrow specular lobe stands in for the roughness term of the Blender material.
KEY_LIGHT = unit((-0.5, 0.8, -0.35))
FILL_LIGHT = unit((0.55, -0.15, 0.65))
AMBIENT = 1.00
KEY_GAIN = 0.26
FILL_GAIN = 0.12
SPECULAR_GAIN = 0.20
SPECULAR_POWER = 28.0


def surface_shade(world_n, view_dir):
    """Lighting response on top of the pre-shaded sheet.

    The sheet already carries a soft directional bake (see ``generate_textures.py``), so this is
    deliberately an ambient-heavy response rather than a second full key light: two stacked
    diffuse terms would crush the underside back to the silhouette the old preview had.
    """
    lambert = max(0.0, dot(world_n, KEY_LIGHT))
    fill = max(0.0, dot(world_n, FILL_LIGHT))
    halfway = unit(tuple(KEY_LIGHT[i] + view_dir[i] for i in range(3)))
    specular = max(0.0, dot(world_n, halfway)) ** SPECULAR_POWER
    return AMBIENT + KEY_GAIN * lambert + FILL_GAIN * fill + SPECULAR_GAIN * specular


def render(faces, textures, view, backdrop):
    """Rasterises `faces` into one tile.

    `faces` is a list of (component name, corners, emissive) - the flag comes from which OBJ
    the face was read out of, not from its component name, because the emissive faces of the
    hard-surface model sit inside the body component.

    `textures` maps the emissive flag to a (pixels, width, height) sheet, because the drone's
    emissive parts live on their own texture at their own resolution. Sampling everything from
    the body sheet - which is what this used to do - drew the lens and the tail lamp black, since
    the body sheet deliberately leaves their UV rectangles empty.

    Emissive faces stand in for the mesh the real renderer draws with
    `RenderType#eyes`: additive blending, no lightmap multiply, and no depth write. All three are
    modelled here, because the gap between "lit" and "additively lit" is the whole reason the
    drone has a second mesh.
    """
    pixels = [[0.0, 0.0, 0.0] for _ in range(view.width * view.height)]
    depth = [float("inf")] * (view.width * view.height)

    # Backdrop gradient. Kept in 0..255 so it shares a scale with the texels
    # that overwrite it - storing it as 0..1 truncated the whole background
    # to black once the rows were built.
    top, bottom = backdrop
    for y in range(view.height):
        t = y / max(1, view.height - 1)
        base = [(top[i] * (1 - t) + bottom[i] * t) * 255.0 for i in range(3)]
        start = y * view.width
        for x in range(view.width):
            pixels[start + x] = base[:]

    scale = view.scale

    prepared = []
    for group, corners, emissive in faces:
        world = [tuple(c * scale for c in p) for p, _, _ in corners]
        prepared.append((group, corners, world, emissive))

    for group, corners, world, emissive in prepared:
        view_pts = [view.to_view(p) for p in world]
        screen = [view.project(v) for v in view_pts]

        # face normal in view space, from the first three vertices
        n = unit(cross(sub(view_pts[1], view_pts[0]), sub(view_pts[2], view_pts[0])))
        # centroid in view space; the camera looks down +z, so a face is
        # front-facing when its normal points back towards the camera.
        centroid = tuple(sum(v[i] for v in view_pts) / len(view_pts) for i in range(3))
        if dot(n, centroid) > 0.0:
            continue
        # Crude near-plane rejection. Without it a vertex behind the camera
        # projects to an enormous triangle that swallows the whole frame.
        if min(p[2] for p in view_pts) < 0.05:
            continue

        tex_pixels, tex_w, tex_h = textures[emissive]
        world_n = unit(cross(sub(world[1], world[0]), sub(world[2], world[0])))
        if emissive:
            # Ignored: the additive pass below does not scale by shading, matching a shader
            # that has no lightmap term to scale by.
            shade = 1.0
        else:
            centroid_world = tuple(sum(p[i] for p in world) / len(world) for i in range(3))
            view_dir = unit(sub(view.eye, centroid_world))
            shade = surface_shade(world_n, view_dir)

        # quads arrive as quads; the prism caps arrive as triangles
        triangles = ((0, 1, 2),) if len(screen) == 3 else ((0, 1, 2), (0, 2, 3))
        for tri in triangles:
            i0, i1, i2 = tri
            s0, s1, s2 = screen[i0], screen[i1], screen[i2]
            area = ((s1[0] - s0[0]) * (s2[1] - s0[1])
                    - (s2[0] - s0[0]) * (s1[1] - s0[1]))
            if abs(area) < 1e-9:
                continue
            min_x = max(0, int(min(s0[0], s1[0], s2[0])))
            max_x = min(view.width - 1, int(max(s0[0], s1[0], s2[0])) + 1)
            min_y = max(0, int(min(s0[1], s1[1], s2[1])))
            max_y = min(view.height - 1, int(max(s0[1], s1[1], s2[1])) + 1)
            if min_x > max_x or min_y > max_y:
                continue

            inv0, inv1, inv2 = 1.0 / s0[2], 1.0 / s1[2], 1.0 / s2[2]
            uv0 = corners[i0][1]
            uv1 = corners[i1][1]
            uv2 = corners[i2][1]
            flip = area < 0
            for py in range(min_y, max_y + 1):
                fy = py + 0.5
                row = py * view.width
                for px in range(min_x, max_x + 1):
                    fx = px + 0.5
                    w0 = ((s1[0] - fx) * (s2[1] - fy) - (s2[0] - fx) * (s1[1] - fy)) / area
                    w1 = ((s2[0] - fx) * (s0[1] - fy) - (s0[0] - fx) * (s2[1] - fy)) / area
                    w2 = 1.0 - w0 - w1
                    if w0 < -1e-6 or w1 < -1e-6 or w2 < -1e-6:
                        continue
                    inv_z = w0 * inv0 + w1 * inv1 + w2 * inv2
                    if inv_z <= 0.0:
                        continue
                    z = 1.0 / inv_z
                    if emissive:
                        # RenderType#eyes writes colour only, so it tests depth but does not
                        # update it, and it accepts an equal depth (LEQUAL).
                        if z > depth[row + px]:
                            continue
                    else:
                        if z >= depth[row + px]:
                            continue
                        depth[row + px] = z

                    u = (w0 * uv0[0] * inv0 + w1 * uv1[0] * inv1 + w2 * uv2[0] * inv2) * z
                    v = (w0 * uv0[1] * inv0 + w1 * uv1[1] * inv1 + w2 * uv2[1] * inv2) * z
                    tx = int(u * tex_w) % tex_w
                    ty = int(v * tex_h) % tex_h
                    texel = tex_pixels[ty]
                    r = texel[tx * 3]
                    g = texel[tx * 3 + 1]
                    b = texel[tx * 3 + 2]
                    if emissive:
                        # blendFunc(ONE, ONE): the part adds its colour to whatever is behind it,
                        # which is the only way it can end up brighter than a fully lit surface.
                        dst = pixels[row + px]
                        pixels[row + px] = [min(255.0, dst[0] + r),
                                            min(255.0, dst[1] + g),
                                            min(255.0, dst[2] + b)]
                    else:
                        pixels[row + px] = [r * shade, g * shade, b * shade]

    rows = []
    for y in range(view.height):
        row = bytearray()
        for x in range(view.width):
            r, g, b = pixels[y * view.width + x]
            row += bytes((min(255, int(r)), min(255, int(g)), min(255, int(b))))
        rows.append(row)
    return rows


def downsample(rows, width, factor):
    height = len(rows) // factor
    out_width = width // factor
    out = []
    for oy in range(height):
        row = bytearray()
        for ox in range(out_width):
            r = g = b = 0
            for sy in range(factor):
                src = rows[oy * factor + sy]
                for sx in range(factor):
                    i = (ox * factor + sx) * 3
                    r += src[i]
                    g += src[i + 1]
                    b += src[i + 2]
            n = factor * factor
            row += bytes((r // n, g // n, b // n))
        out.append(row)
    return out


def main():
    model_dir = paths.model_dir(sys.argv[1] if len(sys.argv) > 1 else None)
    out_dir = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else paths.PREVIEW_OUT
    os.makedirs(out_dir, exist_ok=True)

    with open(paths.manifest_for(model_dir), encoding="utf-8") as handle:
        manifest = json.load(handle)

    body_verts, body_faces = parse_obj(os.path.join(model_dir, "drone.obj"))
    glow_verts, glow_faces = parse_obj(os.path.join(model_dir, "drone_glow.obj"))
    # The emissive flag comes from the file, not from the component name: on the hard-surface
    # model the cyan lens sits inside the body component, sharing its group with 5555 other faces.
    faces = ([(group, corners, False) for group, corners in body_faces]
             + [(group, corners, True) for group, corners in glow_faces])
    vertices = body_verts + glow_verts

    # texture sheets, straight from the manifest
    red = "--red" in sys.argv
    body_tex = load_texture(manifest, False, 1, red)
    glow_tex = load_texture(manifest, True, 1, red)
    # The emissive parts are on the glow sheet, at its own resolution. Every face has to be
    # sampled from the sheet its UVs were packed against, or the glow parts come out black.
    textures = {False: body_tex, True: glow_tex}
    write_png(os.path.join(out_dir, "drone-texture.png"), body_tex[1], body_tex[2],
              build_sheet(manifest, False, red))
    write_png(os.path.join(out_dir, "drone-glow.png"), glow_tex[1], glow_tex[2],
              build_sheet(manifest, True, red))

    low = [min(v[i] for v in vertices) for i in range(3)]
    high = [max(v[i] for v in vertices) for i in range(3)]
    centre = [(low[i] + high[i]) / 2.0 for i in range(3)]
    span = max(high[i] - low[i] for i in range(3))
    print("model bbox %s .. %s  (%.3f x %.3f x %.3f blocks)"
          % (low, high, high[0] - low[0], high[1] - low[1], high[2] - low[2]))

    cell = 420
    # Supersample and box filter. The rasteriser has hard pixel edges; without
    # this the README and the blue/red game previews show stair-stepped rotor
    # tips and arm silhouettes no matter how clean the mesh is.
    ss = 2
    render_size = cell * ss
    scale = 1.0 / span * 1.85
    # Frame on the bounding sphere, not the widest axis: the corners of a
    # square model reach sqrt(2) further out than the edges do.
    radius = 0.5 * math.sqrt(sum((high[i] - low[i]) ** 2 for i in range(3))) * scale
    fov = 18.0
    distance = radius / math.tan(math.radians(fov) / 2.0) / 0.92
    views = [
        ("top", 0.0, 89.9),
        ("front", 0.0, 0.0),
        ("side", 90.0, 0.0),
        ("front 3/4", 38.0, 24.0),
    ]
    backdrop = ((0.62, 0.66, 0.72), (0.86, 0.88, 0.91))

    tiles = []
    for label, azimuth, elevation in views:
        az = math.radians(azimuth)
        el = math.radians(elevation)
        eye = (distance * math.cos(el) * math.sin(az),
               distance * math.sin(el),
               -distance * math.cos(el) * math.cos(az))
        eye = tuple(eye[i] + centre[i] * scale for i in range(3))
        target = tuple(c * scale for c in centre)
        view = View(eye, target, (0.0, 1.0, 0.0), fov, render_size, render_size, scale)
        rows = render(faces, textures, view, backdrop)
        tiles.append((label, downsample(rows, render_size, ss)))
        print("rendered %s" % label)

    # stitch into a 2x2 sheet with one-pixel gutters
    gap = 3
    width = cell * 2 + gap * 3
    height = cell * 2 + gap * 3
    canvas = [bytearray(bytes((28, 30, 34)) * width) for _ in range(height)]
    for index, (label, rows) in enumerate(tiles):
        ox = gap + (index % 2) * (cell + gap)
        oy = gap + (index // 2) * (cell + gap)
        for y in range(cell):
            canvas[oy + y][ox * 3:(ox + cell) * 3] = rows[y]

    preview = os.path.join(out_dir, "drone-preview.png")
    write_png(preview, width, height, canvas)
    print("wrote %s" % preview)


if __name__ == "__main__":
    main()
