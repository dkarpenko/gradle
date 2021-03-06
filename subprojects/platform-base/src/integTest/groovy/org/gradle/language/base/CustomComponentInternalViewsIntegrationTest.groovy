/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.language.base

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class CustomComponentInternalViewsIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        buildFile << """
            apply plugin: "jvm-component"

            interface SampleLibrarySpec extends ComponentSpec {
                String getPublicData()
                void setPublicData(String publicData)
            }

            interface BareInternalView {
                String getBareData()
                void setBareData(String bareData)
            }

            interface SampleLibrarySpecInternal extends ComponentSpec {
                String getInternalData()
                void setInternalData(String internalData)
            }

            class DefaultSampleLibrarySpec extends BaseComponentSpec implements SampleLibrarySpec, SampleLibrarySpecInternal, BareInternalView {
                String internalData
                String bareData
                String publicData
            }
        """
    }

    def setupRegistration() {
        buildFile << """
            class RegisterComponentRules extends RuleSource {
                @ComponentType
                void register(TypeBuilder<SampleLibrarySpec> builder) {
                    builder.defaultImplementation(DefaultSampleLibrarySpec)
                    builder.internalView(SampleLibrarySpecInternal)
                    builder.internalView(BareInternalView)
                }
            }
            apply plugin: RegisterComponentRules
        """
    }

    def setupModel() {
        buildFile << """
            model {
                components {
                    jar(JvmLibrarySpec) {}
                    sampleLib(SampleLibrarySpec) {}
                }
            }
        """
    }

    def setupValidateTask() {
        buildFile << """
        class ValidateTaskRules extends RuleSource {
            @Mutate
            void createValidateTask(ModelMap<Task> tasks, ComponentSpecContainer components) {
                tasks.create("validate") {
                    assert components*.name == ["jar", "sampleLib"]
                    assert components.withType(ComponentSpec)*.name == ["jar", "sampleLib"]
                    assert components.withType(JvmLibrarySpec)*.name == ["jar"]
                    assert components.withType(SampleLibrarySpec)*.name == ["sampleLib"]
                    assert components.withType(SampleLibrarySpecInternal)*.name == ["sampleLib"]
                    assert components.withType(BareInternalView)*.name == ["sampleLib"]
                }
            }
        }
        apply plugin: ValidateTaskRules
        """
    }

    def "can target internal view with rules"() {
        setupRegistration()
        setupModel()

        buildFile << """

        class Rules extends RuleSource {
            @Finalize
            void mutateInternal(ModelMap<SampleLibrarySpecInternal> sampleLibs) {
                sampleLibs.each { sampleLib ->
                    sampleLib.internalData = "internal"
                }
            }

            @Finalize
            void mutateComponentSpecInternal(ModelMap<VariantComponentSpec> sampleLibs) {
                sampleLibs.each { sampleLib ->
                    sampleLib.binaries {
                        sampleBin(JarBinarySpec)
                    }
                }
            }

            @Finalize
            void mutatePublic(ModelMap<SampleLibrarySpec> sampleLibs) {
                sampleLibs.each { sampleLib ->
                    sampleLib.publicData = "public"
                }
                sampleLibs.withType(BareInternalView).all { sampleLib ->
                    sampleLib.bareData = "bare"
                }
            }

            @Mutate
            void createValidateTask(ModelMap<Task> tasks, ModelMap<SampleLibrarySpecInternal> sampleLibs) {
                tasks.create("validate") {
                    assert sampleLibs.size() == 1
                    sampleLibs.each { sampleLib ->
                        assert sampleLib.internalData == "internal"
                        assert sampleLib.bareData == "bare"
                        assert sampleLib.publicData == "public"
                        assert sampleLib.binaries*.name == ["sampleBin"]
                    }
                }
            }
        }
        apply plugin: Rules
        """
        expect:
        succeeds "validate"
    }

    def "can filter for custom internal view with ComponentSpecContainer.withType()"() {
        setupRegistration()
        setupModel()
        setupValidateTask()

        expect:
        succeeds "validate"
    }

    def "can register internal view and default implementation separately"() {
        buildFile << """
            class RegisterComponentRules extends RuleSource {
                @ComponentType
                void registerComponent(TypeBuilder<SampleLibrarySpec> builder) {
                    builder.defaultImplementation(DefaultSampleLibrarySpec)
                }

                @ComponentType
                void registerInternalView(TypeBuilder<SampleLibrarySpec> builder) {
                    builder.internalView(SampleLibrarySpecInternal)
                    builder.internalView(BareInternalView)
                }
            }
            apply plugin: RegisterComponentRules
        """

        setupModel()
        setupValidateTask()

        expect:
        succeeds "validate"
    }

    def "fails when wrong internal view is registered separately"() {
        buildFile << """
            interface NotImplementedInternalView extends ComponentSpec {}

            class RegisterComponentRules extends RuleSource {
                @ComponentType
                void registerComponent(TypeBuilder<SampleLibrarySpec> builder) {
                    builder.defaultImplementation(DefaultSampleLibrarySpec)
                }

                @ComponentType
                void registerInternalView(TypeBuilder<SampleLibrarySpec> builder) {
                    builder.internalView(NotImplementedInternalView)
                }
            }
            apply plugin: RegisterComponentRules
        """

        setupModel()
        setupValidateTask()

        expect:
        def failure = fails("validate")
        failure.assertHasCause "Factory registration for 'SampleLibrarySpec' is invalid because the implementation type 'DefaultSampleLibrarySpec' does not implement internal view 'NotImplementedInternalView', implementation type was registered by RegisterComponentRules#registerComponent, internal view was registered by RegisterComponentRules#registerInternalView"
    }
}
