package net.neoforged.jarjar.nio.layfs;

import net.neoforged.jarjar.nio.pathfs.PathFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestLayeredZipUriPaths {
    private static final String ENTRY_NAME = "message.txt";
    private static final String NESTED_ARCHIVE_NAME = "nested.zip";
    private static final String OUTER_CONTENTS = "Outer archive";
    private static final String NESTED_CONTENTS = "Nested archive";

    @TempDir
    Path temporaryDirectory;

    private Path archivePath;
    private URI archiveUri;

    @BeforeEach
    public void createArchiveFixture() throws IOException {
        final Path nestedArchive = temporaryDirectory.resolve(NESTED_ARCHIVE_NAME);
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(nestedArchive))) {
            archive.putNextEntry(new ZipEntry(ENTRY_NAME));
            archive.write(NESTED_CONTENTS.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }

        archivePath = temporaryDirectory.resolve("archive.zip");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            archive.putNextEntry(new ZipEntry(ENTRY_NAME));
            archive.write(OUTER_CONTENTS.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry(NESTED_ARCHIVE_NAME));
            Files.copy(nestedArchive, archive);
            archive.closeEntry();
        }

        // Path.toUri supplies the platform's file URI format, including the drive prefix on Windows.
        final URI fileUri = archivePath.toUri();
        final String archiveLocation = fileUri.getRawSchemeSpecificPart();
        // Change only the scheme so Java's filesystem APIs select JarJar's provider.
        archiveUri = URI.create("jij:" + archiveLocation);
    }

    @Test
    public void testCreateFileSystemFromArchiveUri() throws IOException {
        // With no packagePath, newFileSystem must convert the URI's location into a native file path.
        try (FileSystem archive = FileSystems.newFileSystem(archiveUri, Collections.emptyMap())) {
            final Path entryPath = archive.getPath(ENTRY_NAME);
            assertContents(entryPath, OUTER_CONTENTS);
        }
    }

    @Test
    public void testGetPathFromArchiveEntryUri() throws IOException {
        // '~/' introduces the entry inside the ZIP. Paths.get calls JarJar's getPath for this 'jij:' URI.
        final URI entryUri = URI.create(archiveUri + "~/" + ENTRY_NAME);
        final Path entryPath = Paths.get(entryUri);
        try (FileSystem archive = entryPath.getFileSystem()) {
            assertContents(entryPath, OUTER_CONTENTS);
        }
    }

    @Test
    public void testGetPathFromArchiveWithSpaceInName() throws IOException {
        final Path archiveWithSpace = temporaryDirectory.resolve("archive with space.zip");
        Files.move(archivePath, archiveWithSpace);

        // Path.toUri encodes the space as %20; lookup must open the filename containing the actual space.
        final URI fileUri = archiveWithSpace.toUri();
        final String archiveLocation = fileUri.getRawSchemeSpecificPart();
        final URI entryUri = URI.create("jij:" + archiveLocation + "~/" + ENTRY_NAME);
        final Path entryPath = Paths.get(entryUri);
        try (FileSystem archive = entryPath.getFileSystem()) {
            assertContents(entryPath, OUTER_CONTENTS);
        }
    }

    @Test
    public void testGetPathWithSingleLeadingSlash() throws IOException {
        // getRawPath omits the URI's empty host prefix, giving '/C:/...' instead of '///C:/...' on Windows.
        final String archivePath = archiveUri.getRawPath();
        final URI entryUri = URI.create("jij:" + archivePath + "~/" + ENTRY_NAME);
        final Path entryPath = Paths.get(entryUri);
        try (FileSystem archive = entryPath.getFileSystem()) {
            assertContents(entryPath, OUTER_CONTENTS);
        }
    }

    @Test
    public void testGetFileSystemFromNestedArchiveUri() throws IOException {
        // The URI identifies the inner ZIP, so getFileSystem must first locate the outer ZIP on disk.
        final URI nestedArchiveUri = URI.create(archiveUri + "~/" + NESTED_ARCHIVE_NAME);
        final PathFileSystem resolvedFileSystem = (PathFileSystem) FileSystems.getFileSystem(nestedArchiveUri);
        final Path nestedArchivePath = resolvedFileSystem.getTarget();
        // Both ZIPs must close so Windows can delete them. Resources close in reverse order: inner, then outer.
        try (FileSystem outerArchive = nestedArchivePath.getFileSystem();
             FileSystem innerArchive = resolvedFileSystem) {
            final Path entryPath = innerArchive.getPath(ENTRY_NAME);
            assertContents(entryPath, NESTED_CONTENTS);
        }
    }

    @Test
    public void testGetPathFromNestedArchiveEntryUri() throws IOException {
        // Only the outer archive uses a native path; subsequent sections are paths inside ZIPs.
        final URI nestedArchiveUri = URI.create(archiveUri + "~/" + NESTED_ARCHIVE_NAME);
        final URI entryUri = URI.create(nestedArchiveUri + "~/" + ENTRY_NAME);
        final Path entryPath = Paths.get(entryUri);
        final PathFileSystem entryFileSystem = (PathFileSystem) entryPath.getFileSystem();
        final Path nestedArchivePath = entryFileSystem.getTarget();
        // Both ZIPs must close so Windows can delete them. Resources close in reverse order: inner, then outer.
        try (FileSystem outerArchive = nestedArchivePath.getFileSystem();
             FileSystem innerArchive = entryFileSystem) {
            assertContents(entryPath, NESTED_CONTENTS);
        }
    }

    private void assertContents(Path entryPath, String expectedContents) throws IOException {
        final List<String> expectedLines = Collections.singletonList(expectedContents);
        final List<String> actualLines = Files.readAllLines(entryPath);
        assertEquals(expectedLines, actualLines);
    }
}
