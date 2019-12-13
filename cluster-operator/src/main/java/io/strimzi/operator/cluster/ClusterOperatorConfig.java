/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.strimzi.operator.cluster.model.ImagePullPolicy;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.NoImageException;
import io.strimzi.operator.common.InvalidConfigurationException;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.operator.resource.AbstractWatchableResourceOperator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;

/**
 * Cluster Operator configuration
 */
public class ClusterOperatorConfig {
    public static final String STRIMZI_NAMESPACE = "STRIMZI_NAMESPACE";
    public static final String STRIMZI_FULL_RECONCILIATION_INTERVAL_MS = "STRIMZI_FULL_RECONCILIATION_INTERVAL_MS";
    public static final String STRIMZI_OPERATION_TIMEOUT_MS = "STRIMZI_OPERATION_TIMEOUT_MS";
    public static final String STRIMZI_CREATE_CLUSTER_ROLES = "STRIMZI_CREATE_CLUSTER_ROLES";
    public static final String STRIMZI_IMAGE_PULL_POLICY = "STRIMZI_IMAGE_PULL_POLICY";
    public static final String STRIMZI_IMAGE_PULL_SECRETS = "STRIMZI_IMAGE_PULL_SECRETS";

    // Env vars for configuring images
    public static final String STRIMZI_KAFKA_IMAGES = "STRIMZI_KAFKA_IMAGES";
    public static final String STRIMZI_KAFKA_CONNECT_IMAGES = "STRIMZI_KAFKA_CONNECT_IMAGES";
    public static final String STRIMZI_KAFKA_CONNECT_S2I_IMAGES = "STRIMZI_KAFKA_CONNECT_S2I_IMAGES";
    public static final String STRIMZI_KAFKA_MIRROR_MAKER_IMAGES = "STRIMZI_KAFKA_MIRROR_MAKER_IMAGES";
    public static final String STRIMZI_KAFKA_MIRROR_MAKER_2_IMAGES = "STRIMZI_KAFKA_MIRROR_MAKER_2_IMAGES";
    public static final String STRIMZI_DEFAULT_ZOOKEEPER_IMAGE = "STRIMZI_DEFAULT_ZOOKEEPER_IMAGE";
    public static final String STRIMZI_DEFAULT_TLS_SIDECAR_ENTITY_OPERATOR_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_ENTITY_OPERATOR_IMAGE";
    public static final String STRIMZI_DEFAULT_TLS_SIDECAR_KAFKA_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_KAFKA_IMAGE";
    public static final String STRIMZI_DEFAULT_TLS_SIDECAR_ZOOKEEPER_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_ZOOKEEPER_IMAGE";
    public static final String STRIMZI_DEFAULT_KAFKA_EXPORTER_IMAGE = "STRIMZI_DEFAULT_KAFKA_EXPORTER_IMAGE";
    public static final String STRIMZI_DEFAULT_TOPIC_OPERATOR_IMAGE = "STRIMZI_DEFAULT_TOPIC_OPERATOR_IMAGE";
    public static final String STRIMZI_DEFAULT_USER_OPERATOR_IMAGE = "STRIMZI_DEFAULT_USER_OPERATOR_IMAGE";
    public static final String STRIMZI_DEFAULT_KAFKA_INIT_IMAGE = "STRIMZI_DEFAULT_KAFKA_INIT_IMAGE";
    public static final String STRIMZI_DEFAULT_KAFKA_BRIDGE_IMAGE = "STRIMZI_DEFAULT_KAFKA_BRIDGE_IMAGE";

    public static final long DEFAULT_FULL_RECONCILIATION_INTERVAL_MS = 120_000;
    public static final long DEFAULT_OPERATION_TIMEOUT_MS = 300_000;
    public static final boolean DEFAULT_CREATE_CLUSTER_ROLES = false;

    private final Set<String> namespaces;
    private final long reconciliationIntervalMs;
    private final long operationTimeoutMs;
    private final boolean createClusterRoles;
    private final KafkaVersion.Lookup versions;
    private final ImagePullPolicy imagePullPolicy;
    private final List<LocalObjectReference> imagePullSecrets;

    /**
     * Constructor
     *
     * @param namespaces namespace in which the operator will run and create resources
     * @param reconciliationIntervalMs    specify every how many milliseconds the reconciliation runs
     * @param operationTimeoutMs    timeout for internal operations specified in milliseconds
     * @param createClusterRoles true to create the cluster roles
     * @param versions The configured Kafka versions
     * @param imagePullPolicy Image pull policy configured by the user
     * @param imagePullSecrets Set of secrets for pulling container images from secured repositories
     */
    public ClusterOperatorConfig(Set<String> namespaces, long reconciliationIntervalMs, long operationTimeoutMs, boolean createClusterRoles, KafkaVersion.Lookup versions, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        this.namespaces = unmodifiableSet(new HashSet<>(namespaces));
        this.reconciliationIntervalMs = reconciliationIntervalMs;
        this.operationTimeoutMs = operationTimeoutMs;
        this.createClusterRoles = createClusterRoles;
        this.versions = versions;
        this.imagePullPolicy = imagePullPolicy;
        this.imagePullSecrets = imagePullSecrets;
    }

    /**
     * Loads configuration parameters from a related map
     *
     * @param map   map from which loading configuration parameters
     * @return  Cluster Operator configuration instance
     */
    public static ClusterOperatorConfig fromMap(Map<String, String> map) {
        KafkaVersion.Lookup lookup = parseKafkaVersions(map.get(STRIMZI_KAFKA_IMAGES), map.get(STRIMZI_KAFKA_CONNECT_IMAGES), map.get(STRIMZI_KAFKA_CONNECT_S2I_IMAGES), map.get(STRIMZI_KAFKA_MIRROR_MAKER_IMAGES), map.get(STRIMZI_KAFKA_MIRROR_MAKER_2_IMAGES));
        return fromMap(map, lookup);
    }

    /**
     * Loads configuration parameters from a related map and custom KafkaVersion.Lookup instance.
     * THis is used for testing.
     *
     * @param map   map from which loading configuration parameters
     * @param lookup KafkaVersion.Lookup instance with the supported Kafka version information
     * @return  Cluster Operator configuration instance
     */
    static ClusterOperatorConfig fromMap(Map<String, String> map, KafkaVersion.Lookup lookup) {
        Set<String> namespaces = parseNamespaceList(map.get(ClusterOperatorConfig.STRIMZI_NAMESPACE));
        long reconciliationInterval = parseReconciliationInerval(map.get(ClusterOperatorConfig.STRIMZI_FULL_RECONCILIATION_INTERVAL_MS));
        long operationTimeout = parseOperationTimeout(map.get(ClusterOperatorConfig.STRIMZI_OPERATION_TIMEOUT_MS));
        boolean createClusterRoles = parseCreateClusterRoles(map.get(ClusterOperatorConfig.STRIMZI_CREATE_CLUSTER_ROLES));
        ImagePullPolicy imagePullPolicy = parseImagePullPolicy(map.get(ClusterOperatorConfig.STRIMZI_IMAGE_PULL_POLICY));
        List<LocalObjectReference> imagePullSecrets = parseImagePullSecrets(map.get(ClusterOperatorConfig.STRIMZI_IMAGE_PULL_SECRETS));
        return new ClusterOperatorConfig(namespaces, reconciliationInterval, operationTimeout, createClusterRoles, lookup, imagePullPolicy, imagePullSecrets);

    }

    private static Set<String> parseNamespaceList(String namespacesList)   {
        Set<String> namespaces;
        if (namespacesList == null || namespacesList.isEmpty()) {
            namespaces = Collections.singleton(AbstractWatchableResourceOperator.ANY_NAMESPACE);
        } else {
            if (namespacesList.trim().equals(AbstractWatchableResourceOperator.ANY_NAMESPACE)) {
                namespaces = Collections.singleton(AbstractWatchableResourceOperator.ANY_NAMESPACE);
            } else if (namespacesList.matches("(\\s*[a-z0-9.-]+\\s*,)*\\s*[a-z0-9.-]+\\s*")) {
                namespaces = new HashSet<>(asList(namespacesList.trim().split("\\s*,+\\s*")));
            } else {
                throw new InvalidConfigurationException(ClusterOperatorConfig.STRIMZI_NAMESPACE
                        + " is not a valid list of namespaces nor the 'any namespace' wildcard "
                        + AbstractWatchableResourceOperator.ANY_NAMESPACE);
            }
        }

        return namespaces;
    }

    private static long parseReconciliationInerval(String reconciliationIntervalEnvVar) {
        long reconciliationInterval = DEFAULT_FULL_RECONCILIATION_INTERVAL_MS;

        if (reconciliationIntervalEnvVar != null) {
            reconciliationInterval = Long.parseLong(reconciliationIntervalEnvVar);
        }

        return reconciliationInterval;
    }

    private static long parseOperationTimeout(String operationTimeoutEnvVar) {
        long operationTimeout = DEFAULT_OPERATION_TIMEOUT_MS;

        if (operationTimeoutEnvVar != null) {
            operationTimeout = Long.parseLong(operationTimeoutEnvVar);
        }

        return operationTimeout;
    }

    private static boolean parseCreateClusterRoles(String createClusterRolesEnvVar) {
        boolean createClusterRoles = DEFAULT_CREATE_CLUSTER_ROLES;

        if (createClusterRolesEnvVar != null) {
            createClusterRoles = Boolean.parseBoolean(createClusterRolesEnvVar);
        }

        return createClusterRoles;
    }

    private static ImagePullPolicy parseImagePullPolicy(String imagePullPolicyEnvVar) {
        ImagePullPolicy imagePullPolicy = null;

        if (imagePullPolicyEnvVar != null) {
            switch (imagePullPolicyEnvVar.trim().toLowerCase(Locale.ENGLISH)) {
                case "always":
                    imagePullPolicy = ImagePullPolicy.ALWAYS;
                    break;
                case "ifnotpresent":
                    imagePullPolicy = ImagePullPolicy.IFNOTPRESENT;
                    break;
                case "never":
                    imagePullPolicy = ImagePullPolicy.NEVER;
                    break;
                default:
                    throw new InvalidConfigurationException(imagePullPolicyEnvVar
                            + " is not a valid " + ClusterOperatorConfig.STRIMZI_IMAGE_PULL_POLICY + " value. " +
                            ClusterOperatorConfig.STRIMZI_IMAGE_PULL_POLICY + " can have one of the following values: Always, IfNotPresent, Never.");
            }
        }

        return imagePullPolicy;
    }

    private static KafkaVersion.Lookup parseKafkaVersions(String kafkaImages, String connectImages, String connectS2IImages, String mirrorMakerImages, String mirrorMaker2Images) {
        KafkaVersion.Lookup lookup = new KafkaVersion.Lookup(
                Util.parseMap(kafkaImages),
                Util.parseMap(connectImages),
                Util.parseMap(connectS2IImages),
                Util.parseMap(mirrorMakerImages),
                Util.parseMap(mirrorMaker2Images));
        try {
            lookup.validateKafkaImages(lookup.supportedVersions());
            lookup.validateKafkaConnectImages(lookup.supportedVersions());
            lookup.validateKafkaConnectS2IImages(lookup.supportedVersions());
            lookup.validateKafkaMirrorMakerImages(lookup.supportedVersions());
            lookup.validateKafkaMirrorMaker2Images(lookup.supportedVersions());
        } catch (NoImageException e) {
            throw new InvalidConfigurationException(e);
        }
        return lookup;
    }

    private static List<LocalObjectReference> parseImagePullSecrets(String imagePullSecretList) {
        List<LocalObjectReference> imagePullSecrets = null;

        if (imagePullSecretList != null && !imagePullSecretList.isEmpty()) {
            if (imagePullSecretList.matches("(\\s*[a-z0-9.-]+\\s*,)*\\s*[a-z0-9.-]+\\s*")) {
                imagePullSecrets = Arrays.stream(imagePullSecretList.trim().split("\\s*,+\\s*")).map(secret -> new LocalObjectReferenceBuilder().withName(secret).build()).collect(Collectors.toList());
            } else {
                throw new InvalidConfigurationException(ClusterOperatorConfig.STRIMZI_IMAGE_PULL_SECRETS
                        + " is not a valid list of secret names");
            }
        }

        return imagePullSecrets;
    }

    /**
     * @return  namespaces in which the operator runs and creates resources
     */
    public Set<String> getNamespaces() {
        return namespaces;
    }

    /**
     * @return  how many milliseconds the reconciliation runs
     */
    public long getReconciliationIntervalMs() {
        return reconciliationIntervalMs;
    }

    /**
     * @return  how many milliseconds should we wait for Kubernetes operations
     */
    public long getOperationTimeoutMs() {
        return operationTimeoutMs;
    }

    /**
     * @return  Indicates whether Cluster Roles should be created
     */
    public boolean isCreateClusterRoles() {
        return createClusterRoles;
    }

    public KafkaVersion.Lookup versions() {
        return versions;
    }

    /**
     * @return  The user-configure image pull policy. Null if it was not configured.
     */
    public ImagePullPolicy getImagePullPolicy() {
        return imagePullPolicy;
    }

    /**
     * @return Retuns list of configured ImagePullSecrets. Null if no secrets were configured.
     */
    public List<LocalObjectReference> getImagePullSecrets() {
        return imagePullSecrets;
    }

    @Override
    public String toString() {
        return "ClusterOperatorConfig(" +
                "namespaces=" + namespaces +
                ",reconciliationIntervalMs=" + reconciliationIntervalMs +
                ",operationTimeoutMs=" + operationTimeoutMs +
                ",createClusterRoles=" + createClusterRoles +
                ",versions=" + versions +
                ",imagePullPolicy=" + imagePullPolicy +
                ",imagePullSecrets=" + imagePullSecrets +
                ")";
    }
}
