package com.jartrans.core.java;

import com.strobel.assembler.metadata.ClasspathTypeLoader;
import com.strobel.assembler.metadata.CompositeTypeLoader;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

final class ProcyonEngine implements DecompilerEngine {
    @Override
    public Map<String, String> decompile(ZipFile jar, Collection<String> names) throws IOException {
        ITypeLoader loader = (name, buffer) -> {
            try {
                byte[] bytes = DecompilerEngine.readClass(jar, name);
                if (bytes == null) {
                    return false;
                }
                buffer.reset(bytes.length);
                buffer.putByteArray(bytes, 0, bytes.length);
                buffer.position(0);
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        var settings = DecompilerSettings.javaDefaults();
        settings.setTypeLoader(new CompositeTypeLoader(loader, new ClasspathTypeLoader()));
        var metadata = new MetadataSystem(settings.getTypeLoader());
        var options = new DecompilationOptions();
        options.setSettings(settings);
        options.setFullDecompilation(true);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : names) {
            var reference = metadata.lookupType(name);
            var type = reference == null ? null : reference.resolve();
            if (type == null) {
                throw new IOException("Class not found: " + name);
            }
            var writer = new StringWriter();
            settings.getLanguage().decompileType(type, new PlainTextOutput(writer), options);
            sources.put(name, writer.toString());
        }
        return sources;
    }
}
