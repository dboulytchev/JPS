package org.jetbrains.jps.builders.javacApi;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DefaultFileManager;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import org.jetbrains.ether.dependencyView.Callbacks;
import org.objectweb.asm.ClassReader;

import javax.lang.model.SourceVersion;
import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author nik
 */
public class OptimizedFileManager extends DefaultFileManager {

    private boolean myUseZipFileIndex;
    private final Map<File, Archive> myArchives;
    private final Map<File, Boolean> myIsFile = new ConcurrentHashMap<File, Boolean>();
    private Callbacks.Backend callback;

    public void setCallback (final Callbacks.Backend c) {
        callback = c;
    }

    public OptimizedFileManager() {
        super(new Context(), true, null);
        try {
            final Field archivesField = DefaultFileManager.class.getDeclaredField("archives");
            archivesField.setAccessible(true);
            myArchives = (Map<File, Archive>) archivesField.get(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            final Field useZipFileIndexField = DefaultFileManager.class.getDeclaredField("useZipFileIndex");
            useZipFileIndexField.setAccessible(true);
            myUseZipFileIndex = (Boolean) useZipFileIndexField.get(this);
        } catch (Exception e) {
            myUseZipFileIndex = false;
        }
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        Iterable<? extends File> path = getLocation(location);
        if (path == null) return Collections.emptyList();

        String relativePath = packageName.replace('.', File.separatorChar);
        ListBuffer<JavaFileObject> results = new ListBuffer<JavaFileObject>();

        for (File root : path) {
            Archive archive = myArchives.get(root);
            final boolean isFile;
            if (archive != null) {
                isFile = true;
            } else {
                Boolean cachedIsFile = myIsFile.get(root);
                if (cachedIsFile == null) {
                    cachedIsFile = root.isFile();
                    myIsFile.put(root, cachedIsFile);
                }
                isFile = cachedIsFile.booleanValue();
            }
            if (isFile) {
                collectFromArchive(root, archive, relativePath, kinds, recurse, results);
            } else {
                File directory = relativePath.length() != 0 ? new File(root, relativePath) : root;
                collectFromDirectory(directory, kinds, recurse, results);
            }
        }

        return results.toList();
    }

    private void collectFromArchive(File root, Archive archive, String relativePath, Set<JavaFileObject.Kind> kinds, boolean recurse, ListBuffer<JavaFileObject> result) {
        if (archive == null) {
            try {
                archive = openArchive(root);
            } catch (IOException ex) {
                log.error("error.reading.file", root, ex.getLocalizedMessage());
                return;
            }
        }
        String separator = myUseZipFileIndex ? File.separator : "/";
        if (relativePath.length() != 0) {
            if (!myUseZipFileIndex) {
                relativePath = relativePath.replace('\\', '/');
            }
            if (!relativePath.endsWith(separator)) relativePath = relativePath + separator;
        }

        collectArchiveFiles(archive, relativePath, kinds, result);
        if (recurse) {
            for (String s : archive.getSubdirectories()) {
                if (s.startsWith(relativePath) && !s.equals(relativePath)) {
                    if (!s.endsWith(separator)) {
                        s += separator;
                    }
                    collectArchiveFiles(archive, s, kinds, result);
                }
            }
        }
    }

    private void collectFromDirectory(File directory, Set<JavaFileObject.Kind> fileKinds,
                                      boolean recurse, ListBuffer<JavaFileObject> result) {
        File[] children = directory.listFiles();
        if (children == null) return;

        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                if (recurse && SourceVersion.isIdentifier(name)) {
                    collectFromDirectory(directory, fileKinds, recurse, result);
                }
            } else {
                if (isValidFile(name, fileKinds)) {
                    JavaFileObject fe = getRegularFile(child);
                    result.append(fe);
                }
            }
        }
    }

    private void collectArchiveFiles(Archive archive, String relativePath, Set<JavaFileObject.Kind> fileKinds, ListBuffer<JavaFileObject> result) {
        List<String> files = archive.getFiles(relativePath);
        if (files != null) {
            for (String file; !files.isEmpty(); files = files.tail) {
                file = files.head;
                if (isValidFile(file, fileKinds)) {
                    result.append(archive.getFileObject(relativePath, file));
                }
            }
        }
    }

    private boolean isValidFile(String name, Set<JavaFileObject.Kind> fileKinds) {
        int dot = name.lastIndexOf(".");
        JavaFileObject.Kind kind = getKind(dot == -1 ? name : name.substring(dot));
        return fileKinds.contains(kind);
    }

    //actually Javac doesn't check if this method returns null. It always get substring of the returned string starting from the last dot.
    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        final String name = file.getName();
        int dot = name.lastIndexOf('.');
        final String relativePath = dot != -1 ? name.substring(0, dot) : name;
        return relativePath.replace(File.separatorChar, '.');
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, final JavaFileObject.Kind kind, FileObject fileObject) throws IOException {
        final JavaFileObject result = super.getJavaFileForOutput(location, className, kind, fileObject);
        final String classFileName = result.toUri().toString();
        final String sourceFileName = fileObject.toUri().toString();

        return new ForwardingJavaFileObject<JavaFileObject>(result) {
            @Override
            public OutputStream openOutputStream() throws IOException {
                final OutputStream result = super.openOutputStream();

                return new OutputStream() {

                    public void flush() throws IOException {
                        result.flush();
                    }

                    public void close() throws IOException {
                        result.close();
                    }

                    public void write(int b) throws IOException {
                        throw new RuntimeException();
                    }

                    public void write(byte[] b) throws IOException {
                        throw new RuntimeException();
                    }

                    public void write(byte[] b, int off, int len) throws IOException {
                        if (kind.equals(JavaFileObject.Kind.CLASS) && callback != null) {
                            callback.associate(classFileName, sourceFileName, new ClassReader(b, off, len));
                        }
                        result.write(b, off, len);
                    }
                };
            }
        };
    }
}
