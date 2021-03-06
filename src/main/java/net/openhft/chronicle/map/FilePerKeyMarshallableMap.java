package net.openhft.chronicle.map;

import com.sun.nio.file.SensitivityWatchEventModifier;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.Wire;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link Map} implementation that stores each entry as a file in a
 * directory. The <code>key</code> is the file name and the <code>value</code>
 * is the contents of the file.
 * <p>
 * The class is effectively an abstraction over a directory in the file system.
 * Therefore when the underlying files are changed an event will be fired to those
 * registered for notifications.
 *
 * <p>
 * Updates will be fired every time the file is saved but will be suppressed
 * if the value has not changed.  To avoid temporary files (e.g. if edited in vi)
 * being included in the map, any file starting with a '.' will be ignored.
 * <p>
 * Note the {@link WatchService} is extremely OS dependant.  Mas OSX registers
 * very few events if they are done quickly and there is a significant delay
 * between the event and the event being triggered.
 */
public class FilePerKeyMarshallableMap<V extends Marshallable> implements Map<String, V>, Closeable {
    private final Path dirPath;
    //Use BytesStore so that it can be shared safely between threads
    private final Map<File, FileRecord<V>> lastFileRecordMap = new ConcurrentHashMap<>();

    private final List<ChronicleMapEventListener<String, V>> listeners = new ArrayList<>();
    private final Thread fileFpmWatcher;
    private final Supplier<V> valueSupplier;
    private volatile boolean closed = false;
    private boolean putReturnsNull;
    private final Bytes<ByteBuffer> writingBytes = Bytes.elasticByteBuffer();
    private final Bytes<ByteBuffer> readingBytes = Bytes.elasticByteBuffer();
    private final Wire writingWire;
    private final Wire readingWire;

    public FilePerKeyMarshallableMap(String dir,
                                     Function<Bytes, Wire> bytesToWire,
                                     Supplier<V> valueSupplier) throws IOException {
        this.valueSupplier = valueSupplier;
        this.dirPath = Paths.get(dir);
        writingWire = bytesToWire.apply(writingBytes);
        readingWire = bytesToWire.apply(readingBytes);
        Files.createDirectories(dirPath);
        WatchService watcher = FileSystems.getDefault().newWatchService();
        dirPath.register(watcher, new WatchEvent.Kind[]{
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY},
                SensitivityWatchEventModifier.HIGH
        );

        fileFpmWatcher = new Thread(new FPMWatcher(watcher), dir + "-watcher");
        fileFpmWatcher.setDaemon(true);
        fileFpmWatcher.start();
    }

    public void registerForEvents(ChronicleMapEventListener<String, V> listener) {
        listeners.add(listener);
    }

    public void unregisterForEvents(ChronicleMapEventListener<String, V> listener) {
        listeners.remove(listener);
    }

    @Override
    public int size() {
        return (int) getFiles().count();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return getFiles().anyMatch(p -> p.getFileName().toString().equals(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return getFiles().anyMatch(p -> getFileContents(p).equals(value));
    }

    @Override
    public V get(Object key) {
        Path path = dirPath.resolve((String) key);
        return getFileContents(path);
    }

    @Override
    public V put(String key, V value) {
        if (closed) throw new IllegalStateException("closed");
        Path path = dirPath.resolve(key);
        FileRecord fr = lastFileRecordMap.get(path.toFile());
        V existingValue = putReturnsNull ? null : getFileContents(path);
        writeToFile(path, value);
        if (fr != null) fr.valid = false;
        return existingValue;
    }

    @Override
    public V remove(Object key) {
        if (closed) throw new IllegalStateException("closed");
        V existing = get(key);
        if (existing != null) {
            deleteFile(dirPath.resolve((String) key));
        }
        return existing;
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> map) {
        map.entrySet().stream().forEach(e -> put(e.getKey(), e.getValue()));
    }

    @Override
    public void clear() {
        AtomicInteger count = new AtomicInteger();
        Stream<Path> files = getFiles();
        files.forEach((path) -> {
            try {
                deleteFile(path);
            } catch (Exception e) {
                count.incrementAndGet();
                // ignored at afirst.
            }
        });
        if (count.intValue() > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().isInterrupted();
            }
            getFiles().forEach(this::deleteFile);
        }
    }

    @NotNull
    @Override
    public Set<String> keySet() {
        return getFiles().map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());
    }

    @NotNull
    @Override
    public Collection<V> values() {
        return getFiles().map(this::getFileContents)
                .collect(Collectors.toSet());
    }

    @NotNull
    @Override
    public Set<Entry<String, V>> entrySet() {
        return getFiles().map(p ->
                (Entry<String, V>) new FPMEntry(p.getFileName().toString(), getFileContents(p)))
                .collect(Collectors.toSet());
    }

    private Stream<Path> getFiles() {
        try {
            return Files.walk(dirPath).filter(p -> !Files.isDirectory(p));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    V getFileContents(Path path) {
        try {
            File file = path.toFile();
            FileRecord<V> lastFileRecord = lastFileRecordMap.get(file);
            if (lastFileRecord != null && lastFileRecord.valid
                    && file.lastModified() == lastFileRecord.timestamp)
            {
                return lastFileRecord.contents;
            }
            return getFileContentsFromDisk(path);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    V getFileContentsFromDisk(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        File file = path.toFile();

        synchronized (readingBytes) {
            try (FileChannel fc = new FileInputStream(file).getChannel()) {
                readingBytes.ensureCapacity(fc.size());

                ByteBuffer dst = readingBytes.underlyingObject();
                dst.clear();

                fc.read(dst);

                readingBytes.position(0);
                readingBytes.limit(dst.position());
            }

            V v = valueSupplier.get();
            v.readMarshallable(readingWire);
            return v;
        }
    }

    private void writeToFile(Path path, V value) {
        synchronized (writingBytes) {
            writingBytes.clear();
            value.writeMarshallable(writingWire);

            File file = path.toFile();
            File tmpFile = new File(file.getParentFile(), "." + file.getName());
            try (FileChannel fc = new FileOutputStream(tmpFile).getChannel()) {
                ByteBuffer byteBuffer = writingBytes.underlyingObject();
                byteBuffer.position(0);
                byteBuffer.limit((int) writingBytes.position());
                fc.write(byteBuffer);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            try {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void close() {
        closed = true;
        fileFpmWatcher.interrupt();
    }

    public void putReturnsNull(boolean putReturnsNull) {
        this.putReturnsNull = putReturnsNull;
    }

    private static class FPMEntry<V> implements Entry<String, V> {
        private String key;
        private V value;

        public FPMEntry(String key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            V lastValue = this.value;
            this.value = value;
            return lastValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FPMEntry<?> fpmEntry = (FPMEntry<?>) o;

            if (key != null ? !key.equals(fpmEntry.key) : fpmEntry.key != null) return false;
            return !(value != null ? !value.equals(fpmEntry.value) : fpmEntry.value != null);
        }

        @Override
        public int hashCode() {
            int result = key != null ? key.hashCode() : 0;
            result = 31 * result + (value != null ? value.hashCode() : 0);
            return result;
        }
    }

    private class FPMWatcher implements Runnable {
        private final WatchService watcher;

        public FPMWatcher(WatchService watcher) {
            this.watcher = watcher;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    WatchKey key = null;
                    try {
                        key = watcher.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();

                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                // todo log a warning.
                                continue;
                            }

                            // get file name
                            WatchEvent<Path> ev = (WatchEvent<Path>) event;
                            Path fileName = ev.context();
                            String mapKey = fileName.toString();

                            if (mapKey.startsWith(".")) {
                                //this avoids temporary files being added to the map
                                continue;
                            }

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                Path p = dirPath.resolve(fileName);
                                try {
                                    V mapVal = getFileContentsFromDisk(p);
                                    lastFileRecordMap.put(p.toFile(), new FileRecord<>(p.toFile().lastModified(), mapVal));
                                    listeners.forEach(l->l.insert(mapKey, mapVal));
                                } catch (FileNotFoundException ignored) {
                                }
                            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                Path p = dirPath.resolve(fileName);

                                FileRecord<V> lastVal = lastFileRecordMap.remove(p.toFile());
                                V lastContent = lastVal == null ? null : lastVal.contents;
                                listeners.forEach(l->l.remove(mapKey, lastContent));

                            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                                try {
                                    Path p = dirPath.resolve(fileName);
                                    V mapVal = getFileContentsFromDisk(p);
                                    V lastVal=null;
                                    if (mapVal != null) {
                                        FileRecord<V> rec = lastFileRecordMap.put(p.toFile(), new FileRecord<>(p.toFile().lastModified(), mapVal));
                                        if (rec != null) lastVal = rec.contents;
                                    }

                                    if (lastVal != null && lastVal.equals(mapVal)) {
                                        //Nothing has changed don't fire an event
                                        continue;
                                    }

                                    //variable needs to be effectively final
                                    V fLastVal = lastVal;
                                    listeners.forEach(l->l.update(mapKey, fLastVal, mapVal));
                                } catch (FileNotFoundException ignored) {
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    } finally {
                        if (key != null) key.reset();
                    }
                }
            } catch (Throwable e) {
                if (!closed)
                    e.printStackTrace();
            }
        }
    }
}

