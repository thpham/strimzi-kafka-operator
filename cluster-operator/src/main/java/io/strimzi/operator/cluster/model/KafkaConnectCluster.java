/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSource;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategy;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder;
import io.fabric8.kubernetes.api.model.apps.RollingUpdateDeploymentBuilder;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectS2ISpec;
import io.strimzi.api.kafka.model.KafkaConnectSpec;
import io.strimzi.api.kafka.model.KafkaConnectTls;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.authentication.KafkaClientAuthentication;
import io.strimzi.api.kafka.model.connect.ExternalConfiguration;
import io.strimzi.api.kafka.model.connect.ExternalConfigurationEnv;
import io.strimzi.api.kafka.model.connect.ExternalConfigurationEnvVarSource;
import io.strimzi.api.kafka.model.connect.ExternalConfigurationVolumeSource;
import io.strimzi.api.kafka.model.template.KafkaConnectTemplate;
import io.strimzi.api.kafka.model.tracing.Tracing;
import io.strimzi.operator.common.model.Labels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.strimzi.operator.cluster.model.ModelUtils.createHttpProbe;

public class KafkaConnectCluster extends AbstractModel {

    // Port configuration
    public static final int REST_API_PORT = 8083;
    protected static final String REST_API_PORT_NAME = "rest-api";

    protected static final String TLS_CERTS_BASE_VOLUME_MOUNT = "/opt/kafka/connect-certs/";
    protected static final String PASSWORD_VOLUME_MOUNT = "/opt/kafka/connect-password/";
    protected static final String EXTERNAL_CONFIGURATION_VOLUME_MOUNT_BASE_PATH = "/opt/kafka/external-configuration/";
    protected static final String EXTERNAL_CONFIGURATION_VOLUME_NAME_PREFIX = "ext-conf-";

    // Configuration defaults
    protected static final int DEFAULT_REPLICAS = 3;
    static final int DEFAULT_HEALTHCHECK_DELAY = 60;
    static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    public static final Probe DEFAULT_HEALTHCHECK_OPTIONS = new ProbeBuilder().withInitialDelaySeconds(DEFAULT_HEALTHCHECK_TIMEOUT)
            .withInitialDelaySeconds(DEFAULT_HEALTHCHECK_DELAY).build();
    protected static final boolean DEFAULT_KAFKA_CONNECT_METRICS_ENABLED = false;

    // Kafka Connect configuration keys (EnvVariables)
    protected static final String ENV_VAR_PREFIX = "KAFKA_CONNECT_";
    protected static final String ENV_VAR_KAFKA_CONNECT_CONFIGURATION = "KAFKA_CONNECT_CONFIGURATION";
    protected static final String ENV_VAR_KAFKA_CONNECT_METRICS_ENABLED = "KAFKA_CONNECT_METRICS_ENABLED";
    protected static final String ENV_VAR_KAFKA_CONNECT_BOOTSTRAP_SERVERS = "KAFKA_CONNECT_BOOTSTRAP_SERVERS";
    protected static final String ENV_VAR_KAFKA_CONNECT_TLS = "KAFKA_CONNECT_TLS";
    protected static final String ENV_VAR_KAFKA_CONNECT_TRUSTED_CERTS = "KAFKA_CONNECT_TRUSTED_CERTS";
    protected static final String ENV_VAR_KAFKA_CONNECT_TLS_AUTH_CERT = "KAFKA_CONNECT_TLS_AUTH_CERT";
    protected static final String ENV_VAR_KAFKA_CONNECT_TLS_AUTH_KEY = "KAFKA_CONNECT_TLS_AUTH_KEY";
    protected static final String ENV_VAR_KAFKA_CONNECT_SASL_PASSWORD_FILE = "KAFKA_CONNECT_SASL_PASSWORD_FILE";
    protected static final String ENV_VAR_KAFKA_CONNECT_SASL_USERNAME = "KAFKA_CONNECT_SASL_USERNAME";
    protected static final String ENV_VAR_KAFKA_CONNECT_SASL_MECHANISM = "KAFKA_CONNECT_SASL_MECHANISM";
    protected static final String ENV_VAR_KAFKA_CONNECT_OAUTH_CONFIG = "KAFKA_CONNECT_OAUTH_CONFIG";
    protected static final String ENV_VAR_KAFKA_CONNECT_OAUTH_CLIENT_SECRET = "KAFKA_CONNECT_OAUTH_CLIENT_SECRET";
    protected static final String ENV_VAR_KAFKA_CONNECT_OAUTH_ACCESS_TOKEN = "KAFKA_CONNECT_OAUTH_ACCESS_TOKEN";
    protected static final String ENV_VAR_KAFKA_CONNECT_OAUTH_REFRESH_TOKEN = "KAFKA_CONNECT_OAUTH_REFRESH_TOKEN";
    protected static final String OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT = "/opt/kafka/oauth-certs/";
    protected static final String ENV_VAR_STRIMZI_TRACING = "STRIMZI_TRACING";

    protected String bootstrapServers;
    protected List<ExternalConfigurationEnv> externalEnvs = Collections.emptyList();
    protected List<ExternalConfigurationVolumeSource> externalVolumes = Collections.emptyList();
    protected List<ContainerEnvVar> templateContainerEnvVars;
    protected Tracing tracing;

    private KafkaConnectTls tls;
    private KafkaClientAuthentication authentication;

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Kafka Connect cluster resources are going to be created
     * @param cluster   overall cluster name
     * @param labels    labels to add to the cluster
     */
    protected KafkaConnectCluster(String namespace, String cluster, Labels labels) {
        super(namespace, cluster, labels);
        this.name = KafkaConnectResources.deploymentName(cluster);
        this.serviceName = KafkaConnectResources.serviceName(cluster);
        this.ancillaryConfigName = KafkaConnectResources.metricsAndLogConfigMapName(cluster);
        this.replicas = DEFAULT_REPLICAS;
        this.readinessPath = "/";
        this.readinessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.livenessPath = "/";
        this.livenessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.isMetricsEnabled = DEFAULT_KAFKA_CONNECT_METRICS_ENABLED;

        this.mountPath = "/var/lib/kafka";
        this.logAndMetricsConfigVolumeName = "kafka-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/kafka/custom-config/";
    }

    public static KafkaConnectCluster fromCrd(KafkaConnect kafkaConnect, KafkaVersion.Lookup versions) {
        KafkaConnectCluster cluster = fromSpec(kafkaConnect.getSpec(), versions, new KafkaConnectCluster(kafkaConnect.getMetadata().getNamespace(),
                kafkaConnect.getMetadata().getName(), Labels.fromResource(kafkaConnect).withKind(kafkaConnect.getKind())));

        cluster.setOwnerReference(kafkaConnect);

        return cluster;
    }

    /**
     * Abstracts the calling of setters on a (subclass of) KafkaConnectCluster
     * from the instantiation of the (subclass of) KafkaConnectCluster,
     * thus permitting reuse of the setter-calling code for subclasses.
     */
    protected static <C extends KafkaConnectCluster> C fromSpec(KafkaConnectSpec spec,
                                                                KafkaVersion.Lookup versions,
                                                                C kafkaConnect) {
        kafkaConnect.setReplicas(spec.getReplicas() != null && spec.getReplicas() >= 0 ? spec.getReplicas() : DEFAULT_REPLICAS);
        kafkaConnect.tracing = spec.getTracing();

        KafkaConnectConfiguration config = new KafkaConnectConfiguration(spec.getConfig().entrySet());
        if (kafkaConnect.tracing != null)   {
            config.setConfigOption("consumer.interceptor.classes", "io.opentracing.contrib.kafka.TracingConsumerInterceptor");
            config.setConfigOption("producer.interceptor.classes", "io.opentracing.contrib.kafka.TracingProducerInterceptor");
        }
        kafkaConnect.setConfiguration(config);

        if (kafkaConnect.getImage() == null) {
            String image = spec instanceof KafkaConnectS2ISpec ?
                    versions.kafkaConnectS2IVersion(spec.getImage(), spec.getVersion())
                    : versions.kafkaConnectVersion(spec.getImage(), spec.getVersion());
            kafkaConnect.setImage(image);
        }

        kafkaConnect.setResources(spec.getResources());
        kafkaConnect.setLogging(spec.getLogging());
        kafkaConnect.setGcLoggingEnabled(spec.getJvmOptions() == null ? DEFAULT_JVM_GC_LOGGING_ENABLED : spec.getJvmOptions().isGcLoggingEnabled());
        kafkaConnect.setJvmOptions(spec.getJvmOptions());
        if (spec.getReadinessProbe() != null) {
            kafkaConnect.setReadinessProbe(spec.getReadinessProbe());
        }
        if (spec.getLivenessProbe() != null) {
            kafkaConnect.setLivenessProbe(spec.getLivenessProbe());
        }

        Map<String, Object> metrics = spec.getMetrics();
        if (metrics != null) {
            kafkaConnect.setMetricsEnabled(true);
            kafkaConnect.setMetricsConfig(metrics.entrySet());
        }
        kafkaConnect.setUserAffinity(affinity(spec));
        kafkaConnect.setTolerations(tolerations(spec));
        kafkaConnect.setBootstrapServers(spec.getBootstrapServers());

        kafkaConnect.setTls(spec.getTls());
        AuthenticationUtils.validateClientAuthentication(spec.getAuthentication(), spec.getTls() != null);
        kafkaConnect.setAuthentication(spec.getAuthentication());

        if (spec.getTemplate() != null) {
            KafkaConnectTemplate template = spec.getTemplate();

            if (template.getDeployment() != null && template.getDeployment().getMetadata() != null)  {
                kafkaConnect.templateDeploymentLabels = template.getDeployment().getMetadata().getLabels();
                kafkaConnect.templateDeploymentAnnotations = template.getDeployment().getMetadata().getAnnotations();
            }

            ModelUtils.parsePodTemplate(kafkaConnect, template.getPod());

            if (template.getApiService() != null && template.getApiService().getMetadata() != null)  {
                kafkaConnect.templateServiceLabels = template.getApiService().getMetadata().getLabels();
                kafkaConnect.templateServiceAnnotations = template.getApiService().getMetadata().getAnnotations();
            }

            if (template.getConnectContainer() != null && template.getConnectContainer().getEnv() != null) {
                kafkaConnect.templateContainerEnvVars = template.getConnectContainer().getEnv();
            }

            ModelUtils.parsePodDisruptionBudgetTemplate(kafkaConnect, template.getPodDisruptionBudget());
        }

        if (spec.getExternalConfiguration() != null)    {
            ExternalConfiguration externalConfiguration = spec.getExternalConfiguration();

            if (externalConfiguration.getEnv() != null && !externalConfiguration.getEnv().isEmpty())    {
                kafkaConnect.externalEnvs = externalConfiguration.getEnv();
            }

            if (externalConfiguration.getVolumes() != null && !externalConfiguration.getVolumes().isEmpty())    {
                kafkaConnect.externalVolumes = externalConfiguration.getVolumes();
            }
        }

        return kafkaConnect;
    }

    @SuppressWarnings("deprecation")
    private static <C extends KafkaConnectCluster> List<Toleration> tolerations(KafkaConnectSpec spec) {
        if (spec.getTemplate() != null
                && spec.getTemplate().getPod() != null
                && spec.getTemplate().getPod().getTolerations() != null) {
            if (spec.getTolerations() != null) {
                log.warn("Tolerations given on both spec.tolerations and spec.template.deployment.tolerations; latter takes precedence");
            }
            return spec.getTemplate().getPod().getTolerations();
        } else {
            return spec.getTolerations();
        }
    }

    @SuppressWarnings("deprecation")
    private static <C extends KafkaConnectCluster> Affinity affinity(KafkaConnectSpec spec) {
        if (spec.getTemplate() != null
                && spec.getTemplate().getPod() != null
                && spec.getTemplate().getPod().getAffinity() != null) {
            if (spec.getAffinity() != null) {
                log.warn("Affinity given on both spec.affinity and spec.template.deployment.affinity; latter takes precedence");
            }
            return spec.getTemplate().getPod().getAffinity();
        } else {
            return spec.getAffinity();
        }
    }

    public Service generateService() {
        List<ServicePort> ports = new ArrayList<>(2);
        ports.add(createServicePort(REST_API_PORT_NAME, REST_API_PORT, REST_API_PORT, "TCP"));
        if (isMetricsEnabled()) {
            ports.add(createServicePort(METRICS_PORT_NAME, METRICS_PORT, METRICS_PORT, "TCP"));
        }

        return createService("ClusterIP", ports, mergeLabelsOrAnnotations(getPrometheusAnnotations(), templateServiceAnnotations));
    }

    protected List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(2);
        portList.add(createContainerPort(REST_API_PORT_NAME, REST_API_PORT, "TCP"));
        if (isMetricsEnabled) {
            portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        }

        return portList;
    }

    protected List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(1);
        volumeList.add(createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigName));

        if (tls != null) {
            List<CertSecretSource> trustedCertificates = tls.getTrustedCertificates();

            if (trustedCertificates != null && trustedCertificates.size() > 0) {
                for (CertSecretSource certSecretSource : trustedCertificates) {
                    // skipping if a volume with same Secret name was already added
                    if (!volumeList.stream().anyMatch(v -> v.getName().equals(certSecretSource.getSecretName()))) {
                        volumeList.add(createSecretVolume(certSecretSource.getSecretName(), certSecretSource.getSecretName(), isOpenShift));
                    }
                }
            }
        }

        AuthenticationUtils.configureClientAuthenticationVolumes(authentication, volumeList, isOpenShift);

        volumeList.addAll(getExternalConfigurationVolumes(isOpenShift));

        return volumeList;
    }

    private List<Volume> getExternalConfigurationVolumes(boolean isOpenShift)  {
        int mode = 0444;
        if (isOpenShift) {
            mode = 0440;
        }

        List<Volume> volumeList = new ArrayList<>(0);

        for (ExternalConfigurationVolumeSource volume : externalVolumes)    {
            String name = volume.getName();

            if (name != null) {
                if (volume.getConfigMap() != null && volume.getSecret() != null) {
                    log.warn("Volume {} with external Kafka Connect configuration has to contain exactly one volume source reference to either ConfigMap or Secret", name);
                } else  {
                    if (volume.getConfigMap() != null) {
                        ConfigMapVolumeSource source = volume.getConfigMap();
                        source.setDefaultMode(mode);

                        Volume newVol = new VolumeBuilder()
                                .withName(EXTERNAL_CONFIGURATION_VOLUME_NAME_PREFIX + name)
                                .withConfigMap(source)
                                .build();

                        volumeList.add(newVol);
                    } else if (volume.getSecret() != null)    {
                        SecretVolumeSource source = volume.getSecret();
                        source.setDefaultMode(mode);

                        Volume newVol = new VolumeBuilder()
                                .withName(EXTERNAL_CONFIGURATION_VOLUME_NAME_PREFIX + name)
                                .withSecret(source)
                                .build();

                        volumeList.add(newVol);
                    }
                }
            }
        }

        return volumeList;
    }

    protected List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>(1);
        volumeMountList.add(createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));

        if (tls != null) {
            List<CertSecretSource> trustedCertificates = tls.getTrustedCertificates();

            if (trustedCertificates != null && trustedCertificates.size() > 0) {
                for (CertSecretSource certSecretSource : trustedCertificates) {
                    // skipping if a volume mount with same Secret name was already added
                    if (!volumeMountList.stream().anyMatch(vm -> vm.getName().equals(certSecretSource.getSecretName()))) {
                        volumeMountList.add(createVolumeMount(certSecretSource.getSecretName(),
                                TLS_CERTS_BASE_VOLUME_MOUNT + certSecretSource.getSecretName()));
                    }
                }
            }
        }

        AuthenticationUtils.configureClientAuthenticationVolumeMounts(authentication, volumeMountList, TLS_CERTS_BASE_VOLUME_MOUNT, PASSWORD_VOLUME_MOUNT, OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT);

        volumeMountList.addAll(getExternalConfigurationVolumeMounts());

        return volumeMountList;
    }

    private List<VolumeMount> getExternalConfigurationVolumeMounts()    {
        List<VolumeMount> volumeMountList = new ArrayList<>(0);

        for (ExternalConfigurationVolumeSource volume : externalVolumes)    {
            String name = volume.getName();

            if (name != null)   {
                if (volume.getConfigMap() != null && volume.getSecret() != null) {
                    log.warn("Volume {} with external Kafka Connect configuration has to contain exactly one volume source reference to either ConfigMap or Secret", name);
                } else  if (volume.getConfigMap() != null || volume.getSecret() != null) {
                    VolumeMount volumeMount = new VolumeMountBuilder()
                            .withName(EXTERNAL_CONFIGURATION_VOLUME_NAME_PREFIX + name)
                            .withMountPath(EXTERNAL_CONFIGURATION_VOLUME_MOUNT_BASE_PATH + name)
                            .build();

                    volumeMountList.add(volumeMount);
                }
            }
        }

        return volumeMountList;
    }

    public Deployment generateDeployment(Map<String, String> annotations, boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        DeploymentStrategy updateStrategy = new DeploymentStrategyBuilder()
                .withType("RollingUpdate")
                .withRollingUpdate(new RollingUpdateDeploymentBuilder()
                        .withMaxSurge(new IntOrString(1))
                        .withMaxUnavailable(new IntOrString(0))
                        .build())
                .build();

        return createDeployment(
                updateStrategy,
                Collections.emptyMap(),
                annotations,
                getMergedAffinity(),
                getInitContainers(imagePullPolicy),
                getContainers(imagePullPolicy),
                getVolumes(isOpenShift),
                imagePullSecrets);
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {

        List<Container> containers = new ArrayList<>();

        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(getImage())
                .withCommand("/opt/kafka/kafka_connect_run.sh")
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(createHttpProbe(livenessPath, REST_API_PORT_NAME, livenessProbeOptions))
                .withReadinessProbe(createHttpProbe(readinessPath, REST_API_PORT_NAME, readinessProbeOptions))
                .withVolumeMounts(getVolumeMounts())
                .withResources(getResources())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .build();

        containers.add(container);

        return containers;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_CONNECT_CONFIGURATION, configuration.getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_CONNECT_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_CONNECT_BOOTSTRAP_SERVERS, bootstrapServers));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));

        heapOptions(varList, 1.0, 0L);
        jvmPerformanceOptions(varList);

        if (tls != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_CONNECT_TLS, "true"));

            List<CertSecretSource> trustedCertificates = tls.getTrustedCertificates();

            if (trustedCertificates != null && trustedCertificates.size() > 0) {
                StringBuilder sb = new StringBuilder();
                boolean separator = false;
                for (CertSecretSource certSecretSource : trustedCertificates) {
                    if (separator) {
                        sb.append(";");
                    }
                    sb.append(certSecretSource.getSecretName() + "/" + certSecretSource.getCertificate());
                    separator = true;
                }
                varList.add(buildEnvVar(ENV_VAR_KAFKA_CONNECT_TRUSTED_CERTS, sb.toString()));
            }
        }

        AuthenticationUtils.configureClientAuthenticationEnvVars(authentication, varList, name -> ENV_VAR_PREFIX + name);

        if (tracing != null) {
            varList.add(buildEnvVar(ENV_VAR_STRIMZI_TRACING, tracing.getType()));
        }

        varList.addAll(getExternalConfigurationEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateContainerEnvVars);

        return varList;
    }

    private List<EnvVar> getExternalConfigurationEnvVars()   {
        List<EnvVar> varList = new ArrayList<>();

        for (ExternalConfigurationEnv var : externalEnvs)    {
            String name = var.getName();

            if (name != null && !name.startsWith("KAFKA_") && !name.startsWith("STRIMZI_")) {
                ExternalConfigurationEnvVarSource valueFrom = var.getValueFrom();

                if (valueFrom != null)  {
                    if (valueFrom.getConfigMapKeyRef() != null && valueFrom.getSecretKeyRef() != null) {
                        log.warn("Environment variable {} with external Kafka Connect configuration has to contain exactly one reference to either ConfigMap or Secret", name);
                    } else {
                        if (valueFrom.getConfigMapKeyRef() != null) {
                            EnvVarSource envVarSource = new EnvVarSourceBuilder()
                                    .withConfigMapKeyRef(var.getValueFrom().getConfigMapKeyRef())
                                    .build();

                            varList.add(new EnvVarBuilder().withName(name).withValueFrom(envVarSource).build());
                        } else if (valueFrom.getSecretKeyRef() != null)    {
                            EnvVarSource envVarSource = new EnvVarSourceBuilder()
                                    .withSecretKeyRef(var.getValueFrom().getSecretKeyRef())
                                    .build();

                            varList.add(new EnvVarBuilder().withName(name).withValueFrom(envVarSource).build());
                        }
                    }
                }
            } else {
                log.warn("Name of an environment variable with external Kafka Connect configuration cannot start with `KAFKA_` or `STRIMZI`.");
            }
        }

        return varList;
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "kafkaConnectDefaultLoggingProperties";
    }

    /**
     * Set the bootstrap servers to connect to
     * @param bootstrapServers bootstrap servers comma separated list
     */
    protected void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Set the tls configuration with the certificate to trust
     *
     * @param tls trusted certificates list
     */
    protected void setTls(KafkaConnectTls tls) {
        this.tls = tls;
    }

    /**
     * Sets the configured authentication
     *
     * @param authetication Authentication configuration
     */
    protected void setAuthentication(KafkaClientAuthentication authetication) {
        this.authentication = authetication;
    }

    /**
     * Generates the PodDisruptionBudget
     *
     * @return The PodDisruptionBudget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget() {
        return createPodDisruptionBudget();
    }

    @Override
    protected String getServiceAccountName() {
        return KafkaConnectResources.serviceAccountName(cluster);
    }
}
