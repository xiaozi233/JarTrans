package com.jartrans.core.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

final class VineflowerEngine implements DecompilerEngine {
    @Override
    public Map<String, String> decompile(ZipFile jar, Collection<String> names) {
        Map<String, String> sources = new ConcurrentHashMap<>();
        Fernflower engine = new Fernflower(
                new MemorySaver(), Map.of("log-level", "ERROR", "include-runtime", "current"), IFernflowerLogger.NO_OP);
        engine.addSource(new IContextSource() {
            @Override
            public String getName() {
                return jar.getName();
            }

            @Override
            public Entries getEntries() {
                return new Entries(names.stream().map(Entry::atBase).toList(), List.of(), List.of());
            }

            @Override
            public InputStream getInputStream(String resource) throws IOException {
                byte[] bytes = DecompilerEngine.readClass(jar, resource);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }

            @Override
            public IOutputSink createOutputSink(IResultSaver saver) {
                return new IOutputSink() {
                    @Override
                    public void begin() {}

                    @Override
                    public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                        sources.put(qualifiedName, content);
                    }

                    @Override
                    public void acceptDirectory(String directory) {}

                    @Override
                    public void acceptOther(String path) {}

                    @Override
                    public void close() {}
                };
            }
        });
        engine.addLibrary(new IContextSource() {
            @Override
            public String getName() {
                return jar.getName() + " dependencies";
            }

            @Override
            public Entries getEntries() {
                return Entries.EMPTY;
            }

            @Override
            public boolean isLazy() {
                return true;
            }

            @Override
            public InputStream getInputStream(String resource) throws IOException {
                byte[] bytes = DecompilerEngine.readClass(jar, resource);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        });
        try {
            engine.decompileContext();
            return sources;
        } finally {
            engine.clearContext();
        }
    }

    /** 所有源码由 context 的 sink 收集；保留 saver 以满足引擎清理协议。 */
    private static final class MemorySaver implements IResultSaver {
        @Override
        public void saveFolder(String path) {}

        @Override
        public void copyFile(String source, String path, String entry) {}

        @Override
        public void saveClassFile(String path, String name, String entry, String content, int[] mapping) {}

        @Override
        public void createArchive(String path, String name, Manifest manifest) {}

        @Override
        public void saveDirEntry(String path, String archive, String entry) {}

        @Override
        public void copyEntry(String source, String path, String archive, String entry) {}

        @Override
        public void saveClassEntry(String path, String archive, String name, String entry, String content) {}

        @Override
        public void closeArchive(String path, String name) {}
    }
}
