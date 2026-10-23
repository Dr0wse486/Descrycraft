# Hard-surface drone model → integration

> Evaluated: a Blender-exported hard-surface mesh.
> Against: the procedural model it replaced (`tools/generate_drone_mesh.py` → 21 parts / 260 faces).
> Every claim below is checked against `neoforge-21.1.251-sources.jar`.

---

## Verdict

**Usable, worth the swap — but it cannot be dropped into the assets folder.** Five hard conflicts
with NeoForge's OBJ path (no UVs, no `map_Kd`, n-gons, `o` lines, near-black `Kd`), all solvable in
Python — **no Blender, nothing on the player's side**. 6899 quads against 260, at 26× the cost.

---

## 1. Asset health check

| | Value | Verdict |
|---|---|---|
| Vertices / normals / UVs | 5156 / 7076 / **0** | ⚠️ **No UVs at all** |
| Faces | 5773 — 2836 tri, 2873 quad, 32 oct, 24 36-gon, 8 24-gon | ⚠️ 64 n-gons |
| Parts | `Drone_Body` + `Rotor_FL/FR/RL/RR` | ✅ The animation grain we want |
| Materials | 7, **none with `map_Kd`** | ⚠️ Magenta checkerboard |
| Bounding box | 1.009 × 0.423 × 1.037, centre (−0.010, 0.001, 0.000) | ✅ Already one block square |
| Facing | Nose at **+X** | Needs rotating to −Z |
| Winding | Signed volume positive on all five parts | ✅ Outward normals, no flipping |
| Shading | Global `s 1` + 7076 split normals | ✅ Hard edges baked into `vn` |
| Defects | 0 degenerate, 0 duplicate | ✅ Clean |

Material split, which decides the atlas layout:

| Material | Faces | Note |
|---|---|---|
| `Drone_Hull_Graphite` | 3639 | Body |
| `Drone_Carbon` | 1165 | Blades and plates |
| `Drone_Motor_Dark` | 440 | Four motor housings |
| `Drone_Trim_Gunmetal` | 256 | Hubs and trim |
| `Drone_Cyan_Emission` | **218** | Emissive: lens and four blade rings |
| `Drone_Sensor_Bezel` | 54 | Nose shroud |
| `Drone_Panel_Steel` | **1** | One plate on the spine |

> `.blend` and `.glb` are useless at runtime — NeoForge has no glTF loader, and the GLB's only real
> content is the four `Rotor_*` rotation channels, which we animate ourselves anyway.

---

## 2. Five things that break on contact (source level)

### ① No UVs ⇒ every face samples the whole texture

```java
Vec2 texCoord = index.length >= 2 && texCoords.size() > 0
        ? texCoords.get(index[1])
        : DEFAULT_COORDS[i];
```

No `vt` lines ⇒ `DEFAULT_COORDS`, the full `(0,0)…(1,1)` square. Not "untextured" — **the whole
atlas shrunk and stamped onto every face**.

### ② n-gons are not triangulated; vertices past the fourth are dropped

```java
for (int i = 0; i < 4; i++) {
    int[] index = indices[Math.min(i, indices.length - 1)];
```

A 36-gon becomes the degenerate quad `v0, v1, v2, v35`. **64 such faces** (motor end caps);
untouched, a spray of stray triangles.

### ③ No `map_Kd` ⇒ the airframe turns magenta

```java
if (tex == null)
    return new Material(TextureAtlas.LOCATION_BLOCKS, MissingTextureAtlasSprite.getLocation());
```

None of the seven `newmtl` blocks names a texture ⇒ every mesh resolves to `minecraft:missingno`.

### ④ `o` lines make group names nested paths

```java
case "o": {
    if (objAboveGroup || currentGroup == null) { objAboveGroup = true; ... }
    else { currentObject = model.new ModelObject(currentGroup.name() + "/" + name); ... }
}
```

`o Drone_Body` followed by `g Drone_Body` ⇒ **`Drone_Body/Drone_Body`**, and every `Transforms` key
has to spell that out. Deleting the `o` lines removes it.

### ⑤ `Kd` is near-black ⇒ pure black in Minecraft

`Kd 0.004 0.006 0.008` exists for PBR metal response. `entityCutout` only does lightmap × Lambert,
so `Kd 0.004` renders black. **The palette has to be rewritten.**

> Worth knowing: `makeQuad` multiplies `mat.diffuseColor` into the vertex colour and
> `putBulkData(..., readExistingColor = true)` multiplies it again — so `Kd` really does work as a
> colour. That is what makes fallback B in §4 viable.

---

## 3. The plan: one Python preprocessing pass

No Blender, no new dependencies — standard library under `tools/`. `tools/prepare_drone_model.py`
takes the source directory and writes the project assets:

```
tools/prepare_drone_model.py <source> [--install]
  ├─ 1. Parse and normalise
  │     · drop `o` lines, keep `g`
  │     · rotate 90° about +Y (nose +X → −Z), translate the bbox centre to the origin
  │     · assert all five parts have positive signed volume
  │
  ├─ 2. Triangulate n-gons as fans; keep quads as quads (half the vertices)
  │     · 2836 + 2873 + 64 n-gons → 6893 quads
  │
  ├─ 3. Split body / glow
  │     · Drone_Cyan_Emission → drone_glow.obj, then split out Gimbal_Lens and Nose_Lamp
  │     · the other six materials → drone.obj
  │
  ├─ 4. Generate UVs and shelf-pack them
  │     · one rectangle per face, sized by world dimensions (TEXELS_PER_U = 8, as before)
  │     · body → 512×512, glow → 128×128
  │
  ├─ 5. Rewrite the MTL
  │     · six body materials all map_Kd watchcraft:entity/recon_drone
  │     · glow material map_Kd watchcraft:entity/recon_drone_glow
  │     · Kd from the Minecraft palette
  │
  └─ 6. Write drone_manifest.json
        · per face: {group, material, UV rect, palette name} + every pivot
        · read by generate_textures.py, same path as before
```

**All six body materials point at one PNG.** `ModelMesh#bake` builds a mesh per material, but
`Mesh#render` looks up the `RenderType` by texture — same texture, same `RenderType`, **one shared
vertex buffer**. Seven materials still produce **two draw calls**: body and glow. Materials here
only route to a texture.

Java barely moves:

| File | Change |
|---|---|
| `DroneMeshes` | `ANIMATED` → `Rotor_FL/FR/RL/RR` + `Gimbal_Lens`; paths unchanged |
| `DroneRig` | Four rotor pivots plus the gimbal pivot (§5) |
| `DroneRenderer` | `MODEL_Y` 0.225 → **0.2113**. Yaw/pitch/bank signs need no change — rotation is baked into the OBJ |
| `ReconDroneEntity` | Override `getBoundingBoxForCulling()` to widen it (model 1.037 wide, AABB 0.8) |
| `generate_textures.py` | Read the manifest — same code, 260 faces becomes 6899 |

Two groups are not in the source: `Gimbal_Lens` is split out of the emissive material to be
animated, and `Nose_Lamp` is **authored in the converter** on the 13° slope under the camera.

---

## 4. Textures

### Measured requirement

| Target density | Body | Glow |
|---|---|---|
| 64 px/block (4 px per U) | 124² | 11² |
| 96 px/block (6 px per U) | 186² | 16² |
| **128 px/block (8 px per U, same as existing assets)** | **248²** | **21²** |

⇒ **512×512 body, 128×128 glow** — a factor of two to spare, and the same sizes already in the
repo. Both live in the manifest (`sheet`, `glow_sheet`) so generator and preview cannot drift.

### What the atlas looks like

Untextured by design (procedural PBR). The Minecraft equivalent is **one rectangle per face, one
colour, slight brightness jitter** — faces are flat, normals do the shading, the texture only says
what colour this face is. ≥ 4 px gets a 1 px darker outline (a chamfer); ≥ 6 px gets two or three
rivet dots; colour is palette ±4% per face, or 4675 faces collapse into one flat grey.

⚠️ The rectangle must stay **flat**. Outlines or noise drawn *inside* it turn the airframe into a
black grid — every face samples its rectangle at the same scale.

### Alternative A: real UV unwrapping

Only for things that cross faces — stencilled warnings, serials, hazard stripes. Needs a Smart UV
Project and a re-export. **Not done** — per-face colour reads closer to vanilla.

### Alternative B: zero-UV emergency route (not recommended, but it works)

No UVs, colour from `Kd`, one blank texture. Runs in an hour; seven flat colours, no panel detail,
every face stretching the atlas. **Fallback only.**

---

## 5. Animation and pivots

After the +90° Y rotation (`(x,y,z) → (z, y, −x)`):

| Part | Faces | Pivot | Rotor disc | Note |
|---|---|---|---|---|
| `Drone_Body` | 4675 | origin | — | Hull, arms, bezel, motors |
| `Rotor_FL` | 548 | (−0.328, 0.009653, −0.314) | 0.381 | Front, **low** |
| `Rotor_FR` | 548 | ( 0.328, 0.009653, −0.314) | 0.381 | Front, **low** |
| `Rotor_RL` | 548 | (−0.327, 0.115654,  0.314) | 0.381 | Rear, **0.106 higher** |
| `Rotor_RR` | 548 | ( 0.327, 0.115654,  0.314) | 0.381 | Rear, **0.106 higher** |
| `Gimbal_Lens` | 26 | (0.0001, 0.0482, −0.3041) | — | Stabilised window |
| `Nose_Lamp` | 6 | origin | — | Added by the converter |

- Pivots are exactly the motor-housing bbox centres in XZ, not estimates.
- The 0.106 step between front and rear is a stacked-quad design feature, not a modelling error.
- All four rotor bboxes are identical (0.3811³) — one transform, reused through `about(pivot, rot)`.
- Spin unchanged: diagonally paired, adjacent opposed (`{+1, −1, −1, +1}` at 42°/tick).

### The gimbal was kept

The first read said the sensor was a fixed shroud and the gimbal would have to go. It did not:
`Gimbal_Lens` is its own group and **cancels the airframe's pitch outright** rather than lagging
behind it. The airframe draws with `−pitch` about X, so cancelling it is the same rotation, sign
flipped.

Travel is clamped to **±12°**, and that number is measured: the window is 0.05 deep, 0.034 tall,
sunk 0.008 behind its bezel, so a corner pushes out by `0.025 sin a + 0.017 cos a` — past ~12° the
cyan escapes its red surround (±20 renders confirmed it). Beyond the limit it rides the stop.

---

## 6. Performance budget (the only real cost)

| | Before | New | Factor |
|---|---|---|---|
| Quads per drone | 260 | **6899** | 26.5× |
| Vertices per drone per frame | ~1040 | ~27596 | 26.5× |
| `putBulkData` calls | 260 | 6899 | 26.5× |
| Draw calls | 2 | 2 | — |
| Vertex data per frame | 33 KB | 221 KB | 6.7× |

For scale: vanilla's `ender_dragon` is ~900 faces, a player ~100. **6899 faces ≈ 7.7 ender dragons.**

- **It runs.** The GPU does not care about 7k faces; the cost is CPU-side `putBulkData` — four
  matrix transforms and a 32-byte write per face, ~0.3–0.8 ms per drone.
- **One drone is fine** — the normal case. **Four or more on screen is where to look.** While
  piloting your own drone is barely on screen; the expensive case is watching someone else fly
  from a distance, where detail matters least.
- **If it drops frames** (optional): a distance LOD past 24 blocks, `Drone_Body` only — about a
  third off. The real fix is a ~2500-face re-export.

---

## 7. The three decisions

| # | Question | Call |
|---|---|---|
| 1 | Keep the gimbal animation | **Kept**, ±12° — the lens is already a separate group, and the limit is where its corner crosses the bezel |
| 2 | Shrink to the 0.8 collision box | **No** — stay one block square, widen `getBoundingBoxForCulling()`; 0.79 drops the height to 0.33 and reads visibly small |
| 3 | Keep the procedural model | **Keep it** — `generate_drone_mesh.py` still does the item icon and the fallback; the two pipelines coexist |

---

## 8. Order it was built in

1. `prepare_drone_model.py` — parse / normalise / triangulate / split body+glow / UVs / MTL / manifest
2. `verify_model.py` — `verify_mesh.py`'s checks (winding, UV overlap, manifest consistency) minus
   the "1/128 grid" and "four-fold symmetry" rules, which do not apply to external models
3. `preview_mesh.py` on the new manifest — **confirm colours and UVs offline before going in game**
4. `generate_textures.py` → the 512² and 128² sheets
5. `DroneMeshes.ANIMATED` + `DroneRig` pivots + `DroneRenderer.MODEL_Y`
6. `ReconDroneEntity#getBoundingBoxForCulling()`
7. Build, then check in game: palette, emission, rotor direction, hurt flash, culling flicker
8. (Bonus) a 16×16 item icon from `preview_mesh.py`'s rasteriser, replacing the hand-drawn one

Steps 1–4 are all Python and verifiable offline; only from step 5 does Java get touched.

---

## Appendix: why not edit the `.blend`

No Blender on the build machine, so "unwrap and re-export in Blender" is unavailable — and not
needed. Every step above is plain geometry, and as a script the pipeline reruns with one command
next time the model changes. The one thing it cannot do is invent geometry — hence `Nose_Lamp`.
