import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

// Moves a world from TrueOG Purpur's RocksDB player data back to vanilla .dat files; see README.md.
public class PlayerDataExportOG {

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;
    private static final String SKIP_FLAG = "--skip-unexportable";
    private static final String BUKKIT_VALUES = "BukkitValues";
    private static final String GROUPS = "myworlds:groups";
    private static final String TEMP_SUFFIX = ".export-tmp";
    private static final String RECORD = "playerdataexport.done";
    private static final byte[] MIGRATED_KEY = "migrated".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            System.out.println("export failed, the database was not moved: " + e);
            code = EXIT_FAILED;
        }
        System.exit(code);
    }

    // One file to produce: where, its uncompressed named NBT, and what it holds.
    private record Output(Path file, byte[] nbt, String what) {
    }

    private static int run(String[] args) throws Exception {
        boolean skipUnexportable = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals(SKIP_FLAG)) {
                skipUnexportable = true;
            } else if (arg.startsWith("--")) {
                return usage("unknown option " + arg);
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty() || positional.size() > 2) {
            return usage(null);
        }
        Path server = Paths.get(positional.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(server)) {
            return usage("not a directory: " + server);
        }
        // Worlds live in the server's world container; every MyWorlds world sits beside the level there
        String level = positional.size() > 1 ? positional.get(1) : levelName(server);
        Path levelDir = worldContainer(server).resolve(level).normalize();
        Path db = levelDir.resolve("playerdatadb");
        if (!Files.isDirectory(db)) {
            return alreadyExported(levelDir, db);
        }
        // Folders and files this creates belong to whoever runs it, and the server must be able to write them again
        String wrongUser = wrongUser(levelDir);
        if (wrongUser != null) {
            System.out.println(wrongUser);
            return EXIT_FAILED;
        }
        // The server holds session.lock while the world is open and RocksDB's LOCK while the database is open
        Path sessionLock = levelDir.resolve("session.lock");
        Path databaseLock = db.resolve("LOCK");
        try (FileChannel session = FileChannel.open(sessionLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileChannel database = FileChannel.open(databaseLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock sessionHeld = tryLock(session);
            if (sessionHeld == null) {
                System.out.println("the server is running (" + sessionLock + " is locked); stop it first");
                return EXIT_FAILED;
            }
            FileLock databaseHeld = tryLock(database);
            if (databaseHeld == null) {
                System.out.println("another process has the database open (" + databaseLock + " is locked); close it first");
                return EXIT_FAILED;
            }
            return export(server, levelDir, db, skipUnexportable);
        }
    }

    // Null when a file this process creates in the level folder gets the folder's owner, else why it does not.
    private static String wrongUser(Path levelDir) throws IOException {
        // Owners only decide write access on POSIX file systems
        if (!levelDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return null;
        }
        Path probe = Files.createTempFile(levelDir, ".owner", TEMP_SUFFIX);
        try {
            UserPrincipal owner = Files.getOwner(levelDir);
            UserPrincipal self = Files.getOwner(probe);
            if (owner.equals(self)) {
                return null;
            }
            return levelDir + " belongs to " + owner.getName() + " but this runs as " + self.getName() + ", so the server could not save into the player data folders this would create; run it as " + owner.getName() + " (for example with sudo -u " + owner.getName() + ")";
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static int usage(String problem) {
        if (problem != null) {
            System.out.println(problem);
        }
        System.out.println("usage: java -cp <rocksdbjni jar> PlayerDataExportOG.java <server directory> [<level name>] [" + SKIP_FLAG + "]");
        return EXIT_USAGE;
    }

    // Without a database the export is done only if this tool recorded it and every file it wrote is still there.
    private static int alreadyExported(Path levelDir, Path db) throws IOException {
        Path record = levelDir.resolve(RECORD);
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("no database at " + db + "; if the worlds live in a subfolder, pass the level as <subfolder>/<level name>");
            return EXIT_FAILED;
        }
        List<String> lines = Files.readAllLines(record, StandardCharsets.UTF_8);
        Path archive = lines.isEmpty() || !lines.get(0).startsWith("archive ") ? null : Paths.get(lines.get(0).substring("archive ".length()));
        if (archive == null || !Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println(record + " names no archived database that still exists; nothing can be confirmed");
            return EXIT_FAILED;
        }
        List<String> missing = new ArrayList<>();
        int files = 0;
        for (String line : lines) {
            if (line.startsWith("file ")) {
                files++;
                Path file = Paths.get(line.substring("file ".length()));
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    missing.add(file.toString());
                }
            }
        }
        if (!missing.isEmpty()) {
            Map<String, List<String>> notes = new LinkedHashMap<>();
            notes.put("exported files that are gone", missing);
            print(notes);
            System.out.println("the export to " + archive + " finished, but the files above were removed since; restore the archive to " + db + " and run again");
            return EXIT_FAILED;
        }
        System.out.println("nothing to do: already exported to " + archive + ", all " + files + " files are still in place");
        return EXIT_OK;
    }

    private static int export(Path server, Path levelDir, Path db, boolean skipUnexportable) throws Exception {
        RocksDB.loadLibrary();
        Exporter exporter = new Exporter(levelDir.getParent(), levelDir.resolve("playerdata"), myWorldsConfig(server));
        List<byte[]> names = RocksDB.listColumnFamilies(new Options(), db.toString());
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions();
        for (byte[] name : names) {
            descriptors.add(new ColumnFamilyDescriptor(name, columnFamilyOptions));
        }
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        Set<String> otherFamilies = new TreeSet<>();
        int unknownKeys = 0;
        Map<Path, String> claimed = new LinkedHashMap<>();
        Set<Path> pending = new HashSet<>();
        Set<Path> targetDirs = new TreeSet<>();
        int alreadyPresent = 0;
        int written = 0;
        try (RocksDB rocksDB = RocksDB.openReadOnly(new DBOptions(), db.toString(), descriptors, handles)) {
            ColumnFamilyHandle defaultHandle = null;
            for (int i = 0; i < names.size(); i++) {
                String name = new String(names.get(i), StandardCharsets.UTF_8);
                if (name.equals("default")) {
                    defaultHandle = handles.get(i);
                } else {
                    otherFamilies.add(name);
                }
            }

            // Pass 1 plans; nothing is written unless the whole plan is clean
            Map<String, List<String>> conflicts = new LinkedHashMap<>();
            try (RocksIterator it = rocksDB.newIterator(defaultHandle)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    byte[] key = it.key();
                    if (key.length != 16) {
                        if (!Arrays.equals(key, MIGRATED_KEY)) {
                            unknownKeys++;
                        }
                        continue;
                    }
                    for (Output output : exporter.outputs(uuid(key), it.value(), true)) {
                        Path target = target(output.file());
                        String obstruction = obstruction(target.getParent());
                        if (obstruction != null) {
                            note(conflicts, "a path on the way to the file is not a directory", obstruction + " (needed for " + target + ")");
                            continue;
                        }
                        String earlier = claimed.putIfAbsent(target, output.what());
                        if (earlier != null) {
                            note(conflicts, "two records map to the same file", target + " (" + earlier + ", " + output.what() + ")");
                            continue;
                        }
                        targetDirs.add(target.getParent());
                        if (Files.isSymbolicLink(target)) {
                            note(conflicts, "file is a symbolic link", target.toString());
                        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && sameContent(target, output.nbt())) {
                                alreadyPresent++;
                            } else {
                                note(conflicts, "file already exists with different content", target.toString());
                            }
                        } else {
                            pending.add(target);
                        }
                    }
                }
                // A read error ends iteration exactly like the end of the data; only status() tells them apart
                it.status();
            }
            // A player file this database did not produce would mix two histories: vanilla and MyWorlds both read it
            Path playerDir = canonical(levelDir.resolve("playerdata"));
            targetDirs.add(playerDir);
            for (Path dir : targetDirs) {
                for (Path file : playerFiles(dir)) {
                    if (!claimed.containsKey(file)) {
                        note(conflicts, "player file the database does not have (it may be the only copy, e.g. after a failed import; move it somewhere safe, do not delete it)", file.toString());
                    }
                }
            }

            if (!conflicts.isEmpty()) {
                print(conflicts);
                System.out.println("nothing was written and the database was not moved; resolve the files above and run again");
                return EXIT_FAILED;
            }
            if (!exporter.problems.isEmpty()) {
                print(exporter.problems);
                if (!skipUnexportable) {
                    System.out.println("nothing was written and the database was not moved; fix the above or run again with " + SKIP_FLAG + " to leave them only in the archived database");
                    return EXIT_FAILED;
                }
                System.out.println(SKIP_FLAG + ": the above stay only in the archived database");
            }

            // Pass 2 writes from the same read-only snapshot
            try (RocksIterator it = rocksDB.newIterator(defaultHandle)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    if (it.key().length != 16) {
                        continue;
                    }
                    for (Output output : exporter.outputs(uuid(it.key()), it.value(), false)) {
                        Path target = target(output.file());
                        if (pending.contains(target)) {
                            publish(target, output.nbt());
                            written++;
                        }
                    }
                }
                it.status();
            }
            if (written != pending.size()) {
                throw new IOException("wrote " + written + " files but planned " + pending.size());
            }

            // Pass 3 checks every planned file, written now or found from an earlier run, right before the database moves
            List<String> wrong = new ArrayList<>();
            try (RocksIterator it = rocksDB.newIterator(defaultHandle)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    if (it.key().length != 16) {
                        continue;
                    }
                    for (Output output : exporter.outputs(uuid(it.key()), it.value(), false)) {
                        Path target = target(output.file());
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !sameContent(target, output.nbt())) {
                            wrong.add(target.toString());
                        }
                    }
                }
                it.status();
            }
            if (!wrong.isEmpty()) {
                Map<String, List<String>> notes = new LinkedHashMap<>();
                notes.put("files changed or vanished during the export", wrong);
                print(notes);
                System.out.println("the database was not moved; run again once nothing else touches these folders");
                return EXIT_FAILED;
            }
        }

        // The old archive goes first: the fork refuses to boot when playerdatamigrated already exists
        long now = System.currentTimeMillis();
        Path migrated = levelDir.resolve("playerdatamigrated");
        if (Files.exists(migrated, LinkOption.NOFOLLOW_LINKS)) {
            Path aside = levelDir.resolve("playerdatamigrated-" + now);
            Files.move(migrated, aside);
            System.out.println("moved " + migrated + " to " + aside + " so the fork can archive the player data directory again");
        }
        // The record is written before the database moves, so a missing database always comes with one
        Path archive = levelDir.resolve("playerdatadb-exported-" + now);
        StringBuilder record = new StringBuilder("archive ").append(archive).append('\n');
        for (Path file : claimed.keySet()) {
            record.append("file ").append(file).append('\n');
        }
        Path recordTemp = levelDir.resolve("." + RECORD + "." + UUID.randomUUID() + TEMP_SUFFIX);
        Files.writeString(recordTemp, record, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(recordTemp, levelDir.resolve(RECORD), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(db, archive);

        System.out.println("wrote " + written + " files (" + exporter.profiles + " profiles, " + exporter.groups + " MyWorlds group profiles), " + alreadyPresent + " were already in place");
        if (!otherFamilies.isEmpty() || unknownKeys > 0) {
            System.out.println("not player data, left in the archive: column families " + otherFamilies + ", " + unknownKeys + " other keys");
        }
        System.out.println("moved the database to " + archive + "; the fork imports the files again on its next start");
        return EXIT_OK;
    }

    // Splits stored records into the vanilla files MyWorlds and the server read.
    private static final class Exporter {
        private final Path container;
        private final Path playerDir;
        private final Map<String, List<MyWorldsWorld>> worlds;
        private final Map<String, Path> groupDirs = new HashMap<>();
        private final Map<String, String> groupFailures = new HashMap<>();
        private final Map<String, List<String>> problems = new LinkedHashMap<>();
        private int profiles;
        private int groups;

        Exporter(Path container, Path playerDir, Map<String, List<MyWorldsWorld>> worlds) {
            this.container = container;
            this.playerDir = playerDir;
            this.worlds = worlds;
        }

        // Problems and counts are only recorded on the planning pass.
        List<Output> outputs(UUID uuid, byte[] value, boolean planning) throws IOException {
            List<Output> outputs = new ArrayList<>();
            Tag root;
            try {
                root = Nbt.read(new DataInputStream(new ByteArrayInputStream(value)));
            } catch (IOException | RuntimeException e) {
                problem(planning, "stored profile can not be read", uuid + " (" + e + ")");
                return outputs;
            }
            if (root.type != 10) {
                problem(planning, "stored profile is not a compound", uuid.toString());
                return outputs;
            }
            Map<String, Tag> record = root.compound();
            Tag values = record.get(BUKKIT_VALUES);
            Tag groupTags = null;
            if (values != null && values.type == 10) {
                groupTags = values.compound().remove(GROUPS);
                if (values.compound().isEmpty()) {
                    record.remove(BUKKIT_VALUES);
                }
            }
            if (groupTags != null && groupTags.type != 10) {
                problem(planning, "MyWorlds groups entry is not a compound", uuid.toString());
            } else if (groupTags != null) {
                for (Map.Entry<String, Tag> group : groupTags.compound().entrySet()) {
                    if (group.getValue().type != 10) {
                        problem(planning, "MyWorlds group is not a compound", uuid + " group '" + group.getKey() + "'");
                        continue;
                    }
                    Path dir = groupDir(group.getKey());
                    if (dir == null) {
                        problem(planning, groupFailures.get(group.getKey()), uuid.toString());
                        continue;
                    }
                    outputs.add(new Output(dir.resolve(uuid + ".dat"), Nbt.bytes(group.getValue()), uuid + " group " + group.getKey()));
                    if (planning) {
                        groups++;
                    }
                }
            }
            // Only a record with a position is a vanilla profile; without one vanilla would place the player at 0,0,0
            if (record.containsKey("Pos")) {
                outputs.add(new Output(playerDir.resolve(uuid + ".dat"), Nbt.bytes(root), uuid + " profile"));
                if (planning) {
                    profiles++;
                }
            } else {
                Set<String> residual = new TreeSet<>(record.keySet());
                residual.remove("DataVersion");
                if (!residual.isEmpty()) {
                    problem(planning, "record has data but no position, so vanilla can not load it", uuid + " " + residual);
                }
            }
            return outputs;
        }

        private void problem(boolean planning, String what, String who) {
            if (planning) {
                note(problems, what, who);
            }
        }

        // Where MyWorlds' file backend reads a group's profiles, or null with the reason in groupFailures.
        private Path groupDir(String group) throws IOException {
            if (!groupDirs.containsKey(group) && !groupFailures.containsKey(group)) {
                String failure = resolve(group);
                if (failure != null) {
                    groupFailures.put(group, failure);
                }
            }
            return groupDirs.get(group);
        }

        private String resolve(String group) throws IOException {
            if (worlds == null) {
                return "MyWorlds groups exist but there is no plugins/MyWorlds/worlds.yml to place them by";
            }
            List<MyWorldsWorld> matches = worlds.getOrDefault(group.toLowerCase(Locale.ROOT), List.of());
            if (matches.isEmpty()) {
                return "MyWorlds has no world named '" + group + "'";
            }
            if (matches.size() > 1) {
                return "several MyWorlds worlds match '" + group + "' ignoring case";
            }
            MyWorldsWorld world = matches.get(0);
            if (world.dimension() == null) {
                return "MyWorlds world '" + world.name() + "' is not written the way MyWorlds saves worlds.yml (indent, tab, # comment or no environment); start and stop the server once so MyWorlds rewrites it, then run again";
            }
            if (world.name().isEmpty() || world.name().equals(".") || world.name().equals("..") || world.name().contains("/") || world.name().contains("\\") || world.name().indexOf('\0') >= 0) {
                return "MyWorlds world '" + world.name() + "' is not a plain folder name";
            }
            // MyWorlds finds the folder ignoring case, like here
            Path folder = container.resolve(world.name());
            if (!Files.isDirectory(folder)) {
                List<Path> found = new ArrayList<>();
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(container, Files::isDirectory)) {
                    for (Path dir : dirs) {
                        if (dir.getFileName().toString().equalsIgnoreCase(world.name())) {
                            found.add(dir);
                        }
                    }
                }
                if (found.size() != 1) {
                    return found.isEmpty() ? "no world folder for MyWorlds world '" + world.name() + "'" : "several folders match MyWorlds world '" + world.name() + "' ignoring case: " + found;
                }
                folder = found.get(0);
            }
            // A loaded nether or end world keeps player data in its dimension folder; MyWorlds reads the root while it is unloaded
            Path base = folder;
            if (!world.dimension().isEmpty()) {
                if (!world.loaded()) {
                    return "MyWorlds world '" + world.name() + "' is a " + world.dimension() + " world that does not load at startup, so MyWorlds reads its group data from two places; set loaded: true in worlds.yml and run again";
                }
                base = folder.resolve(world.dimension());
            }
            groupDirs.put(group, base.resolve("playerdata"));
            return null;
        }
    }

    // A world from MyWorlds' worlds.yml: its real name, its dimension folder (empty for the overworld kind), and whether it loads at startup.
    private record MyWorldsWorld(String name, String dimension, boolean loaded) {
    }

    // MyWorlds' worlds.yml by lower-case world name, or null without one. Each top-level node is a world.
    private static Map<String, List<MyWorldsWorld>> myWorldsConfig(Path server) throws IOException {
        Path file = server.resolve("plugins").resolve("MyWorlds").resolve("worlds.yml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Map<String, Map<String, String>> nodes = new LinkedHashMap<>();
        Set<String> unread = new HashSet<>();
        Map<String, String> current = null;
        String key = null;
        boolean first = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                current = null;
                String trimmed = line.trim();
                if (trimmed.endsWith(":")) {
                    current = new HashMap<>();
                    key = unquote(trimmed.substring(0, trimmed.length() - 1));
                    nodes.put(key, current);
                    first = true;
                }
            } else if (current != null) {
                // MyWorlds writes two-space children; another indent or a tab is a hand edit this reader can not follow
                boolean child = line.startsWith("  ") && !Character.isWhitespace(line.charAt(2));
                if (first && !child || line.substring(0, line.length() - line.stripLeading().length()).indexOf('\t') >= 0) {
                    unread.add(key);
                }
                first = false;
                int colon = line.indexOf(':');
                if (child && colon > 0) {
                    String field = line.substring(2, colon).trim();
                    String raw = line.substring(colon + 1).trim();
                    String value = unquote(raw);
                    // For MyWorlds a # after whitespace starts a comment, so the value is not what it reads
                    if (Set.of("name", "environment", "loaded").contains(field) && value.equals(raw) && raw.matches(".*\\s#.*")) {
                        unread.add(key);
                    }
                    current.put(field, value);
                }
            }
        }
        Map<String, List<MyWorldsWorld>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> node : nodes.entrySet()) {
            Map<String, String> fields = node.getValue();
            String name = fields.getOrDefault("name", node.getKey());
            // Without an environment MyWorlds guesses from the name and folders; only the form it writes itself is trusted
            String environment = fields.get("environment");
            String dimension = environment == null || unread.contains(node.getKey()) ? null : dimension(environment);
            MyWorldsWorld world = new MyWorldsWorld(name, dimension, "true".equalsIgnoreCase(fields.get("loaded")));
            result.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(world);
        }
        return result;
    }

    // The dimension folder of a MyWorlds environment mode, matching its WorldMode.get parsing: nether_flat is a nether, dim1 an end.
    private static String dimension(String mode) {
        String text = mode.toLowerCase(Locale.ROOT);
        while (true) {
            if (text.equals("nether") || text.equals("dim-1") || text.startsWith("nether_")) {
                return "DIM-1";
            }
            if (text.equals("the_end") || text.equals("dim1") || text.startsWith("the_end_")) {
                return "DIM1";
            }
            if (text.equals("normal") || text.equals("flat") || text.equals("largebiomes") || text.startsWith("normal_")) {
                return "";
            }
            int underscore = text.indexOf('_');
            if (underscore < 0) {
                return "";
            }
            text = text.substring(underscore + 1);
        }
    }

    // A file's path with its directory resolved through links; the name itself is kept so a link there is still seen.
    private static Path target(Path file) throws IOException {
        return canonical(file.getParent()).resolve(file.getFileName());
    }

    // The path with every existing ancestor resolved through links, so two names for one directory compare equal.
    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        List<Path> rest = new ArrayList<>();
        while (existing != null && !Files.exists(existing)) {
            rest.add(0, existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path result = existing.toRealPath();
        for (Path part : rest) {
            result = result.resolve(part);
        }
        return result;
    }

    // The first existing ancestor that is not a directory, or null when the directory can be created.
    private static String obstruction(Path dir) {
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                return Files.isDirectory(p) ? null : p.toString();
            }
        }
        return null;
    }

    // Regular <uuid>.dat files in a directory, the ones the fork's import and MyWorlds read.
    private static List<Path> playerFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.dat")) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    UUID.fromString(name.substring(0, name.length() - 4));
                    files.add(file);
                } catch (IllegalArgumentException e) {
                    // not a player file
                }
            }
        }
        return files;
    }

    private static void note(Map<String, List<String>> notes, String what, String who) {
        notes.computeIfAbsent(what, k -> new ArrayList<>()).add(who);
    }

    private static void print(Map<String, List<String>> notes) {
        for (Map.Entry<String, List<String>> entry : notes.entrySet()) {
            List<String> who = entry.getValue();
            System.out.println(entry.getKey() + " (" + who.size() + "):");
            for (String item : who.subList(0, Math.min(who.size(), 20))) {
                System.out.println("  " + item);
            }
            if (who.size() > 20) {
                System.out.println("  ... and " + (who.size() - 20) + " more");
            }
        }
    }

    private static UUID uuid(byte[] key) {
        ByteBuffer buffer = ByteBuffer.wrap(key);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    // Writes a fresh file beside the target and links it into place, which fails instead of replacing anything.
    private static void publish(Path target, byte[] nbt) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path temp = dir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                GZIPOutputStream gzip = new GZIPOutputStream(Channels.newOutputStream(channel));
                gzip.write(nbt);
                gzip.finish();
                gzip.flush();
                channel.force(true);
            }
            try {
                Files.createLink(target, temp);
            } catch (FileAlreadyExistsException e) {
                throw new IOException(target + " appeared while the export was running", e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean sameContent(Path file, byte[] nbt) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            return Arrays.equals(in.readAllBytes(), nbt);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    // Read the way the server reads it, so both agree on the level name.
    private static String levelName(Path server) throws IOException {
        Path file = server.resolve("server.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        return properties.getProperty("level-name", "world");
    }

    // The settings.world-container directory from bukkit.yml, the server directory when unset.
    private static Path worldContainer(Path server) throws IOException {
        Path file = server.resolve("bukkit.yml");
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("world-container:")) {
                    String value = unquote(trimmed.substring("world-container:".length()).trim());
                    if (!value.isEmpty()) {
                        return server.resolve(value).normalize();
                    }
                }
            }
        }
        return server;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'") || value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // Minimal NBT tree: type id plus value, written back exactly as read.
    static final class Tag {
        final int type;
        final Object value;

        Tag(int type, Object value) {
            this.type = type;
            this.value = value;
        }

        @SuppressWarnings("unchecked")
        Map<String, Tag> compound() {
            return (Map<String, Tag>) value;
        }
    }

    static final class ListValue {
        final int elementType;
        final List<Tag> elements = new ArrayList<>();

        ListValue(int elementType) {
            this.elementType = elementType;
        }
    }

    static final class Nbt {
        static Tag read(DataInput in) throws IOException {
            int type = in.readByte();
            in.readUTF();
            return readPayload(in, type);
        }

        // Uncompressed named NBT with an empty root name, the form vanilla gzips into a .dat.
        static byte[] bytes(Tag root) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(root.type);
            out.writeUTF("");
            writePayload(out, root);
            out.flush();
            return bytes.toByteArray();
        }

        static Tag readPayload(DataInput in, int type) throws IOException {
            switch (type) {
                case 1: return new Tag(type, in.readByte());
                case 2: return new Tag(type, in.readShort());
                case 3: return new Tag(type, in.readInt());
                case 4: return new Tag(type, in.readLong());
                case 5: return new Tag(type, in.readFloat());
                case 6: return new Tag(type, in.readDouble());
                case 7: {
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    return new Tag(type, data);
                }
                case 8: return new Tag(type, in.readUTF());
                case 9: {
                    int elementType = in.readByte();
                    int size = in.readInt();
                    ListValue list = new ListValue(elementType);
                    for (int i = 0; i < size; i++) {
                        list.elements.add(readPayload(in, elementType));
                    }
                    return new Tag(type, list);
                }
                case 10: {
                    Map<String, Tag> compound = new LinkedHashMap<>();
                    int childType;
                    while ((childType = in.readByte()) != 0) {
                        String name = in.readUTF();
                        compound.put(name, readPayload(in, childType));
                    }
                    return new Tag(type, compound);
                }
                case 11: {
                    int[] data = new int[in.readInt()];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = in.readInt();
                    }
                    return new Tag(type, data);
                }
                case 12: {
                    long[] data = new long[in.readInt()];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = in.readLong();
                    }
                    return new Tag(type, data);
                }
                default: throw new IOException("Unknown tag type " + type);
            }
        }

        static void writePayload(DataOutput out, Tag tag) throws IOException {
            switch (tag.type) {
                case 1: out.writeByte((Byte) tag.value); break;
                case 2: out.writeShort((Short) tag.value); break;
                case 3: out.writeInt((Integer) tag.value); break;
                case 4: out.writeLong((Long) tag.value); break;
                case 5: out.writeFloat((Float) tag.value); break;
                case 6: out.writeDouble((Double) tag.value); break;
                case 7: {
                    byte[] data = (byte[]) tag.value;
                    out.writeInt(data.length);
                    out.write(data);
                    break;
                }
                case 8: out.writeUTF((String) tag.value); break;
                case 9: {
                    ListValue list = (ListValue) tag.value;
                    out.writeByte(list.elementType);
                    out.writeInt(list.elements.size());
                    for (Tag element : list.elements) {
                        writePayload(out, element);
                    }
                    break;
                }
                case 10: {
                    for (Map.Entry<String, Tag> entry : tag.compound().entrySet()) {
                        out.writeByte(entry.getValue().type);
                        out.writeUTF(entry.getKey());
                        writePayload(out, entry.getValue());
                    }
                    out.writeByte(0);
                    break;
                }
                case 11: {
                    int[] data = (int[]) tag.value;
                    out.writeInt(data.length);
                    for (int v : data) {
                        out.writeInt(v);
                    }
                    break;
                }
                case 12: {
                    long[] data = (long[]) tag.value;
                    out.writeInt(data.length);
                    for (long v : data) {
                        out.writeLong(v);
                    }
                    break;
                }
                default: throw new IOException("Unknown tag type " + tag.type);
            }
        }
    }
}
