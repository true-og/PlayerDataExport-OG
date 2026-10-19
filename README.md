# PlayerDataExport-OG

Converts TrueOG Purpur's RocksDB player data back to vanilla `.dat` files, so a world can run on normal Purpur again. Maintained by [TrueOG Network](https://true-og.net/).

Current Target: Purpur 1.19.4

Java Version: 17

## Usage

Stop the server, then run as the user the server runs as:

```
java -cp <server>/libraries/org/rocksdb/rocksdbjni/10.10.1.1/rocksdbjni-10.10.1.1.jar PlayerDataExportOG.java <server>
```

`<server>` is the server's root directory, the one containing `server.properties`, the world folders and `libraries/`; use `.` when running from inside it.

Profiles are written to `<level>/playerdata/<uuid>.dat` and MyWorlds inventory groups to `<world>/playerdata/<uuid>.dat` (`<world>/DIM-1/playerdata` for a nether world, `<world>/DIM1/playerdata` for an end world), as the gzip-compressed NBT Minecraft writes itself. The stored record is copied unchanged, only the nested MyWorlds groups move to their own files. The database is moved aside, never deleted. Run it before every switch to vanilla; the fork imports the files again on its next start.

The tool refuses to run while the server is running or as a different user than the one owning the level folder, and writes nothing if anything is wrong: an unreadable record, a group whose world is not in MyWorlds' `worlds.yml`, or an existing file it would overwrite. It lists what it found. Add `--skip-unexportable` to export the rest and leave the listed data only in the archived database. Running it again after an interruption picks up where it stopped, and running it after a finished export checks that every exported file is still there. It exits with 0 only when the export finished.

If the worlds live in a world container other than the server directory, set it in `bukkit.yml` or pass the level as `<container>/<level name>`.

PlayerDataExport-OG is public domain software released under the terms of [The Unlicense](LICENSE).
