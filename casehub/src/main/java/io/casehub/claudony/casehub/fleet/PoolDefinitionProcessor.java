package io.casehub.claudony.casehub.fleet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes("io.casehub.claudony.casehub.fleet.PoolDefinition")
public class PoolDefinitionProcessor extends AbstractProcessor {

    private boolean processed;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processed || roundEnv.processingOver()) return false;
        processed = true;

        for (Element element : roundEnv.getElementsAnnotatedWith(PoolDefinition.class)) {
            if (element.getKind() != ElementKind.RECORD) {
                error(element, "@PoolDefinition must be applied to a record");
                continue;
            }
            TypeElement typeElement = (TypeElement) element;
            PoolDefinition annotation = typeElement.getAnnotation(PoolDefinition.class);
            generate(annotation.value(), typeElement);
        }
        return false;
    }

    private void generate(String name, TypeElement typeElement) {
        List<RecordComponentElement> fields = new ArrayList<>(typeElement.getRecordComponents());
        try {
            new PoolSchemaEmitter().emit(name, fields, processingEnv.getFiler());
            new PoolManifestEmitter().emit(name, typeElement, processingEnv.getFiler());
        } catch (Exception e) {
            error(typeElement,
                    "Code generation failed for @PoolDefinition '" + name + "': " + e.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
