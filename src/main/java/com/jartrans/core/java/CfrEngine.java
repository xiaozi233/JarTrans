package com.jartrans.core.java;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

final class CfrEngine implements DecompilerEngine {
    @Override
    public Map<String, String> decompile(ZipFile jar, Collection<String> names) {
        Map<String, String> sources = new LinkedHashMap<>();
        ClassFileSource loader = new ClassFileSource() {
            @Override public void informAnalysisRelativePathDetail(String usePath, String path) { }
            @Override public Collection<String> addJar(String path) { return List.of(); }
            @Override public String getPossiblyRenamedPath(String path) { return path; }
            @Override public Pair<byte[], String> getClassFileContent(String path) throws IOException {
                byte[] bytes = DecompilerEngine.readClass(jar, path);
                if (bytes == null) {
                    throw new IOException("Class not found: " + path);
                }
                return Pair.make(bytes, path);
            }
        };
        OutputSinkFactory sink = new OutputSinkFactory() {
            @Override public List<SinkClass> getSupportedSinks(SinkType type, Collection<SinkClass> available) {
                return type == SinkType.JAVA ? List.of(SinkClass.DECOMPILED) : List.of(SinkClass.STRING);
            }
            @Override public <T> Sink<T> getSink(SinkType type, SinkClass sinkClass) {
                return value -> {
                    if (type == SinkType.JAVA && value instanceof SinkReturns.Decompiled result) {
                        String pkg = result.getPackageName().replace('.', '/');
                        sources.put((pkg.isEmpty() ? "" : pkg + "/") + result.getClassName(), result.getJava());
                    }
                };
            }
        };
        new CfrDriver.Builder().withOverrideClassFileSource(loader).withOutputSink(sink)
                .withOptions(Map.of("silent", "true")).build()
                .analyse(names.stream().map(name -> name + ".class").toList());
        return sources;
    }
}
