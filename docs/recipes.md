# Player-facing recipes and item reference

This page groups the gameplay-facing information that was previously embedded in the main README so the project README stays concise and developer-oriented.

## Navigation

- [README.md](../README.md)
- [README.zh-Hans.md](../README.zh-Hans.md)

## Recon drone overview

The drone is the reference implementation of the mod: a deployable, flyable camera with a 64-block leash, remote link, module support, and optional combat functionality.

## Controls

- Right click a block — deploy the drone in front of that face
- Right click air / G — throw the drone
- Sneak + right click the drone — pack it back into the item
- Sneak + right click with a battery in hand — install or remove the battery
- V — link / unlink
- R — recall drone
- W A S D / Space / Shift — fly
- C — roller (tap = full roll, hold = continuous spin)
- Sprint while piloting and with an Attack Module — charge attack
- X while piloting and with an Attack Module — detonate warhead

## Commands

- `/watchcraft doorinteract` — show whether pilot door interaction is enabled
- `/watchcraft doorinteract true` — allow pilots to open doors, trapdoors, and fence gates
- `/watchcraft doorinteract false` — disable pilot door interaction
- `/watchcraft dimension` — print recorded/current dimension and the recall guard status

## Modules

The Module Workbench accepts a drone in the left slot, a board in the middle slot, and outputs the finished drone to the right slot.

| Module | Requires | Effect |
| --- | --- | --- |
| Drone Customization Module | — | Required to open the fitting bay and install other modules |
| Recon Boost Module | Customization Module | Widens the scan cone and reach; also gates the next two boards |
| Container Marker Module | Customization + Recon Boost | Marks nearby containers on the owner's screen |
| Alert Radar Module | Customization + Recon Boost | Warns of nearby creatures and players |
| Signal Module I | Customization Module | Adds 32 blocks to the leash |
| Signal Module II | Customization + Signal I | Adds another 32 blocks |
| Governor Removal Module | Customization Module | Raises the cruise ceiling |
| Attack Module | Customization Module | Enables charge attack and warhead detonation |

## Batteries

| Item | Charge | Flight time |
| --- | --- | --- |
| Copper Battery | 25% | ~12.5 minutes |
| Graphite Electrode | — | Half of a graphite pack; recipe requires two |
| Graphite Battery | 100% | ~50 minutes |

Battery consumption is one charge unit per 600 ticks (30 seconds). The pack lives on the item stack, so deploying, throwing, being destroyed, and recalling all preserve the same charge.

## Crafting recipes

### Module workbench

```text
Module workbench                   Drone chassis
 I I I     I = iron ingot           . I .     I = iron ingot
 I R I     R = redstone             I R I     R = redstone block
 I I I     . = empty                . I .     . = empty
```

### Drone propeller and recon drone

```text
Drone propeller                    Recon drone
 . N .     N = iron nugget          P . P     P = drone propeller
 I = iron ingot                     . C .     C = drone chassis
 . I .                              P . P
```

### Customization and attack modules

```text
Customization module               Attack module
 . I .     I = iron ingot           . G .     G = gunpowder
 R A R     R = redstone             I T I     I = iron ingot
 . I .     A = amethyst shard       . G .     T = tnt
```

### Recon, container, and radar boards

```text
Recon boost module                 Container marker module
 . A .     A = amethyst shard       . A .     A = amethyst shard
 G S G     G = glass                G C G     G = glowstone dust
 . A .     S = spyglass             . A .     C = chest
```

```text
Alert radar module
 . R .     R = redstone
 A S A     A = amethyst shard
 . R .     S = sculk sensor
```

### Signal and governor modules

```text
Signal module I                    Signal module II
 . C .     C = copper ingot         . G .     G = gold ingot
 R A R     R = redstone             R E R     R = redstone
 . I .     A = amethyst shard       . A .     E = ender pearl
           I = iron ingot                     A = amethyst shard
```

```text
Governor removal module
 . S .     S = sugar
 R G R     R = redstone
 . I .     G = gold ingot
           I = iron ingot
```

### Battery and graphite recipes

```text
Copper battery                     Graphite electrode
 G G G     G = gold ingot           . I .     I = iron block
 C R C     C = copper ingot         I D I     D = diamond
 C R C     R = redstone             . I .
```

```text
Graphite battery
 G G G     G = gold ingot
 E R E     E = graphite electrode
 C R C     R = redstone
           C = copper ingot
```

## Item effects summary

### Recon drone
- Deployable remote camera
- Works as a scout and remote observer
- Supports modules and battery loading
- Can maintain a link from a distance, with signal loss as range increases
- Can be recalled or destroyed

### Recon Boost Module
- Increases scan cone and range
- Enables higher-value reconnaissance modules

### Container Marker Module
- Marks nearby containers such as chests, barrels, shulkers, hoppers, and ender chests
- Helps locate valuable storage while remote-piloting

### Alert Radar Module
- Warns of nearby threatening entities and players
- Uses owner-centered detection rather than drone-centered detection

### Signal Modules
- Extend the drone's leash beyond the base 64-block range
- Useful for scouting farther away without losing the link

### Attack Module
- Allows a charge attack from the drone cockpit
- Adds a one-way explosive run with server-side validation

### Batteries
- Required for the drone to be usable
- Charge is retained on the item stack across deploy and recall transitions

## Notes

This page is intentionally limited to player-facing and gameplay-facing information. For technical implementation details, asset generation, tuning values, and source layout, see the main project README and the docs/ directory.
