package org.carddemo.platform.testsupport;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItemExtension;
import org.carddemo.platform.fidelity.SpecItemIds;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** The class-template provider behind {@link BothModes}. */
public class BothModesExtension implements ClassTemplateInvocationContextProvider {

    @Override
    public boolean supportsClassTemplate(ExtensionContext context) {
        return annotation(context.getRequiredTestClass()) != null;
    }

    @Override
    public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(ExtensionContext context) {
        BothModes both = annotation(context.getRequiredTestClass());
        List<String> ids = Arrays.stream(both.value()).map(SpecItemIds::require).toList();
        return Stream.of(FidelityMode.LEGACY, FidelityMode.FIXED).map(mode -> new ClassTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "[" + mode.key() + "]";
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return List.of(new ModeCallbacks(ids, mode));
            }
        });
    }

    private static BothModes annotation(Class<?> testClass) {
        return AnnotationSupport.findAnnotation(testClass, BothModes.class).orElse(null);
    }

    private record ModeCallbacks(List<String> ids, FidelityMode mode) implements BeforeEachCallback, AfterEachCallback {
        @Override
        public void beforeEach(ExtensionContext context) {
            FidelityOverrides.set(ids, mode);
            SpecItemExtension.recordMode(context, mode);
        }

        @Override
        public void afterEach(ExtensionContext context) {
            FidelityOverrides.clear();
        }
    }
}
