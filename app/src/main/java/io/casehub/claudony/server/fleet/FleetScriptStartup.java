package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.script.FleetScriptResult;
import io.casehub.claudony.casehub.fleet.script.NodeResult;
import io.casehub.claudony.config.ClaudonyConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@ApplicationScoped
public class FleetScriptStartup {

    private static final Logger LOG = Logger.getLogger(FleetScriptStartup.class);
    static final String FLEET_SCRIPTS_DIR = "META-INF/fleet-scripts/";

    @Inject ClaudonyConfig config;
    @Inject FleetScriptService fleetScriptService;

    void onStart(@Observes StartupEvent event) {
        if (!config.isServerMode()) return;
        executeFleetScripts(Thread.currentThread().getContextClassLoader());
    }

    void executeFleetScripts(ClassLoader cl) {
        int executed = 0;
        int failed = 0;
        try {
            Enumeration<URL> dirs = cl.getResources(FLEET_SCRIPTS_DIR);
            while (dirs.hasMoreElements()) {
                URL dirUrl = dirs.nextElement();
                String protocol = dirUrl.getProtocol();
                if ("file".equals(protocol)) {
                    int[] counts = scanFileDirectory(new File(dirUrl.getPath()), cl);
                    executed += counts[0];
                    failed += counts[1];
                } else if ("jar".equals(protocol)) {
                    int[] counts = scanJarDirectory(dirUrl, cl);
                    executed += counts[0];
                    failed += counts[1];
                }
            }
        } catch (IOException e) {
            LOG.warnf("Failed to scan %s: %s", FLEET_SCRIPTS_DIR, e.getMessage());
            return;
        }
        if (executed > 0) {
            LOG.infof("Fleet scripts: %d executed, %d failed", executed, failed);
        }
    }

    private int[] scanFileDirectory(File dir, ClassLoader cl) {
        int executed = 0;
        int failed = 0;
        File[] files = dir.listFiles();
        if (files == null) return new int[]{0, 0};
        for (File file : files) {
            if (file.getName().endsWith(".yaml") || file.getName().endsWith(".yml")) {
                String resourcePath = FLEET_SCRIPTS_DIR + file.getName();
                if (executeScript(resourcePath, cl)) failed++;
                executed++;
            }
        }
        return new int[]{executed, failed};
    }

    private int[] scanJarDirectory(URL dirUrl, ClassLoader cl) throws IOException {
        int executed = 0;
        int failed = 0;
        JarURLConnection jarConn = (JarURLConnection) dirUrl.openConnection();
        try (JarFile jarFile = jarConn.getJarFile()) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(FLEET_SCRIPTS_DIR)
                        && !entry.isDirectory()
                        && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                    if (executeScript(name, cl)) failed++;
                    executed++;
                }
            }
        }
        return new int[]{executed, failed};
    }

    private boolean executeScript(String resourcePath, ClassLoader cl) {
        String fileName = resourcePath.substring(FLEET_SCRIPTS_DIR.length());
        try (InputStream stream = cl.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                LOG.warnf("Fleet script not readable: %s", fileName);
                return true;
            }
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            FleetScriptResult result = fleetScriptService.execute(yaml);
            if (result.allSucceeded()) {
                LOG.infof("Fleet script '%s': all %d node(s) succeeded", fileName, result.results().size());
            } else {
                for (NodeResult nr : result.failures()) {
                    LOG.warnf("Fleet script '%s' node '%s' (%s) failed: %s",
                            fileName, nr.name(), nr.type(), nr.message());
                }
            }
            return !result.allSucceeded();
        } catch (Exception e) {
            LOG.warnf("Fleet script '%s' failed: %s", fileName, e.getMessage());
            return true;
        }
    }
}
