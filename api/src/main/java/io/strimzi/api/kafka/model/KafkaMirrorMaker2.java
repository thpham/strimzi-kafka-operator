/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import io.strimzi.api.kafka.model.status.HasStatus;
import io.strimzi.api.kafka.model.status.KafkaMirrorMaker2Status;
import io.strimzi.crdgenerator.annotations.Crd;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.Inline;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;

@JsonDeserialize
@Crd(
        apiVersion = KafkaMirrorMaker2.CRD_API_VERSION,
        spec = @Crd.Spec(
                names = @Crd.Spec.Names(
                        kind = KafkaMirrorMaker2.RESOURCE_KIND,
                        plural = KafkaMirrorMaker2.RESOURCE_PLURAL,
                        shortNames = {KafkaMirrorMaker2.SHORT_NAME}
                ),
                group = KafkaMirrorMaker2.RESOURCE_GROUP,
                scope = KafkaMirrorMaker2.SCOPE,
                version = KafkaMirrorMaker2.V1BETA1,
                versions = {
                        @Crd.Spec.Version(
                                name = KafkaMirrorMaker2.V1BETA1,
                                served = true,
                                storage = true
                        ),
                        @Crd.Spec.Version(
                                name = KafkaMirrorMaker2.V1ALPHA1,
                                served = true,
                                storage = false
                        )
                },
                subresources = @Crd.Spec.Subresources(
                        status = @Crd.Spec.Subresources.Status()
                ),
                additionalPrinterColumns = {
                        @Crd.Spec.AdditionalPrinterColumn(
                                name = "Desired replicas",
                                description = "The desired number of Kafka Mirror Maker 2 replicas",
                                jsonPath = ".spec.replicas",
                                type = "integer"
                        )
                }
        )
)
@Buildable(
        editableEnabled = false, 
        generateBuilderPackage = false, 
        builderPackage = "io.fabric8.kubernetes.api.builder", 
        inline = @Inline(type = Doneable.class, prefix = "Doneable", value = "done")
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "apiVersion", "kind", "metadata", "spec", "status" })
@EqualsAndHashCode
public class KafkaMirrorMaker2 extends CustomResource implements UnknownPropertyPreserving, HasStatus<KafkaMirrorMaker2Status> {

    private static final long serialVersionUID = 1L;

    public static final String SCOPE = "Namespaced";
    public static final String V1ALPHA1 = "v1alpha1";
    public static final String V1BETA1 = "v1beta1";
    public static final List<String> VERSIONS = unmodifiableList(asList(V1BETA1, V1ALPHA1));
    public static final String RESOURCE_KIND = "KafkaMirrorMaker2";
    public static final String RESOURCE_LIST_KIND = RESOURCE_KIND + "List";
    public static final String RESOURCE_GROUP = "kafka.strimzi.io";
    public static final String RESOURCE_PLURAL = "kafkamirrormaker2s";
    public static final String RESOURCE_SINGULAR = "kafkamirrormaker2";
    public static final String CRD_API_VERSION = "apiextensions.k8s.io/v1beta1";
    public static final String CRD_NAME = RESOURCE_PLURAL + "." + RESOURCE_GROUP;
    public static final String SHORT_NAME = "kmm2";
    public static final List<String> RESOURCE_SHORTNAMES = singletonList(SHORT_NAME);

    private String apiVersion;
    private KafkaMirrorMaker2Spec spec;
    private ObjectMeta metadata;
    private KafkaMirrorMaker2Status status;
    private Map<String, Object> additionalProperties = new HashMap<>(0);

    @Override
    public String getApiVersion() {
        return apiVersion;
    }

    @Override
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    @JsonProperty("kind")
    @Override
    public String getKind() {
        return RESOURCE_KIND;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Override
    public ObjectMeta getMetadata() {
        return super.getMetadata();
    }

    @Override
    public void setMetadata(ObjectMeta metadata) {
        super.setMetadata(metadata);
    }

    @Description("The specification of the Kafka Mirror Maker 2 cluster.")
    public KafkaMirrorMaker2Spec getSpec() {
        return spec;
    }

    public void setSpec(KafkaMirrorMaker2Spec spec) {
        this.spec = spec;
    }

    @Description("The status of the Kafka Mirror Maker 2 cluster.")
    public KafkaMirrorMaker2Status getStatus() {
        return status;
    }

    public void setStatus(KafkaMirrorMaker2Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        YAMLMapper mapper = new YAMLMapper().disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID);
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}
