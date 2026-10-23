"""Converts the Blender hard-surface drone into the assets this mod ships.

Why this exists
---------------
The source model (``drone_hard_surface.obj``, Blender 5.1.2) is authored for a
physically based renderer, not for Minecraft's entity pipeline. Four things about
it are incompatible with ``net.neoforged.neoforge.client.model.obj``:

1. **No ``vt``.** ``ObjModel#makeQuad`` falls back to ``DEFAULT_COORDS`` when a
   face carries no texture coordinate, which maps the *entire* sheet onto every
   single quad. It does not fail loudly; it just looks wrong.
2. **n-gons.** ``makeQuad`` reads exactly four corners and does
   ``indices[Math.min(i, indices.length - 1)]``, so vertex 5 onwards of an
   n-gon is silently discarded. This model has 64 such faces (the motor caps).
3. **No ``map_Kd``.** ``UnbakedGeometryHelper#resolveDirtyMaterial`` returns
   ``MissingTextureAtlasSprite`` for a null texture, i.e. the purple-black
   checkerboard.
4. **``o`` followed by ``g``.** ``ObjModel#parse`` sets ``objAboveGroup`` on the
   first ``o``, and every later ``g`` becomes a nested component named
   ``Parent/Child`` -- which will not match the keys we hand ``Transforms``.

On top of that the material colours are PBR values (``Kd 0.004 0.006 0.008``),
which Minecraft renders as black, so the palette is re-authored here.

What it does
------------
* strips ``o``, keeps ``g``
* rotates +90 degrees about Y so the nose (+X in Blender) points at -Z, and
  centres the bounding box on the origin
* fan-triangulates n-gons; triangles and quads are left alone
* splits the emissive material into a second OBJ, because it needs a different
  render type (``RenderType#eyes``)
* adds the nose lamp, the one piece of geometry the Blender file does not have
* allocates one shelf-packed UV rectangle per face and paints it through the
  manifest, exactly like the procedural generator does
* rewrites the MTL with a real ``map_Kd``

Nothing here needs Blender. It is pure geometry on a text file, which means the
conversion is reproducible and can live in version control.

Run:  python tools/prepare_drone_model.py <source_dir> [--install]
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402
from generate_drone_mesh import SHEET, GLOW_SHEET, TEXELS_PER_U, U, ShelfPacker  # noqa: E402

#: The file Blender exported. Everything else in the folder is reference material.
SOURCE = "drone_hard_surface.obj"

#: Material -> palette name, for faces on the airframe.
#:
#: These are not the Blender values. The source materials are PBR metal (``Kd`` around 0.005),
#: which only looks like anything because a renderer is bouncing reflections off it; Minecraft
#: multiplies the albedo by a lightmap and stops there, so the same numbers come out black. The
#: mapping below keeps the design's dark-charcoal read while giving each material a distinct
#: value step, because on a faceted hull the albedo *is* the material.
BODY_COLOURS = {
    "Drone_Hull_Graphite": "HULL_PANEL",
    "Drone_Carbon": "HULL_MID",
    "Drone_Motor_Dark": "HULL_DARK",
    "Drone_Trim_Gunmetal": "HULL_LIGHT",
    "Drone_Sensor_Bezel": "HULL_LIGHT",
    "Drone_Panel_Steel": "BLADE",
}

#: Same, for faces on the rotors. The blades want to read as a lighter machined
#: part against a dark hull, so they get their own row rather than inheriting
#: the hull's grey.
ROTOR_COLOURS = {
    "Drone_Carbon": "BLADE",
    "Drone_Trim_Gunmetal": "HULL_LIGHT",
}

#: Anything in here is emissive and moves to the glow mesh.
GLOW_MATERIAL = "Drone_Cyan_Emission"
GLOW_COLOUR = "CYAN"

#: Component name -> pivot is derived from the rotor's own bounding box, so this
#: table only decides which components spin. Order matches the source.
ROTOR_GROUPS = ("Rotor_FL", "Rotor_FR", "Rotor_RL", "Rotor_RR")

#: The camera window gets its own component so ``DroneRig`` can tilt it.
#:
#: Selecting it needs a spatial test on top of the material, not just the material: the same
#: ``Drone_Cyan_Emission`` also lights the ring around each motor, and ``x > 0.2`` alone picks up
#: 122 faces spanning the full width of the airframe. The window is the 26 faces left once the
#: rotor rings are excluded, and they sit within 0.06 of the centre line.
#:
#: Both parts of the module are already separate closed shells -- the bezel (54 faces) and the
#: window (26) share no vertices with each other or with the hull -- so lifting the window out
#: into its own component cannot tear a hole in anything.
GIMBAL_GROUP = "Gimbal_Lens"
GIMBAL_HALF_WIDTH = 0.06
GIMBAL_FRONT_Z = -0.25

#: The nose lamp, added as new geometry rather than by re-colouring existing faces.
#:
#: The source model has nothing on the nose that can carry a light. Every forward-facing face
#: left over once the sensor window is accounted for is between 0.0002 and 0.0013 blocks square --
#: two or three screen pixels at any distance the drone is ever seen from -- and the sensor's own
#: bezel is a 54-face frame whose nine forward faces are a 16 mm lip. Re-colouring any of them
#: produces either nothing or a hairline, so the lamp is built here instead.
#:
#: It sits on the flat panel directly under the sensor window. That panel is *not* vertical: it
#: leans back 0.012 over its 0.050 height, a 13 degree chin. A box with a constant z therefore
#: pushes its side faces out through the slope and shows as two amber splinters beside the bar --
#: which is exactly what the first version did. The lamp is instead built on the panel's own
#: plane, so its front face is parallel to the surface and only 0.003 proud of it everywhere.
#:
#: Amber, not cyan: the sensor is cyan, and a second cyan patch immediately under it would read as
#: part of the sensor. Amber is also the one palette entry the hard-surface model leaves unused,
#: so the lamp cannot be confused with any existing emissive part.
NOSE_LAMP_GROUP = "Nose_Lamp"
NOSE_LAMP_COLOUR = "AMBER"

#: The mounting panel, read off face 1754 of the source: bottom (y, z), top (y, z), half width.
NOSE_LAMP_PANEL = (-0.0277, -0.2953, 0.0227, -0.3073, 0.0341)

#: Where on that panel the bar goes, as fractions of its height and half width.
#:
#: Only the bottom third of the panel is actually exposed. The sensor's bezel overhangs it: a ray
#: cast forward at x = 0 meets hull at z = -0.3219 from y = -0.006 upwards, against -0.2997 on the
#: panel, so everything above roughly y = -0.010 is behind the bezel's lower rail and invisible
#: from every angle. The first version ran to y = 0.008 and rendered as a thin amber splinter
#: peeking over the rail, with the real bar underneath it.
NOSE_LAMP_LOW = 0.03
NOSE_LAMP_HIGH = 0.32
NOSE_LAMP_INSET = 0.88

#: How far the front face stands off the panel, and how deep the back goes into it. The back only
#: has to be behind the panel surface; the sides are then buried and never drawn.
NOSE_LAMP_PROUD = 0.003
NOSE_LAMP_DEPTH = 0.008


# ------------------------------------------------------------------------ parsing


class Source:
    """The raw OBJ, parsed just far enough to rebuild it."""

    def __init__(self):
        self.vertices = []
        self.normals = []
        self.faces = []          # (group, material, [(v, vn), ...])

    @classmethod
    def read(cls, path):
        source = cls()
        group = ""
        material = ""
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                token = line.split()
                if not token:
                    continue
                tag = token[0]
                if tag == "v":
                    source.vertices.append(tuple(float(v) for v in token[1:4]))
                elif tag == "vn":
                    source.normals.append(tuple(float(v) for v in token[1:4]))
                elif tag == "g":
                    group = token[1]
                elif tag == "usemtl":
                    material = token[1]
                elif tag == "f":
                    corners = []
                    for part in token[1:]:
                        fields = part.split("/")
                        position = int(fields[0])
                        normal = int(fields[2]) if len(fields) > 2 and fields[2] else 0
                        corners.append((position - 1, normal - 1))
                    source.faces.append((group, material, corners))
                # "o" is deliberately ignored: it would make the following "g"
                # a nested component name.
        return source


# -------------------------------------------------------------------- transforms


def rotate_nose_to_negative_z(point):
    """Blender put the sensor on +X; Minecraft entities face -Z at yaw 0."""
    x, y, z = point
    return (z, y, -x)


def centre(points):
    """Bounding-box centre, so the model spins about its own middle."""
    low = [min(p[i] for p in points) for i in range(3)]
    high = [max(p[i] for p in points) for i in range(3)]
    return tuple((low[i] + high[i]) / 2.0 for i in range(3))


def fan_triangulate(corners):
    """A quad stays a quad; anything with more corners becomes a fan of triangles."""
    if len(corners) <= 4:
        return [corners]
    return [[corners[0], corners[k], corners[k + 1]] for k in range(1, len(corners) - 1)]


def is_camera_window(points):
    """True for the cyan faces on the nose, in the already-rotated, already-centred space.

    The emissive material is shared with the ring around each motor, so the material alone is not
    a usable selector: the rotor rings sit 0.3 blocks off the centre line and reach the full width
    of the airframe. Requiring the face to be both near the centre line and past the front of the
    hull leaves exactly the 26 faces of the camera window.
    """
    return (all(abs(p[0]) < GIMBAL_HALF_WIDTH for p in points)
            and all(p[2] < GIMBAL_FRONT_Z for p in points))


# ------------------------------------------------------------- added geometry


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _unit(v):
    length = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / length for c in v)


def add_quad(sheet, group, vertices, normals, corners, outward, colour, tag):
    """Appends one quad, winding it so its front face points along ``outward``.

    The winding has to be right on its own merits: every mesh in this mod is drawn with back-face
    culling, so a reversed quad is simply invisible. Rather than hand-order six sets of corners and
    hope, each quad is wound to match a normal computed from its own first three vertices, and
    reversed if that normal disagrees with the direction the face is supposed to face.

    New vertices and normals are appended to the shared pools, so the 1-based indices the rest of
    the writer uses stay valid.
    """
    normal = _unit(_cross(_sub(corners[1], corners[0]), _sub(corners[2], corners[0])))
    if sum(normal[i] * outward[i] for i in range(3)) < 0.0:
        corners = [corners[0]] + corners[:0:-1]
        normal = tuple(-c for c in normal)

    positions = []
    for point in corners:
        vertices.append(tuple(round(c, 6) for c in point))
        positions.append(len(vertices))
    normals.append(tuple(round(c, 6) for c in normal))
    normal_index = len(normals)

    record = sheet.add(group, tag, list(corners), normal, colour, True)
    record["_positions"] = positions
    record["_normals"] = [normal_index] * len(corners)
    return record


def nose_lamp_plane():
    """The mounting panel as ``(slope, normal, surface)`` in the YZ section.

    ``surface(y)`` returns the panel's z at that height; ``normal`` is the unit vector pointing
    away from the hull, i.e. towards -z.
    """
    bottom_y, bottom_z, top_y, top_z, _ = NOSE_LAMP_PANEL
    slope = (top_z - bottom_z) / (top_y - bottom_y)
    length = math.hypot(slope, 1.0)

    def surface(y):
        return bottom_z + slope * (y - bottom_y)

    return slope, (0.0, slope / length, -1.0 / length), surface


def add_nose_lamp(sheet, vertices, normals):
    """The lamp bar under the sensor window, built on the panel's own plane.

    Six quads: a front and a back plus four sides. Only the front is ever outside the hull -- the
    sides are 0.003 tall at most and the back is 0.008 inside -- but it is a closed box so the
    signed-volume winding check in ``report`` covers it like everything else.
    """
    _, normal, surface = nose_lamp_plane()
    bottom_y, _, top_y, _, half_width = NOSE_LAMP_PANEL
    height = top_y - bottom_y
    half = half_width * NOSE_LAMP_INSET

    y_low = bottom_y + height * NOSE_LAMP_LOW
    y_high = bottom_y + height * NOSE_LAMP_HIGH

    def corners(offset):
        result = []
        for y, x in ((y_low, -half), (y_low, half), (y_high, half), (y_high, -half)):
            z = surface(y)
            result.append((x + normal[0] * offset,
                           y + normal[1] * offset,
                           z + normal[2] * offset))
        return result

    front = corners(NOSE_LAMP_PROUD)
    back = corners(-NOSE_LAMP_DEPTH)
    add_quad(sheet, NOSE_LAMP_GROUP, vertices, normals, front, normal,
             NOSE_LAMP_COLOUR, "nose_lamp_front")
    add_quad(sheet, NOSE_LAMP_GROUP, vertices, normals, back,
             tuple(-c for c in normal), NOSE_LAMP_COLOUR, "nose_lamp_back")
    # The corners run anticlockwise seen from the front, so edge k joins corner k to k+1 and
    # faces the way that edge does: bottom, right, top, left.
    sides = ("bottom", "right", "top", "left")
    outward = ((0.0, -1.0, 0.0), (1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (-1.0, 0.0, 0.0))
    for index, tag in enumerate(sides):
        following = (index + 1) % 4
        face = [front[index], front[following], back[following], back[index]]
        add_quad(sheet, NOSE_LAMP_GROUP, vertices, normals, face, outward[index],
                 NOSE_LAMP_COLOUR, "nose_lamp_" + tag)
    return 6


# ------------------------------------------------------------------------ writing


def fmt(value):
    """Shortest text that still round-trips.

    Six decimals is what Blender wrote, and it is all this model needs; the
    nine-significant-digit form would triple the file for no gain.
    """
    if abs(value) < 5e-7:
        return "0"
    text = "%.6f" % value
    return text.rstrip("0").rstrip(".")


class Sheet:
    """One output OBJ: a vertex pool, per-face UVs, and a shelf-packed manifest."""

    def __init__(self, mesh_name, material, sheet_size):
        self.mesh_name = mesh_name
        self.material = material
        self.sheet = sheet_size
        self.packer = ShelfPacker(sheet_size[0], sheet_size[1])
        self.faces = []      # manifest records
        self.groups = {}     # group -> list of face records
        self.vt = []         # (u, v)

    def add(self, group, name, points, normal, colour, glow):
        """Records a face. The UV rectangle is assigned later, in ``layout``."""
        record = {
            "group": group,
            "name": name,
            "colour": colour,
            "glow": glow,
            "_points": points,
            "_normal": normal,
        }
        self.faces.append(record)
        self.groups.setdefault(group, []).append(record)
        return record

    def layout(self):
        """Shelf-packs every face's UV rectangle.

        The order matters a lot: shelf packing is first-fit, so feeding it faces
        in model order wastes roughly a third more height than feeding it
        tallest-first. On this mesh that is the difference between fitting in
        512 square and raising ``UV sheet 512x512 is too small``.
        """
        pending = []
        for record in self.faces:
            points = record["_points"]
            extents = sorted((max(p[i] for p in points) - min(p[i] for p in points)
                              for i in range(3)), reverse=True)
            pending.append((math.ceil(extents[0] * 16.0 * TEXELS_PER_U),
                            math.ceil(extents[1] * 16.0 * TEXELS_PER_U),
                            record))
        pending.sort(key=lambda item: (-item[1], -item[0]))

        for width, height, record in pending:
            x, y, w, h = self.packer.pack(width, height)
            corners = (
                (x / self.sheet[0], y / self.sheet[1]),
                ((x + w) / self.sheet[0], y / self.sheet[1]),
                ((x + w) / self.sheet[0], (y + h) / self.sheet[1]),
                (x / self.sheet[0], (y + h) / self.sheet[1]),
            )
            count = len(record["_points"])
            record["rect"] = [x, y, w, h]
            record["_uv"] = []
            for index in range(count):
                self.vt.append(corners[index])
                record["_uv"].append(len(self.vt))

    def manifest_parts(self, pivots):
        result = []
        for group, faces in self.groups.items():
            points = []
            for face in faces:
                points.extend(face["_points"])
            low = [min(p[i] for p in points) for i in range(3)]
            high = [max(p[i] for p in points) for i in range(3)]
            result.append({
                "group": group,
                "mesh": self.mesh_name,
                "pivot": [round(v, 6) for v in pivots.get(group, (0.0, 0.0, 0.0))],
                "faces": len(faces),
                "bbox": [[round(v, 6) for v in low], [round(v, 6) for v in high]],
            })
        return result


def write_obj(path, sheet, vertices, normals, mtllib):
    """Writes the sheet. One ``usemtl`` at the top, one ``g`` per component.

    Only the vertices and normals this sheet actually references are written, and the indices
    are renumbered to match. The two sheets share one parsed pool, and the glow mesh touches a
    couple of hundred faces out of nearly seven thousand - copying the whole pool into it would
    make the emissive file six times larger than the geometry it holds.
    """
    used_positions = sorted({p for face in sheet.faces for p in face["_positions"]})
    used_normals = sorted({n for face in sheet.faces for n in face["_normals"]})
    position_index = {old: new + 1 for new, old in enumerate(used_positions)}
    normal_index = {old: new + 1 for new, old in enumerate(used_normals)}

    lines = [
        "# Watchcraft recon drone - 1.0 = 1 Minecraft block, Y up, nose towards -Z",
        "# Converted from drone_hard_surface.obj by tools/prepare_drone_model.py",
        "mtllib %s" % mtllib,
        "usemtl %s" % sheet.material,
    ]
    for old in used_positions:
        lines.append("v %s %s %s" % tuple(fmt(v) for v in vertices[old - 1]))
    for u, v in sheet.vt:
        lines.append("vt %s %s" % (fmt(u), fmt(v)))
    for old in used_normals:
        lines.append("vn %s %s %s" % tuple(fmt(v) for v in normals[old - 1]))

    for group, faces in sheet.groups.items():
        lines.append("g %s" % group)
        for face in faces:
            values = []
            for position, normal, uv in zip(face["_positions"], face["_normals"], face["_uv"]):
                values.append("%d/%d/%d" % (position_index[position], uv, normal_index[normal]))
            lines.append("f " + " ".join(values))

    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def write_mtl(path, material, texture):
    """``map_Kd`` is mandatory: without it the loader hands back the missing sprite.

    The value has to be ``namespace:path`` without a ``.png`` suffix -- the OBJ
    loader appends ``textures/<path>.png`` itself, and the filesystem-path
    regex in ``resolveDirtyMaterial`` does not match a colon.
    """
    body = "\n".join([
        "newmtl %s" % material,
        "Ka 0.000 0.000 0.000",
        "Kd 1.000 1.000 1.000",
        "Ks 0.000 0.000 0.000",
        "d 1.0",
        "illum 1",
        "map_Kd %s" % texture,
        "",
    ])
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(body)


# -------------------------------------------------------------------------- build


def build(source_dir, output_dir):
    source = Source.read(os.path.join(source_dir, SOURCE))
    if not source.faces:
        raise SystemExit("%s has no faces" % SOURCE)

    offset = centre([rotate_nose_to_negative_z(p) for p in source.vertices])
    vertices = [tuple(round(c - offset[i], 6) for i, c in enumerate(rotate_nose_to_negative_z(p)))
                for p in source.vertices]
    normals = [tuple(round(c, 6) for c in rotate_nose_to_negative_z(n)) for n in source.normals]

    body = Sheet("drone", "drone_mat", SHEET)
    glow = Sheet("drone_glow", "drone_glow_mat", GLOW_SHEET)

    for index, (group, material, corners) in enumerate(source.faces):
        for tri, piece in enumerate(fan_triangulate(corners)):
            target = glow if material == GLOW_MATERIAL else body
            is_rotor = group in ROTOR_GROUPS
            table = ROTOR_COLOURS if is_rotor else BODY_COLOURS
            colour = GLOW_COLOUR if material == GLOW_MATERIAL else table.get(material)
            if colour is None:
                raise SystemExit("no palette entry for material %r" % material)

            points = [vertices[position] for position, _ in piece]
            name = GIMBAL_GROUP if (material == GLOW_MATERIAL and is_camera_window(points)) else group
            record = target.add(name, "f%d_%d" % (index, tri),
                                points, normals[piece[0][1]], colour,
                                material == GLOW_MATERIAL)
            record["_positions"] = [position + 1 for position, _ in piece]
            record["_normals"] = [normal + 1 for _, normal in piece]

    # The nose lamp is the one part of the model that is not in the Blender file. It is added
    # after every source face so the shared vertex pool is complete and the 1-based indices the
    # writer renumbers stay consistent.
    lamp_faces = add_nose_lamp(glow, vertices, normals)

    # Rectangles are assigned only once every face is known, because the packer
    # has to see them all to order them tallest-first.
    body.layout()
    glow.layout()

    # Rotors spin about their own axis. The bounding-box centre in XZ is exactly
    # the motor axis -- it matches the motor housing centre to six decimals --
    # and using the rotor's own Y keeps the pivot on the blade disc.
    pivots = {}
    for group in ROTOR_GROUPS:
        points = [p for face in body.groups.get(group, []) for p in face["_points"]]
        if not points:
            continue
        low = [min(p[i] for p in points) for i in range(3)]
        high = [max(p[i] for p in points) for i in range(3)]
        pivots[group] = tuple((low[i] + high[i]) / 2.0 for i in range(3))

    # The camera window tilts about its own centre, which is also roughly where a real sensor's
    # optical centre sits. Rotating about the back edge instead would swing the front face twice
    # as far for the same angle, and the window has only 8 mm of recess to swing inside.
    window = [p for face in glow.groups.get(GIMBAL_GROUP, []) for p in face["_points"]]
    if window:
        pivots[GIMBAL_GROUP] = centre(window)

    os.makedirs(output_dir, exist_ok=True)
    write_obj(os.path.join(output_dir, "drone.obj"), body, vertices, normals, "drone.mtl")
    write_obj(os.path.join(output_dir, "drone_glow.obj"), glow, vertices, normals, "drone_glow.mtl")
    write_mtl(os.path.join(output_dir, "drone.mtl"), body.material,
              "watchcraft:entity/recon_drone")
    write_mtl(os.path.join(output_dir, "drone_glow.mtl"), glow.material,
              "watchcraft:entity/recon_drone_glow")

    faces = []
    for sheet in (body, glow):
        for face in sheet.faces:
            # No "uv" key: the coordinates already live in the OBJ, and nothing that reads
            # the manifest wants them. On seven thousand faces that was 1.7 MB of duplicated
            # floats.
            faces.append({
                "group": face["group"],
                "name": face["name"],
                "rect": face["rect"],
                "colour": face["colour"],
                "glow": face["glow"],
            })
    # Both sheets contribute parts, and the glow faces live inside Drone_Body, so the same
    # component can appear twice. Merge by name so the manifest lists each part once.
    parts = {}
    for part in body.manifest_parts(pivots) + glow.manifest_parts(pivots):
        existing = parts.get(part["group"])
        if existing is None:
            parts[part["group"]] = dict(part)
            continue
        existing["faces"] += part["faces"]
        existing["bbox"] = [[min(existing["bbox"][0][i], part["bbox"][0][i]),
                             max(existing["bbox"][1][i], part["bbox"][1][i])]
                            for i in range(3)]

    manifest = {
        "unit": U,
        "sheet": list(SHEET),
        "glow_sheet": list(GLOW_SHEET),
        "parts": list(parts.values()),
        "faces": faces,
    }
    with open(os.path.join(output_dir, "drone_manifest.json"), "w",
              encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")

    report(body, glow, vertices, source, pivots)
    return output_dir


def report(body, glow, vertices, source, pivots):
    low = [min(p[i] for p in vertices) for i in range(3)]
    high = [max(p[i] for p in vertices) for i in range(3)]
    size = [high[i] - low[i] for i in range(3)]

    print("source: %d vertices, %d normals, %d faces" %
          (len(source.vertices), len(source.normals), len(source.faces)))
    print("output: %d body + %d glow quads (%d total)" %
          (len(body.faces), len(glow.faces), len(body.faces) + len(glow.faces)))
    print("bbox  : %.4f x %.4f x %.4f blocks, centre %s" %
          (size[0], size[1], size[2],
           [round((low[i] + high[i]) / 2.0, 6) for i in range(3)]))
    print("        y spans %.4f .. %.4f  => MODEL_Y = %.4f" % (low[1], high[1], -low[1]))
    print("sheet : body used %d/%d px, glow used %d/%d px" %
          (body.packer.y + body.packer.row_height, SHEET[1],
           glow.packer.y + glow.packer.row_height, GLOW_SHEET[1]))
    lamp = [p for face in glow.groups.get(NOSE_LAMP_GROUP, []) for p in face["_points"]]
    if lamp:
        low = [min(p[i] for p in lamp) for i in range(3)]
        high = [max(p[i] for p in lamp) for i in range(3)]
        print("lamp  : %d quads, %.4f x %.4f x %.4f blocks at (%.3f, %.3f, %.3f)" %
              (len(glow.groups[NOSE_LAMP_GROUP]),
               high[0] - low[0], high[1] - low[1], high[2] - low[2],
               (low[0] + high[0]) / 2.0, (low[1] + high[1]) / 2.0, (low[2] + high[2]) / 2.0))
    for group, pivot in pivots.items():
        print("pivot : %-10s (%.4f, %.4f, %.4f)" % (group, pivot[0], pivot[1], pivot[2]))

    # Winding: a closed shell swept from outside has positive signed volume. This
    # is the same test verify_mesh.py uses, and it is the only cheap way to know
    # the exporter did not hand us inside-out faces.
    for name, sheet, source_faces in (("body", body, source.faces), ("glow", glow, None)):
        volume = signed_volume(sheet)
        verdict = "outward" if volume > 0 else "INSIDE OUT"
        print("winding: %-5s %+.6f  %s" % (name, volume, verdict))
        if volume <= 0:
            raise SystemExit("%s mesh is inside out" % name)


def signed_volume(sheet):
    total = 0.0
    for faces in sheet.groups.values():
        for face in faces:
            points = face["_points"]
            a = points[0]
            for k in range(1, len(points) - 1):
                b, c = points[k], points[k + 1]
                total += (a[0] * (b[1] * c[2] - b[2] * c[1])
                          - a[1] * (b[0] * c[2] - b[2] * c[0])
                          + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6.0
    return total


#: What --install copies, and where each file belongs.
INSTALL_LAYOUT = (
    ("drone.obj", paths.MODEL_DIR),
    ("drone_glow.obj", paths.MODEL_DIR),
    ("drone.mtl", paths.MODEL_DIR),
    ("drone_glow.mtl", paths.MODEL_DIR),
    ("drone_manifest.json", os.path.dirname(paths.MANIFEST)),
)


def install(output_dir):
    for name, destination in INSTALL_LAYOUT:
        os.makedirs(destination, exist_ok=True)
        target = os.path.join(destination, name)
        shutil.copyfile(os.path.join(output_dir, name), target)
        print("installed %s -> %s" % (name, os.path.relpath(target, paths.ROOT)))


if __name__ == "__main__":
    arguments = [a for a in sys.argv[1:] if not a.startswith("--")]
    do_install = "--install" in sys.argv
    if not arguments:
        raise SystemExit("usage: prepare_drone_model.py <source_dir> [--install]")
    written = build(arguments[0], os.path.join(paths.BUILD, "modelgen"))
    if do_install:
        install(written)
