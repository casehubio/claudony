package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PoolDefinitionProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesSchemaAndManifest() throws Exception {
        var source = """
                package test;

                import io.casehub.claudony.casehub.fleet.PoolDefinition;
                import io.casehub.yaml.plugin.api.Optional;

                @PoolDefinition("code-reviewer")
                public record CodeReviewerPool(
                    @Optional String workingDir,
                    @Optional String command,
                    @Optional int minActive,
                    @Optional int maxActive
                ) {}
                """;

        var outputDir = tempDir.resolve("classes");
        Files.createDirectories(outputDir);
        compile(source, "test/CodeReviewerPool.java", outputDir);

        var manifest = outputDir.resolve("META-INF/pool-definitions/code-reviewer.json");
        assertThat(manifest).exists();
        var manifestContent = Files.readString(manifest);
        assertThat(manifestContent).contains("\"name\": \"code-reviewer\"");
        assertThat(manifestContent).contains("\"recordClass\": \"test.CodeReviewerPool\"");

        var schema = outputDir.resolve("META-INF/pool-definitions/code-reviewer.schema.json");
        assertThat(schema).exists();
        var schemaContent = Files.readString(schema);
        assertThat(schemaContent).contains("\"working-dir\"");
        assertThat(schemaContent).contains("\"min-active\"");
        assertThat(schemaContent).contains("\"type\": \"integer\"");
        assertThat(schemaContent).contains("\"type\": \"string\"");
    }

    @Test
    void rejectsNonRecord() throws Exception {
        var source = """
                package test;

                import io.casehub.claudony.casehub.fleet.PoolDefinition;

                @PoolDefinition("bad")
                public class NotARecord {}
                """;

        var outputDir = tempDir.resolve("classes");
        Files.createDirectories(outputDir);

        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        compileWithDiagnostics(source, "test/NotARecord.java", outputDir, diagnostics);

        assertThat(diagnostics).anyMatch(d ->
                d.getMessage(null).contains("must be applied to a record"));
    }

    private void compile(String source, String fileName, Path outputDir) throws IOException {
        var sourceFile = tempDir.resolve(fileName);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            var compilationUnits = fm.getJavaFileObjects(sourceFile.toFile());
            var options = List.of("-d", outputDir.toString(),
                    "-classpath", System.getProperty("java.class.path"),
                    "-proc:only",
                    "-processor", "io.casehub.claudony.casehub.fleet.PoolDefinitionProcessor");
            var task = compiler.getTask(null, fm, null, options, null, compilationUnits);
            assertThat(task.call()).isTrue();
        }
    }

    private void compileWithDiagnostics(String source, String fileName, Path outputDir,
                                         List<Diagnostic<? extends JavaFileObject>> diagnostics) throws IOException {
        var sourceFile = tempDir.resolve(fileName);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            var listener = new DiagnosticCollector<JavaFileObject>();
            var compilationUnits = fm.getJavaFileObjects(sourceFile.toFile());
            var options = List.of("-d", outputDir.toString(),
                    "-classpath", System.getProperty("java.class.path"),
                    "-proc:only",
                    "-processor", "io.casehub.claudony.casehub.fleet.PoolDefinitionProcessor");
            var task = compiler.getTask(null, fm, listener, options, null, compilationUnits);
            task.call();
            diagnostics.addAll(listener.getDiagnostics());
        }
    }
}
