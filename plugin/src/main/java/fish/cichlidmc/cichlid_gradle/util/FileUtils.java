package fish.cichlidmc.cichlid_gradle.util;

import fish.cichlidmc.cichlid_gradle.CichlidGradlePlugin;
import fish.cichlidmc.pistonmetaparser.util.Downloadable;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class FileUtils {
    public static final Comparator<File> FILE_SORTER = Comparator.comparing(File::getName);

    private static final Logger logger = Logging.getLogger(FileUtils.class);

    public static InputStream openDownloadStream(Downloadable downloadable) throws IOException {
        URLConnection connection = downloadable.url().toURL().openConnection();
        connection.addRequestProperty("User-Agent", CichlidGradlePlugin.NAME + " / " + CichlidGradlePlugin.VERSION);
        long length = connection.getContentLengthLong();
        if (downloadable.size() != length)
            throw new RuntimeException("Downloaded file size of " + length + " did not match expected size of " + downloadable.size());
        return connection.getInputStream();
    }

    public static String sha1(Path file) {
        try {
            MessageDigest messageDigest = Hashes.createDigest("SHA-1");
            byte[] data = Files.readAllBytes(file);
            return Hashes.format(messageDigest.digest(data));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha1All(Collection<File> files) {
        Collection<File> sorted = new TreeSet<>(FILE_SORTER);
        sorted.addAll(files);

        try {
            MessageDigest messageDigest = Hashes.createDigest("SHA-1");
            for (File file : sorted) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                messageDigest.update(bytes);
            }
            return Hashes.format(messageDigest.digest());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void ensureCreated(Path file) throws IOException {
        if (!Files.exists(file)) {
            // can't use open option, gradle breaks it
            Path folder = file.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.createFile(file);
        }
    }

    @Nullable
    public static Path getSingleFileInDirectory(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> list = stream.toList();
            if (list.size() != 1) {
                logger.warn("Expected directory {} to contain 1 path, but it contained {}", dir, list.size());
                list.forEach(path -> logger.warn(path.toString()));
                return null;
            }

            return list.getFirst();
        }
    }

    public static void unzip(Path target, Path dest, Predicate<String> filter) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(target)) {
            Path root = fs.getRootDirectories().iterator().next();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (filter.test(fileName)) {
                        Path fileDest = dest.resolve(root.relativize(file).toString());
                        if (!Files.exists(fileDest))
                            FileUtils.copy(file, fileDest);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static void removeJarSignatures(Path path) throws IOException {
        // nuke META-INF, but preserve Main-Class for reading later
        String mainClass;
        try (JarFile jar = new JarFile(path.toFile())) {
            mainClass = jar.getManifest().getMainAttributes().getValue("Main-Class");
        }

        if (mainClass == null)
            return;

        try (FileSystem fs = FileSystems.newFileSystem(path)) {
            Path metaInf = fs.getPath("META-INF");
            deleteRecursively(metaInf);
            Files.createDirectories(metaInf);
            Path manifest = metaInf.resolve("MANIFEST.MF");
            Files.writeString(manifest, "Manifest-Version: 1.0\nMain-Class: " + mainClass + '\n'); // trailing break is critical
        }
    }

    public static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static byte[] readAllBytesUnchecked(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void copy(Path from, Path to) throws IOException {
        Path parent = to.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.copy(from, to);
    }

    public static void move(Path from, Path to) throws IOException {
        Path parent = to.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path createTempFile(Path target) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        return Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
    }

    public static Path createTempDirectory(Path target) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        return Files.createTempDirectory(dir, target.getFileName().toString());
    }
}
