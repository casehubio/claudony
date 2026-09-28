package io.casehub.claudony.casehub.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PoolDefinitionSource {

    private static final String MANIFEST_DIR = "META-INF/pool-definitions/";

    private final ObjectMapper objectMapper;

    public PoolDefinitionSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void discover(ClassLoader cl, AgentPoolDefinitionRegistry registry) {
        try {
            Enumeration<URL> dirs = cl.getResources(MANIFEST_DIR);
            while (dirs.hasMoreElements()) {
                URL dirUrl = dirs.nextElement();
                scanDirectory(dirUrl, cl, registry);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan pool definition manifests", e);
        }
    }

    private void scanDirectory(URL dirUrl, ClassLoader cl, AgentPoolDefinitionRegistry registry)
            throws IOException {
        String protocol = dirUrl.getProtocol();
        if ("file".equals(protocol)) {
            scanFileDirectory(new File(dirUrl.getPath()), cl, registry);
        } else if ("jar".equals(protocol)) {
            scanJarDirectory(dirUrl, cl, registry);
        }
    }

    private void scanFileDirectory(File dir, ClassLoader cl, AgentPoolDefinitionRegistry registry)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json") && !name.endsWith(".schema.json")) {
                String poolName = name.substring(0, name.length() - ".json".length());
                loadAndRegister(poolName, cl, registry);
            }
        }
    }

    private void scanJarDirectory(URL dirUrl, ClassLoader cl, AgentPoolDefinitionRegistry registry)
            throws IOException {
        JarURLConnection jarConn = (JarURLConnection) dirUrl.openConnection();
        try (JarFile jarFile = jarConn.getJarFile()) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                String entryName = jarEntry.getName();
                if (entryName.startsWith(MANIFEST_DIR)
                        && entryName.endsWith(".json")
                        && !entryName.endsWith(".schema.json")
                        && !jarEntry.isDirectory()) {
                    String fileName = entryName.substring(MANIFEST_DIR.length());
                    String poolName = fileName.substring(0, fileName.length() - ".json".length());
                    loadAndRegister(poolName, cl, registry);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadAndRegister(String poolName, ClassLoader cl, AgentPoolDefinitionRegistry registry)
            throws IOException {
        String manifestPath = MANIFEST_DIR + poolName + ".json";
        try (InputStream stream = cl.getResourceAsStream(manifestPath)) {
            if (stream == null) return;
            Map<String, Object> manifest = objectMapper.readValue(stream, LinkedHashMap.class);
            String name = (String) manifest.get("name");
            registry.register(AgentPoolDefinition.builder().agent(name).build());
        }
    }
}
