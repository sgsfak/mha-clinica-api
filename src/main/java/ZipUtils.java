import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Created by ssfak on 2/11/15.
 */
public class ZipUtils {
    static boolean isZipFile(final Path file) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file.toFile()))) {
            boolean isZip = in.readInt() == 0x504b0304;
            return isZip;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }

    }

    static void unzipFile(Path outputDir, File zipFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            final int MAX_BUF = 4 * 1024 * 1024;
            byte buffer[] = new byte[MAX_BUF];
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                final Path outputEntryPath = Paths.get(outputDir.toString(), entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outputEntryPath);
                } else {
                    if (!Files.exists(outputEntryPath.getParent())) {
                        Files.createDirectories(outputEntryPath.getParent());
                    }
                    try (FileOutputStream fos = new FileOutputStream(outputEntryPath.toString())) {
                        while (true) {
                            int k = zis.read(buffer, 0, MAX_BUF);
                            if (k > 0)
                                fos.write(buffer, 0, k);
                            else
                                break;
                        }
                    }
                    // System.out.println("==> wrote " + entry.getName() + " at " + outputEntryPath);
                }
                zis.closeEntry();
            }
        }
    }


    static void zipDir(Path dir, Path outZip) throws IOException {
        // See http://docs.oracle.com/javase/7/docs/technotes/guides/io/fsp/zipfilesystemprovider.html
        try {
            URI uri = new URI("jar", outZip.toUri().toString(), null);
            // if (outZip.toFile().exists())       outZip.toFile().delete();
            try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.singletonMap("create", "true"))) {
                Files.walk(dir).filter(path -> !path.toFile().isHidden()).forEach(path -> {
                            String targetPath = "/" + dir.getParent().relativize(path);
                            // System.out.println("Adding " + path + " to " + targetPath);
                            try {
                                Files.copy(path.toAbsolutePath(),
                                        fileSystem.getPath("/").resolve(targetPath),
                                        StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                );
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }

    static void visitEntries(final Path zipPath, FileVisitor<Path> visitor) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(new URI("jar", zipPath.toUri().toString(), null), Collections.singletonMap("create", "false"), null)) {
            fs.getRootDirectories().forEach(root -> {
                try {
                    Files.walkFileTree(root, visitor);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }
}
