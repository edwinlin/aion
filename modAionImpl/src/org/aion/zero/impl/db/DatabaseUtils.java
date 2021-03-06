package org.aion.zero.impl.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;
import org.aion.db.impl.ByteArrayKeyValueDatabase;
import org.aion.db.impl.DatabaseFactory;
import org.aion.db.impl.DatabaseFactory.Props;
import org.aion.db.impl.PersistenceMethod;
import org.aion.mcf.db.exception.InvalidFilePathException;
import org.aion.mcf.db.exception.InvalidFileTypeException;
import org.slf4j.Logger;

/** @author Alexandra Roatis */
public class DatabaseUtils {

    public static ByteArrayKeyValueDatabase connectAndOpen(Properties info, Logger LOG) {
        // get the database object
        ByteArrayKeyValueDatabase db = DatabaseFactory.connect(info, LOG);

        // open the database connection
        db.open();

        // check object status
        if (db == null) {
            LOG.error(
                    "Database <{}> connection could not be established for <{}>.",
                    info.getProperty(Props.DB_TYPE),
                    info.getProperty(Props.DB_NAME));
        }

        // check persistence status
        if (!db.isCreatedOnDisk() && db.getPersistenceMethod() != PersistenceMethod.DBMS) {
            LOG.error(
                    "Database <{}> cannot be saved to disk for <{}>.",
                    info.getProperty(Props.DB_TYPE),
                    info.getProperty(Props.DB_NAME));
        }

        return db;
    }

    /**
     * Ensures that the path defined by the dbFile is valid and generates all the directories that
     * are missing in the path.
     *
     * @param dbFile the path to be verified.
     * @throws InvalidFilePathException when:
     *     <ol>
     *       <li>the given path is not valid;
     *       <li>the directory structure cannot be created;
     *       <li>the path cannot be written to;
     *       <li>the give file is not a directory
     *     </ol>
     */
    public static void verifyAndBuildPath(File dbFile) throws InvalidFilePathException {
        // to throw in case of issues with the path
        InvalidFilePathException exception =
                new InvalidFilePathException(
                        "The path «"
                                + dbFile.getAbsolutePath()
                                + "» is not valid as reported by the OS or a read/write permissions error occurred."
                                + " Please provide an alternative DB dbFile path in /config/config.xml.");
        Path path;

        try {
            // ask the OS if the path is valid
            String canonicalPath = dbFile.getCanonicalPath();
            path = Paths.get(canonicalPath);
        } catch (Exception e) {
            e.printStackTrace();
            throw exception;
        }

        // try to create the directory
        if (!dbFile.exists()) {
            if (!dbFile.mkdirs()) {
                throw exception;
            }
        }

        if (path == null || !Files.isWritable(path) || !Files.isDirectory(path)) {
            throw exception;
        }
    }

    /**
     * Ensures that the path defined by the dbFile and the fileType in the folders are correct.
     * The sst file is part of the file structure of the rocksdb
     * The ldb file is part of the file structure of the leveldb
     * The OPTIONS file is specific for the rocksdb
     * @author Jay Tseng
     * @param dbFile the path to be verified.
     * @param dbType the database type set by the config file
     * @throws InvalidFileTypeException when:
     *     <ol>
     *       <li>the file type is ldb but found the prefix «OPTIONS» file or .sst file.
     *       <li>the file type is sst but found the .ldb file or cannot find the «OPTIONS» file.
     *     </ol>
     * @throws IOException when:
     *     <ol>
     *       <li> I/O error for accessing the dbFile.
     *     </ol>
     */
    static void verifyDBfileType(File dbFile, String dbType)
        throws InvalidFileTypeException, IOException {

        // Check if it is a new database folder
        if (Objects.requireNonNull(dbFile.listFiles()).length == 0) {
            return;
        }

        if (dbType.equals("leveldb")) {
            try (Stream<Path> paths = Files.walk(dbFile.toPath())) {
                boolean shouldThrow =
                    paths.filter(Files::isRegularFile)
                        .anyMatch(
                            filepath ->
                                filepath.getFileName().toString().startsWith("OPTIONS")
                                    || filepath.getFileName().toString().endsWith(".sst"));
                if (shouldThrow) {
                    throw new InvalidFileTypeException(
                        "Found file type «sst» or the file name prefix «OPTIONS» in the database folder"
                            + ", it is not matched the current DB settings «"
                            + dbType
                            + "» Please check DB settings in ./<network>/config/config.xml .");
                }
            }
        } else if (dbType.equals("rocksdb")) {
            try (Stream<Path> paths = Files.walk(dbFile.toPath())) {
                boolean shouldThrow =
                    paths.filter(Files::isRegularFile)
                        .anyMatch(filepath ->
                            filepath.getFileName().toString().endsWith(".ldb"));

                if (shouldThrow) {
                    throw new InvalidFileTypeException(
                        "Found file type «ldb» in the database folder"
                            + ", it is not matched the current DB settings «"
                            + dbType
                            + "» Please check DB settings in ./<network>/config/config.xml .");
                }
            }

            try (Stream<Path> paths = Files.walk(dbFile.toPath())) {
                boolean shouldThrow =
                    paths.filter(Files::isRegularFile)
                        .noneMatch(filepath ->
                            filepath.getFileName().toString().startsWith("OPTIONS"));

                if (shouldThrow) {
                    throw new InvalidFileTypeException(
                        "Cannot find the file «OPTIONS» in the database folder"
                            + ", it is not matched with the current DB settings «"
                            + dbType
                            + "» Please check DB settings in ./<network>/config/config.xml .");
                }
            }
        } else {
            throw new InvalidFileTypeException(
                    "The db type «" + dbType + "» has not been supported by the kernel.");
        }
    }

    public static boolean deleteRecursively(File file) {
        Path path = file.toPath();
        try {
            java.nio.file.Files.walkFileTree(
                    path,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(
                                final Path file, final BasicFileAttributes attrs)
                                throws IOException {
                            java.nio.file.Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(
                                final Path file, final IOException e) {
                            return handleException(e);
                        }

                        private FileVisitResult handleException(final IOException e) {
                            // e.printStackTrace();
                            return FileVisitResult.TERMINATE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(
                                final Path dir, final IOException e) throws IOException {
                            if (e != null) return handleException(e);
                            java.nio.file.Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }
}
