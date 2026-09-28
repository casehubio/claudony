package io.casehub.claudony.casehub.fleet;

import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;

final class PoolManifestEmitter {

    void emit(String name, TypeElement pluginClass, Filer filer) throws IOException {
        FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                "META-INF/pool-definitions/" + name + ".json");

        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            w.println("{");
            w.println("  \"name\": \"" + name + "\",");
            w.println("  \"recordClass\": \"" + pluginClass.getQualifiedName() + "\",");
            w.println("  \"schemaResource\": \"META-INF/pool-definitions/" + name + ".schema.json\"");
            w.println("}");
        }
    }
}
