package com.jartrans.core.java;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.zip.ZipFile;

/** 进程内引擎适配器：从原 JAR 读取字节，返回内部类名到源码的映射。 */
interface DecompilerEngine {
    Map<String, String> decompile(ZipFile jar, Collection<String> names) throws IOException;

    static byte[] readClass(ZipFile jar, String name) throws IOException {
        var entry = jar.getEntry(name.endsWith(".class") ? name : name + ".class");
        if (entry == null) {
            return null;
        }
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
