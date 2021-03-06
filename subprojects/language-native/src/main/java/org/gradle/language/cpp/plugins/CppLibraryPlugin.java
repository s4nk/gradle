/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp.plugins;

import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.CppStaticLibrary;
import org.gradle.language.cpp.internal.DefaultCppLibrary;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.cpp.internal.MainLibraryVariant;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;

import javax.inject.Inject;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.gradle.language.cpp.CppBinary.*;

/**
 * <p>A plugin that produces a native library from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp`, public headers are located in `src/main/public` and implementation header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppLibrary} extension to the project to allow configuration of the library.</p>
 *
 * @since 4.1
 */
@Incubating
public class CppLibraryPlugin implements Plugin<ProjectInternal> {
    private final NativeComponentFactory componentFactory;
    private final ToolChainSelector toolChainSelector;
    private final ImmutableAttributesFactory attributesFactory;

    /**
     * Injects a {@link FileOperations} instance.
     *
     * @since 4.2
     */
    @Inject
    public CppLibraryPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        final TaskContainer tasks = project.getTasks();
        final ObjectFactory objectFactory = project.getObjects();
        final ProviderFactory providers = project.getProviders();

        // Add the library and extension
        final DefaultCppLibrary library = componentFactory.newInstance(CppLibrary.class, DefaultCppLibrary.class, "main");
        project.getExtensions().add(CppLibrary.class, "library", library);
        project.getComponents().add(library);

        // Configure the component
        library.getBaseName().set(project.getName());

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                library.getOperatingSystems().finalizeValue();
                Set<OperatingSystemFamily> operatingSystemFamilies = library.getOperatingSystems().get();
                if (operatingSystemFamilies.isEmpty()) {
                    throw new IllegalArgumentException("An operating system needs to be specified for the library.");
                }

                library.getLinkage().finalizeValue();
                Set<Linkage> linkages = library.getLinkage().get();
                if (linkages.isEmpty()) {
                    throw new IllegalArgumentException("A linkage needs to be specified for the library.");
                }

                Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);
                Usage linkUsage = objectFactory.named(Usage.class, Usage.NATIVE_LINK);

                for (BuildType buildType : BuildType.DEFAULT_BUILD_TYPES) {
                    for (OperatingSystemFamily operatingSystem : operatingSystemFamilies) {
                        for (Linkage linkage : linkages) {

                            String operatingSystemSuffix = createDimensionSuffix(operatingSystem, operatingSystemFamilies);
                            String linkageSuffix = createDimensionSuffix(linkage, linkages);
                            String variantName = buildType.getName() + linkageSuffix + operatingSystemSuffix;

                            Provider<String> group = project.provider(new Callable<String>() {
                                @Override
                                public String call() throws Exception {
                                    return project.getGroup().toString();
                                }
                            });

                            Provider<String> version = project.provider(new Callable<String>() {
                                @Override
                                public String call() throws Exception {
                                    return project.getVersion().toString();
                                }
                            });

                            AttributeContainer runtimeAttributes = attributesFactory.mutable();
                            runtimeAttributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
                            runtimeAttributes.attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable());
                            runtimeAttributes.attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized());
                            runtimeAttributes.attribute(LINKAGE_ATTRIBUTE, linkage);
                            runtimeAttributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, operatingSystem);

                            AttributeContainer linkAttributes = attributesFactory.mutable();
                            linkAttributes.attribute(Usage.USAGE_ATTRIBUTE, linkUsage);
                            linkAttributes.attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable());
                            linkAttributes.attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized());
                            linkAttributes.attribute(LINKAGE_ATTRIBUTE, linkage);
                            linkAttributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, operatingSystem);

                            NativeVariantIdentity variantIdentity = new NativeVariantIdentity(variantName, library.getBaseName(), group, version, buildType.isDebuggable(), buildType.isOptimized(), operatingSystem,
                                new DefaultUsageContext(variantName + "Link", linkUsage, linkAttributes),
                                new DefaultUsageContext(variantName + "Runtime", runtimeUsage, runtimeAttributes));

                            if (DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(operatingSystem.getName())) {
                                ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class);

                                if (linkage == Linkage.SHARED) {
                                    CppSharedLibrary sharedLibrary = library.addSharedLibrary(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                                    library.getMainPublication().addVariant(sharedLibrary);
                                    // Use the debug shared library as the development binary
                                    if (buildType == BuildType.DEBUG) {
                                        library.getDevelopmentBinary().set(sharedLibrary);
                                    }
                                } else {
                                    CppStaticLibrary staticLibrary = library.addStaticLibrary(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                                    library.getMainPublication().addVariant(staticLibrary);
                                    if (!linkages.contains(Linkage.SHARED) && buildType == BuildType.DEBUG) {
                                        // Use the debug static library as the development binary
                                        library.getDevelopmentBinary().set(staticLibrary);
                                    }
                                }

                            } else {
                                // Known, but not buildable
                                library.getMainPublication().addVariant(variantIdentity);
                            }
                        }
                    }
                }

                final MainLibraryVariant mainVariant = library.getMainPublication();

                final Configuration apiElements = library.getApiElements();
                // TODO - deal with more than one header dir, e.g. generated public headers
                Provider<File> publicHeaders = providers.provider(new Callable<File>() {
                    @Override
                    public File call() throws Exception {
                        Set<File> files = library.getPublicHeaderDirs().getFiles();
                        if (files.size() != 1) {
                            throw new UnsupportedOperationException(String.format("The C++ library plugin currently requires exactly one public header directory, however there are %d directories configured: %s", files.size(), files));
                        }
                        return files.iterator().next();
                    }
                });
                apiElements.getOutgoing().artifact(publicHeaders);

                project.getPluginManager().withPlugin("maven-publish", new Action<AppliedPlugin>() {
                    @Override
                    public void execute(AppliedPlugin appliedPlugin) {
                        final TaskProvider<Zip> headersZip = tasks.register("cppHeaders", Zip.class, new Action<Zip>() {
                            @Override
                            public void execute(Zip headersZip) {
                                headersZip.from(library.getPublicHeaderFiles());
                                headersZip.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("headers"));
                                headersZip.getArchiveClassifier().set("cpp-api-headers");
                                headersZip.getArchiveFileName().set("cpp-api-headers.zip");
                            }
                        });
                        mainVariant.addArtifact(new LazyPublishArtifact(headersZip));
                    }
                });

                library.getBinaries().realizeNow();
            }
        });
    }

    private String createDimensionSuffix(Named dimensionValue, Collection<? extends Named> multivalueProperty) {
        if (isDimensionVisible(multivalueProperty)) {
            return StringUtils.capitalize(dimensionValue.getName().toLowerCase());
        }
        return "";
    }

    private boolean isDimensionVisible(Collection<? extends Named> multivalueProperty) {
        return multivalueProperty.size() > 1;
    }

    private static final class BuildType implements Named {
        private static final BuildType DEBUG = new BuildType("debug", true, false);
        private static final BuildType RELEASE = new BuildType("release", true, true);
        public static final Collection<BuildType> DEFAULT_BUILD_TYPES = Arrays.asList(DEBUG, RELEASE);

        private final boolean debuggable;
        private final boolean optimized;
        private final String name;

        private BuildType(String name, boolean debuggable, boolean optimized) {
            this.debuggable = debuggable;
            this.optimized = optimized;
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        public boolean isDebuggable() {
            return debuggable;
        }

        public boolean isOptimized() {
            return optimized;
        }
    }
}
