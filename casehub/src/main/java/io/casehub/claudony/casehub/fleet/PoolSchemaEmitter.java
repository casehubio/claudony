package io.casehub.claudony.casehub.fleet;

import javax.annotation.processing.Filer;
import javax.lang.model.element.RecordComponentElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

final class PoolSchemaEmitter {

    void emit(String name, List<RecordComponentElement> fields, Filer filer) throws IOException {
        FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                "META-INF/pool-definitions/" + name + ".schema.json");

        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            w.println("{");
            w.println("  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\",");
            w.println("  \"type\": \"object\",");
            w.println("  \"properties\": {");

            for (int i = 0; i < fields.size(); i++) {
                RecordComponentElement field = fields.get(i);
                String yamlKey = toKebabCase(field.getSimpleName().toString());
                String jsonType = toJsonType(field.asType().toString());
                w.print("    \"" + yamlKey + "\": { \"type\": \"" + jsonType + "\" }");
                if (i < fields.size() - 1) w.print(",");
                w.println();
            }

            w.println("  },");
            w.println("  \"required\": [],");
            w.println("  \"additionalProperties\": false");
            w.println("}");
        }
    }

    static String toKebabCase(String camelCase) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String toJsonType(String javaType) {
        return switch (javaType) {
            case "java.lang.String" -> "string";
            case "int", "long", "java.lang.Integer", "java.lang.Long" -> "integer";
            case "double", "float", "java.lang.Double", "java.lang.Float" -> "number";
            case "boolean", "java.lang.Boolean" -> "boolean";
            default -> "string";
        };
    }
}
