# Waystones Squaremap Addon

A client-side Minecraft mod that transforms the Waystones selection screen with an interactive, top-down terrain map. See exactly where each waystone is located relative to the surrounding landscape, with persistent tiles that remember everywhere you've explored.

Inspired by [squaremap](https://github.com/jpenilla/squaremap) by jpenilla - this addon brings the same beautiful tile-based mapping to your Waystones menu.

## What Does This Do?

When you open your Waystones menu, instead of just seeing a list of waystone names, you'll see a live, squaremap-style terrain view showing:
- **Real terrain rendering** - Mountains, forests, oceans, and structures displayed with Minecraft's native color palette
- **Waystone locations** - Visual markers showing exactly where each waystone sits in the world
- **Interactive navigation** - Click and drag to pan, scroll to zoom, click a waystone to teleport
- **Dimension switching** - Clicking a waystone in the Nether or End automatically switches the map view to that dimension
- **Persistent exploration** - Once an area is mapped, it stays visible even after logging out or changing dimensions

All mapping happens client-side using chunks already loaded in your world. No server-side components required.

## Features

### Core Mapping
- **512×512 tile generation** from loaded chunks using Minecraft's heightmap and color system
- **Disk-based persistence** - tiles are cached per server and dimension, surviving restarts
- **Multi-dimension support** - separate maps for Overworld, Nether, and End
- **Automatic updates** - areas re-map as terrain changes or new chunks load
- **Smart caching** - 3-second cooldown prevents redundant tile regeneration

### UI Enhancements
- **Favorites system** - star your most-used waystones and reorder them via drag-and-drop
- **Dimension filtering** - toggle between dimensions or view all at once
- **Party integration** - see party member locations when using Open Parties and Claims (optional)
- **Auto-centering** - selecting a waystone automatically centers the map on its location

### Performance
- **Throttled updates** - mapping only triggers when crossing tile boundaries or changing render distance
- **Async tile loading** - non-blocking disk reads keep the UI responsive
- **ChunkStatus.FULL validation** - only maps chunks that are completely loaded, preventing incomplete data

## Installation

1. Make sure you have **Minecraft 1.21.1** with **Fabric Loader** installed
2. Download the latest release from the [Releases](../../releases) page
3. Place the `.jar` file in your `.minecraft/mods` folder
4. Ensure you have the **Waystones** mod installed (required dependency)
5. Launch Minecraft with the Fabric profile

## Building from Source

### Prerequisites
- Java 21 or newer
- Git

### Build Steps

Clone this repository and navigate to the addon folder:

```bash
git clone <repository-url>
cd squaremap-master/waystones-squaremap-addon
```

Build the mod:

```bash
# Linux/Mac
./gradlew clean build

# Windows
.\gradlew.bat clean build
```

The compiled mod will be in `build/libs/waystones-squaremap-addon-<version>.jar`

## Usage

1. **Open the Waystones menu** - Right-click any waystone or use the Warp Stone item
2. **Explore the map** - Click and drag to pan around, scroll wheel to zoom in/out
3. **Switch dimensions** - Use the dimension filter buttons or click a waystone in another dimension
4. **Mark favorites** - Click the star icon next to waystone names to add them to your favorites list
5. **Teleport** - Click any waystone marker on the map or select from the list to travel

### Tips
- The map shows terrain based on your render distance - explore more to see more
- Tiles are generated from fully loaded chunks, so areas may appear blank until visited
- Each server gets its own cache folder to prevent conflicts
- Delete cached tiles from the cache folder (see below) to force a fresh map generation

## Cache Location

Map tiles are stored in your Minecraft instance directory:

```
<minecraft>/.minecraft/waystones_sqmap_cache/<server>_<port>_<dimension>/
```

For example:
```
.minecraft/waystones_sqmap_cache/localhost_25565_minecraft_overworld/
.minecraft/waystones_sqmap_cache/localhost_25565_minecraft_the_nether/
```

Each tile is a 512×512 PNG named by its world coordinates (e.g., `tile_0_0.png`).

## Troubleshooting

**Q: The map is blank or only showing small patches**  
A: The mod only maps chunks that are fully loaded on your client. Explore around waystones to generate terrain data. Areas outside your render distance won't be mapped until you visit them.

**Q: Map doesn't update when I change dimensions**  
A: Click a waystone in the target dimension - the map will automatically switch. You can also use the dimension filter buttons at the top of the screen.

**Q: Some tiles look corrupted or outdated**  
A: Delete the cache folder for that dimension and re-explore the area. The mapper will regenerate fresh tiles.

**Q: Performance issues or lag when opening the Waystones menu**  
A: The mod only generates tiles for areas within your render distance when you move between tile boundaries. If you have an extremely high render distance (20+ chunks), consider reducing it slightly.

**Q: Tiles aren't persisting between sessions**  
A: Check that the cache folder is being created and written to. File permission issues could prevent tile saving.

## How It Works

1. **Chunk Detection** - When you move, the mod checks if you've crossed into a new 512×512 block tile region
2. **Tile Generation** - For any visible tile within render distance, the mod reads chunk data using `ChunkStatus.FULL`
3. **Color Mapping** - Each block's top surface is sampled using Minecraft's MapColor system (same as vanilla maps)
4. **Disk Cache** - Generated tiles are saved as PNGs in the cache folder
5. **UI Rendering** - The Waystones screen loads cached tiles and renders them as a scrollable, zoomable map

This approach ensures minimal performance impact - tiles are only generated once and loaded from disk thereafter.

## Compatibility

- **Minecraft Version**: 1.21.1
- **Mod Loader**: Fabric
- **Required Dependencies**: Waystones (Fabric version)
- **Optional Dependencies**: Open Parties and Claims (for party member markers)

Tested with Fabric Loader 0.15.0+ and Fabric API.

## Credits

This mod is inspired by [squaremap](https://github.com/jpenilla/squaremap) by jpenilla - an excellent server-side mapping plugin. We adapted the tile-based rendering approach for client-side use in the Waystones UI.

## License

See the root repository license.
