package com.darkmusic.client.testing;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * Test-only paths delegate every operation to the host filesystem, including locking and case aliases.
 */
public final class FaultFileSystem extends FileSystem {
    @FunctionalInterface
    public interface Before {
        void run(String operation, Path path, Path target) throws IOException;
    }

    private final FileSystem host = FileSystems.getDefault();
    private final Provider provider = new Provider();
    private Before before = (operation, path, target) -> {
    };
    private boolean open = true;

    public Path wrap(Path path) {
        return new FaultPath(path);
    }

    public void before(Before value) {
        before = value;
    }

    private Path raw(Path path) {
        return path instanceof FaultPath p ? p.path : path;
    }

    private void check(String operation, Path path, Path target) throws IOException {
        if (!open) throw new ClosedFileSystemException();
        before.run(operation, raw(path), target == null ? null : raw(target));
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return host.isReadOnly();
    }

    @Override
    public String getSeparator() {
        return host.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        var roots = new ArrayList<Path>();
        host.getRootDirectories().forEach(p -> roots.add(wrap(p)));
        return roots;
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return host.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return host.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more) {
        return wrap(host.getPath(first, more));
    }

    @Override
    public PathMatcher getPathMatcher(String pattern) {
        var matcher = host.getPathMatcher(pattern);
        return p -> matcher.matches(raw(p));
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return host.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        return host.newWatchService();
    }

    private final class Provider extends FileSystemProvider {
        private final FileSystemProvider delegate = host.provider();

        @Override
        public String getScheme() {
            return "fault";
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            return FaultFileSystem.this;
        }

        @Override
        public Path getPath(URI uri) {
            throw new UnsupportedOperationException();
        }

        private void channel(Path path, Set<? extends OpenOption> options) throws IOException {
            check(options.stream().anyMatch(o -> o == StandardOpenOption.WRITE || o == StandardOpenOption.APPEND
                || o == StandardOpenOption.CREATE || o == StandardOpenOption.CREATE_NEW) ? "write" : "read", path, null);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            channel(path, options);
            return delegate.newByteChannel(raw(path), options, attrs);
        }

        @Override
        public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            channel(path, options);
            return delegate.newFileChannel(raw(path), options, attrs);
        }

        @Override
        public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options, ExecutorService executor,
                                                                  FileAttribute<?>... attrs) throws IOException {
            channel(path, options);
            return delegate.newAsynchronousFileChannel(raw(path), options, executor, attrs);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            check("list", dir, null);
            var stream = delegate.newDirectoryStream(raw(dir), p -> filter.accept(wrap(p)));
            return new DirectoryStream<>() {
                public Iterator<Path> iterator() {
                    var iterator = stream.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() {
                            return iterator.hasNext();
                        }

                        public Path next() {
                            return wrap(iterator.next());
                        }
                    };
                }

                public void close() throws IOException {
                    stream.close();
                }
            };
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            check("mkdir", dir, null);
            delegate.createDirectory(raw(dir), attrs);
        }

        @Override
        public void delete(Path path) throws IOException {
            check("delete", path, null);
            delegate.delete(raw(path));
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            check("copy", source, target);
            delegate.copy(raw(source), raw(target), options);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            check("move", source, target);
            delegate.move(raw(source), raw(target), options);
        }

        @Override
        public boolean isSameFile(Path path, Path other) throws IOException {
            return delegate.isSameFile(raw(path), raw(other));
        }

        @Override
        public boolean isHidden(Path path) throws IOException {
            return delegate.isHidden(raw(path));
        }

        @Override
        public FileStore getFileStore(Path path) throws IOException {
            check("store", path, null);
            return delegate.getFileStore(raw(path));
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            delegate.checkAccess(raw(path), modes);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            return delegate.getFileAttributeView(raw(path), type, options);
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
            return delegate.readAttributes(raw(path), type, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return delegate.readAttributes(raw(path), attributes, options);
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
            check("attribute", path, null);
            delegate.setAttribute(raw(path), attribute, value, options);
        }

        @Override
        public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
            check("symlink", link, target);
            delegate.createSymbolicLink(raw(link), raw(target), attrs);
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            check("link", link, existing);
            delegate.createLink(raw(link), raw(existing));
        }

        @Override
        public Path readSymbolicLink(Path link) throws IOException {
            return wrap(delegate.readSymbolicLink(raw(link)));
        }
    }

    private final class FaultPath implements Path {
        private final Path path;

        FaultPath(Path path) {
            this.path = path;
        }

        private Path nullable(Path value) {
            return value == null ? null : wrap(value);
        }

        @Override
        public FileSystem getFileSystem() {
            return FaultFileSystem.this;
        }

        @Override
        public boolean isAbsolute() {
            return path.isAbsolute();
        }

        @Override
        public Path getRoot() {
            return nullable(path.getRoot());
        }

        @Override
        public Path getFileName() {
            return nullable(path.getFileName());
        }

        @Override
        public Path getParent() {
            return nullable(path.getParent());
        }

        @Override
        public int getNameCount() {
            return path.getNameCount();
        }

        @Override
        public Path getName(int index) {
            return wrap(path.getName(index));
        }

        @Override
        public Path subpath(int begin, int end) {
            return wrap(path.subpath(begin, end));
        }

        @Override
        public boolean startsWith(Path other) {
            return path.startsWith(raw(other));
        }

        @Override
        public boolean endsWith(Path other) {
            return path.endsWith(raw(other));
        }

        @Override
        public Path normalize() {
            return wrap(path.normalize());
        }

        @Override
        public Path resolve(Path other) {
            return wrap(path.resolve(raw(other)));
        }

        @Override
        public Path relativize(Path other) {
            return wrap(path.relativize(raw(other)));
        }

        @Override
        public URI toUri() {
            return path.toUri();
        }

        @Override
        public Path toAbsolutePath() {
            return wrap(path.toAbsolutePath());
        }

        @Override
        public int compareTo(Path other) {
            return path.compareTo(raw(other));
        }

        @Override
        public String toString() {
            return path.toString();
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(FaultFileSystem.this) + path.hashCode();
        }

        @Override
        public Path toRealPath(LinkOption... options) throws IOException {
            return wrap(path.toRealPath(options));
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
            return path.register(watcher, events, modifiers);
        }

        @Override
        public Iterator<Path> iterator() {
            var iterator = path.iterator();
            return new Iterator<>() {
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                public Path next() {
                    return wrap(iterator.next());
                }
            };
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FaultPath p && p.getFileSystem() == getFileSystem() && path.equals(p.path);
        }
    }
}
