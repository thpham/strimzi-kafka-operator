/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Lifecycle;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import io.strimzi.api.kafka.model.InlineLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.RackBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverrideBuilder;
import io.strimzi.api.kafka.model.storage.SingleVolumeStorage;
import io.strimzi.api.kafka.model.TlsSidecar;
import io.strimzi.api.kafka.model.TlsSidecarBuilder;
import io.strimzi.api.kafka.model.TlsSidecarLogLevel;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.ContainerTemplate;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.common.PasswordGenerator;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.test.TestUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static io.strimzi.test.TestUtils.set;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("checkstyle:ClassDataAbstractionCoupling")
public class ZookeeperClusterTest {

    private static final KafkaVersion.Lookup VERSIONS = new KafkaVersion.Lookup(
            new StringReader(KafkaVersionTestUtils.getKafkaVersionYaml()),
            KafkaVersionTestUtils.getKafkaImageMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap()) { };
    private final String namespace = "test";
    private final String cluster = "foo";
    private final int replicas = 3;
    private final String image = "image";
    private final int healthDelay = 120;
    private final int healthTimeout = 30;
    private final int tlsHealthDelay = 120;
    private final int tlsHealthTimeout = 30;
    private final Map<String, Object> metricsCmJson = singletonMap("animal", "wombat");
    private final Map<String, Object> configurationJson = emptyMap();
    private final InlineLogging kafkaLogConfigJson = new InlineLogging();
    private final InlineLogging zooLogConfigJson = new InlineLogging();
    {
        kafkaLogConfigJson.setLoggers(Collections.singletonMap("kafka.root.logger.level", "OFF"));
        zooLogConfigJson.setLoggers(Collections.singletonMap("zookeeper.root.logger", "OFF"));
    }

    private final Map<String, Object> zooConfigurationJson = singletonMap("foo", "bar");

    private final TlsSidecar tlsSidecar = new TlsSidecarBuilder()
            .withLivenessProbe(new ProbeBuilder().withInitialDelaySeconds(tlsHealthDelay).withTimeoutSeconds(tlsHealthTimeout).build())
            .withReadinessProbe(new ProbeBuilder().withInitialDelaySeconds(tlsHealthDelay).withTimeoutSeconds(tlsHealthTimeout).build())
            .build();

    private final Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson, null, null, null, kafkaLogConfigJson, zooLogConfigJson, null))
            .editSpec()
                .editZookeeper()
                    .withTlsSidecar(tlsSidecar)
                .endZookeeper()
            .endSpec()
            .build();

    private final ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

    @Test
    public void testMetricsConfigMap() {
        ConfigMap metricsCm = zc.generateMetricsAndLogConfigMap(null);
        checkMetricsConfigMap(metricsCm);
        checkOwnerReference(zc.createOwnerReference(), metricsCm);
    }

    private void checkMetricsConfigMap(ConfigMap metricsCm) {
        assertThat(metricsCm.getData().get(AbstractModel.ANCILLARY_CM_KEY_METRICS), is(TestUtils.toJsonString(metricsCmJson)));
    }

    private Map<String, String> expectedSelectorLabels()    {
        return Labels.fromMap(expectedLabels()).strimziLabels().toMap();
    }

    private Map<String, String> expectedLabels()    {
        return TestUtils.map(Labels.STRIMZI_CLUSTER_LABEL, cluster,
            "my-user-label", "cromulent",
            Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(cluster),
            Labels.STRIMZI_KIND_LABEL, Kafka.RESOURCE_KIND,
            Labels.KUBERNETES_NAME_LABEL, Labels.KUBERNETES_NAME,
            Labels.KUBERNETES_INSTANCE_LABEL, this.cluster,
            Labels.KUBERNETES_MANAGED_BY_LABEL, AbstractModel.STRIMZI_CLUSTER_OPERATOR_NAME);
    }

    @Test
    public void testGenerateService() {
        Service headful = zc.generateService();

        assertThat(headful.getSpec().getType(), is("ClusterIP"));
        assertThat(headful.getSpec().getSelector(), is(expectedSelectorLabels()));
        assertThat(headful.getSpec().getPorts().size(), is(2));

        assertThat(headful.getSpec().getPorts().get(1).getName(), is(ZookeeperCluster.CLIENT_PORT_NAME));
        assertThat(headful.getSpec().getPorts().get(1).getPort(), is(new Integer(ZookeeperCluster.CLIENT_PORT)));
        assertThat(headful.getSpec().getPorts().get(1).getProtocol(), is("TCP"));
        assertThat(headful.getSpec().getPorts().get(0).getName(), is(ZookeeperCluster.METRICS_PORT_NAME));
        assertThat(headful.getSpec().getPorts().get(0).getPort(), is(new Integer(ZookeeperCluster.METRICS_PORT)));
        assertThat(headful.getSpec().getPorts().get(0).getProtocol(), is("TCP"));
        assertThat(headful.getMetadata().getAnnotations(), is(zc.getPrometheusAnnotations()));

        checkOwnerReference(zc.createOwnerReference(), headful);
    }

    @Test
    public void testGenerateServiceWithoutMetrics() {
        Kafka kafka = new KafkaBuilder(ka)
                .editSpec()
                    .editZookeeper()
                        .withMetrics(null)
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafka, VERSIONS);
        Service headful = zc.generateService();

        assertThat(headful.getSpec().getType(), is("ClusterIP"));
        assertThat(headful.getSpec().getSelector(), is(expectedSelectorLabels()));
        assertThat(headful.getSpec().getPorts().size(), is(1));
        assertThat(headful.getSpec().getPorts().get(0).getName(), is(ZookeeperCluster.CLIENT_PORT_NAME));
        assertThat(headful.getSpec().getPorts().get(0).getPort(), is(new Integer(ZookeeperCluster.CLIENT_PORT)));
        assertThat(headful.getSpec().getPorts().get(0).getProtocol(), is("TCP"));

        assertThat(headful.getMetadata().getAnnotations().containsKey("prometheus.io/port"), is(false));
        assertThat(headful.getMetadata().getAnnotations().containsKey("prometheus.io/scrape"), is(false));
        assertThat(headful.getMetadata().getAnnotations().containsKey("prometheus.io/path"), is(false));

        checkOwnerReference(zc.createOwnerReference(), headful);
    }

    @Test
    public void testGenerateHeadlessService() {
        Service headless = zc.generateHeadlessService();
        checkHeadlessService(headless);
        checkOwnerReference(zc.createOwnerReference(), headless);
    }

    private void checkHeadlessService(Service headless) {
        assertThat(headless.getMetadata().getName(), is(ZookeeperCluster.headlessServiceName(cluster)));
        assertThat(headless.getSpec().getType(), is("ClusterIP"));
        assertThat(headless.getSpec().getClusterIP(), is("None"));
        assertThat(headless.getSpec().getSelector(), is(expectedSelectorLabels()));
        assertThat(headless.getSpec().getPorts().size(), is(3));
        assertThat(headless.getSpec().getPorts().get(0).getName(), is(ZookeeperCluster.CLIENT_PORT_NAME));
        assertThat(headless.getSpec().getPorts().get(0).getPort(), is(new Integer(ZookeeperCluster.CLIENT_PORT)));
        assertThat(headless.getSpec().getPorts().get(1).getName(), is(ZookeeperCluster.CLUSTERING_PORT_NAME));
        assertThat(headless.getSpec().getPorts().get(1).getPort(), is(new Integer(ZookeeperCluster.CLUSTERING_PORT)));
        assertThat(headless.getSpec().getPorts().get(2).getName(), is(ZookeeperCluster.LEADER_ELECTION_PORT_NAME));
        assertThat(headless.getSpec().getPorts().get(2).getPort(), is(new Integer(ZookeeperCluster.LEADER_ELECTION_PORT)));
        assertThat(headless.getSpec().getPorts().get(0).getProtocol(), is("TCP"));
    }

    @Test
    public void testGenerateStatefulSet() {
        // We expect a single statefulSet ...
        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        checkStatefulSet(sts);
        checkOwnerReference(zc.createOwnerReference(), sts);
    }

    @Test
    public void testInvalidVersion() {
        assertThrows(InvalidResourceException.class, () -> {
            Kafka ka = new KafkaBuilder(this.ka)
                    .editSpec()
                        .editKafka()
                            .withImage(null)
                            .withVersion("10000.0.0")
                        .endKafka()
                        .editZookeeper()
                            .withImage(null)
                        .endZookeeper()
                    .endSpec()
                    .build();

            ZookeeperCluster.fromCrd(ka, VERSIONS);
        });
    }

    private void checkStatefulSet(StatefulSet sts) {
        assertThat(sts.getMetadata().getName(), is(ZookeeperCluster.zookeeperClusterName(cluster)));
        // ... in the same namespace ...
        assertThat(sts.getMetadata().getNamespace(), is(namespace));
        // ... with these labels
        assertThat(sts.getMetadata().getLabels(), is(expectedLabels()));
        assertThat(sts.getSpec().getSelector().getMatchLabels(), is(expectedSelectorLabels()));

        assertThat(sts.getSpec().getTemplate().getSpec().getSchedulerName(), is("default-scheduler"));

        List<Container> containers = sts.getSpec().getTemplate().getSpec().getContainers();

        assertThat(containers.size(), is(2));

        // checks on the main Zookeeper container
        assertThat(sts.getSpec().getReplicas(), is(new Integer(replicas)));
        assertThat(containers.get(0).getImage(), is(image + "-zk"));
        assertThat(containers.get(0).getLivenessProbe().getTimeoutSeconds(), is(new Integer(healthTimeout)));
        assertThat(containers.get(0).getLivenessProbe().getInitialDelaySeconds(), is(new Integer(healthDelay)));
        assertThat(containers.get(0).getLivenessProbe().getFailureThreshold(), is(new Integer(10)));
        assertThat(containers.get(0).getLivenessProbe().getSuccessThreshold(), is(new Integer(4)));
        assertThat(containers.get(0).getLivenessProbe().getPeriodSeconds(), is(new Integer(33)));
        assertThat(containers.get(0).getReadinessProbe().getTimeoutSeconds(), is(new Integer(healthTimeout)));
        assertThat(containers.get(0).getReadinessProbe().getInitialDelaySeconds(), is(new Integer(healthDelay)));
        assertThat(containers.get(0).getReadinessProbe().getFailureThreshold(), is(new Integer(10)));
        assertThat(containers.get(0).getReadinessProbe().getSuccessThreshold(), is(new Integer(4)));
        assertThat(containers.get(0).getReadinessProbe().getPeriodSeconds(), is(new Integer(33)));
        OrderedProperties expectedConfig = new OrderedProperties()
                .addPair("autopurge.purgeInterval", "1")
                .addPair("tickTime", "2000")
                .addPair("syncLimit", "2")
                .addPair("initLimit", "5")
                .addPair("foo", "bar");
        OrderedProperties actual = new OrderedProperties()
                .addStringPairs(AbstractModel.containerEnvVars(containers.get(0)).get(ZookeeperCluster.ENV_VAR_ZOOKEEPER_CONFIGURATION));
        assertThat(actual, is(expectedConfig));
        assertThat(AbstractModel.containerEnvVars(containers.get(0)).get(ZookeeperCluster.ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED), is(Boolean.toString(AbstractModel.DEFAULT_JVM_GC_LOGGING_ENABLED)));
        // checks on the TLS sidecar container
        Container tlsSidecarContainer = containers.get(1);
        assertThat(tlsSidecarContainer.getImage(), is(image));
        assertThat(Integer.valueOf(AbstractModel.containerEnvVars(tlsSidecarContainer).get(ZookeeperCluster.ENV_VAR_ZOOKEEPER_NODE_COUNT)), is(new Integer(replicas)));
        assertThat(AbstractModel.containerEnvVars(tlsSidecarContainer).get(ModelUtils.TLS_SIDECAR_LOG_LEVEL), is(TlsSidecarLogLevel.NOTICE.toValue()));
        assertThat(tlsSidecarContainer.getPorts().get(0).getName(), is(ZookeeperCluster.CLUSTERING_PORT_NAME));
        assertThat(tlsSidecarContainer.getPorts().get(0).getContainerPort(), is(new Integer(ZookeeperCluster.CLUSTERING_PORT)));
        assertThat(tlsSidecarContainer.getPorts().get(1).getName(), is(ZookeeperCluster.LEADER_ELECTION_PORT_NAME));
        assertThat(tlsSidecarContainer.getPorts().get(1).getContainerPort(), is(new Integer(ZookeeperCluster.LEADER_ELECTION_PORT)));
        assertThat(tlsSidecarContainer.getPorts().get(2).getName(), is(ZookeeperCluster.CLIENT_PORT_NAME));
        assertThat(tlsSidecarContainer.getPorts().get(2).getContainerPort(), is(new Integer(ZookeeperCluster.CLIENT_PORT)));
        assertThat(tlsSidecarContainer.getVolumeMounts().get(0).getName(), is(ZookeeperCluster.TLS_SIDECAR_NODES_VOLUME_NAME));
        assertThat(tlsSidecarContainer.getVolumeMounts().get(0).getMountPath(), is(ZookeeperCluster.TLS_SIDECAR_NODES_VOLUME_MOUNT));
        assertThat(tlsSidecarContainer.getVolumeMounts().get(1).getName(), is(ZookeeperCluster.TLS_SIDECAR_CLUSTER_CA_VOLUME_NAME));
        assertThat(tlsSidecarContainer.getVolumeMounts().get(1).getMountPath(), is(ZookeeperCluster.TLS_SIDECAR_CLUSTER_CA_VOLUME_MOUNT));
        assertThat(tlsSidecarContainer.getReadinessProbe().getInitialDelaySeconds(), is(new Integer(tlsHealthDelay)));
        assertThat(tlsSidecarContainer.getReadinessProbe().getTimeoutSeconds(), is(new Integer(tlsHealthTimeout)));
        assertThat(tlsSidecarContainer.getLivenessProbe().getInitialDelaySeconds(), is(new Integer(tlsHealthDelay)));
        assertThat(tlsSidecarContainer.getLivenessProbe().getTimeoutSeconds(), is(new Integer(tlsHealthTimeout)));
    }

    // TODO test volume claim templates

    @Test
    public void testPodNames() {

        for (int i = 0; i < replicas; i++) {
            assertThat(zc.getPodName(i), is(ZookeeperCluster.zookeeperPodName(cluster, i)));
        }
    }

    @Test
    public void testPvcNames() {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editZookeeper()
                        .withNewPersistentClaimStorage().withDeleteClaim(false).withSize("100Gi").endPersistentClaimStorage()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        PersistentVolumeClaim pvc = zc.getVolumeClaims().get(0);

        for (int i = 0; i < replicas; i++) {
            assertThat(pvc.getMetadata().getName() + "-" + ZookeeperCluster.zookeeperPodName(cluster, i),
                    is(zc.VOLUME_NAME + "-" + ZookeeperCluster.zookeeperPodName(cluster, i)));
        }
    }

    @Test
    public void withOldAffinity() throws IOException {
        ResourceTester<Kafka, ZookeeperCluster> resourceTester = new ResourceTester<>(Kafka.class, VERSIONS, ZookeeperCluster::fromCrd, this.getClass().getSimpleName() + ".withOldAffinity");
        resourceTester.assertDesiredResource("-STS.yaml", zc -> zc.generateStatefulSet(true, null, null).getSpec().getTemplate().getSpec().getAffinity());
    }

    @Test
    public void withAffinity() throws IOException {
        ResourceTester<Kafka, ZookeeperCluster> resourceTester = new ResourceTester<>(Kafka.class, VERSIONS, ZookeeperCluster::fromCrd, this.getClass().getSimpleName() + ".withAffinity");
        resourceTester.assertDesiredResource("-STS.yaml", zc -> zc.generateStatefulSet(true, null, null).getSpec().getTemplate().getSpec().getAffinity());
    }

    public void checkOwnerReference(OwnerReference ownerRef, HasMetadata resource)  {
        assertThat(resource.getMetadata().getOwnerReferences().size(), is(1));
        assertThat(resource.getMetadata().getOwnerReferences().get(0), is(ownerRef));
    }

    @Test
    public void testGenerateBrokerSecret() throws CertificateParsingException {
        ClusterCa clusterCa = new ClusterCa(new OpenSslCertManager(), new PasswordGenerator(10, "a", "a"), cluster, null, null);
        clusterCa.createRenewOrReplace(namespace, cluster, emptyMap(), null, true);

        Secret secret = zc.generateNodesSecret(clusterCa, ka);
        assertThat(secret.getData().keySet(), is(set(
                "foo-zookeeper-0.crt",  "foo-zookeeper-0.key", "foo-zookeeper-0.p12", "foo-zookeeper-0.password",
                "foo-zookeeper-1.crt", "foo-zookeeper-1.key", "foo-zookeeper-1.p12", "foo-zookeeper-1.password",
                "foo-zookeeper-2.crt", "foo-zookeeper-2.key", "foo-zookeeper-2.p12", "foo-zookeeper-2.password")));
        X509Certificate cert = Ca.cert(secret, "foo-zookeeper-0.crt");
        assertThat(cert.getSubjectDN().getName(), is("CN=foo-zookeeper, O=io.strimzi"));
        assertThat(new HashSet<Object>(cert.getSubjectAlternativeNames()), is(set(
                asList(2, "foo-zookeeper-0.foo-zookeeper-nodes.test.svc.cluster.local"),
                asList(2, "foo-zookeeper-client"),
                asList(2, "foo-zookeeper-client.test"),
                asList(2, "foo-zookeeper-client.test.svc"),
                asList(2, "foo-zookeeper-client.test.svc.cluster.local"))));

    }

    @Test
    public void testTemplate() {
        Map<String, String> ssLabels = TestUtils.map("l1", "v1", "l2", "v2");
        Map<String, String> ssAnots = TestUtils.map("a1", "v1", "a2", "v2");

        Map<String, String> podLabels = TestUtils.map("l3", "v3", "l4", "v4");
        Map<String, String> podAnots = TestUtils.map("a3", "v3", "a4", "v4");

        Map<String, String> svcLabels = TestUtils.map("l5", "v5", "l6", "v6");
        Map<String, String> svcAnots = TestUtils.map("a5", "v5", "a6", "v6");

        Map<String, String> hSvcLabels = TestUtils.map("l7", "v7", "l8", "v8");
        Map<String, String> hSvcAnots = TestUtils.map("a7", "v7", "a8", "v8");

        Map<String, String> pdbLabels = TestUtils.map("l9", "v9", "l10", "v10");
        Map<String, String> pdbAnots = TestUtils.map("a9", "v9", "a10", "v10");

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewStatefulset()
                                .withNewMetadata()
                                    .withLabels(ssLabels)
                                    .withAnnotations(ssAnots)
                                .endMetadata()
                            .endStatefulset()
                            .withNewPod()
                                .withNewMetadata()
                                    .withLabels(podLabels)
                                    .withAnnotations(podAnots)
                                .endMetadata()
                                .withNewPriorityClassName("top-priority")
                                .withNewSchedulerName("my-scheduler")
                            .endPod()
                            .withNewClientService()
                                .withNewMetadata()
                                    .withLabels(svcLabels)
                                    .withAnnotations(svcAnots)
                                .endMetadata()
                            .endClientService()
                            .withNewNodesService()
                                .withNewMetadata()
                                    .withLabels(hSvcLabels)
                                    .withAnnotations(hSvcAnots)
                                .endMetadata()
                            .endNodesService()
                            .withNewPodDisruptionBudget()
                                .withNewMetadata()
                                    .withLabels(pdbLabels)
                                    .withAnnotations(pdbAnots)
                                .endMetadata()
                            .endPodDisruptionBudget()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        // Check StatefulSet
        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getMetadata().getLabels().entrySet().containsAll(ssLabels.entrySet()), is(true));
        assertThat(sts.getMetadata().getAnnotations().entrySet().containsAll(ssAnots.entrySet()), is(true));
        assertThat(sts.getSpec().getTemplate().getSpec().getPriorityClassName(), is("top-priority"));

        // Check Pods
        assertThat(sts.getSpec().getTemplate().getMetadata().getLabels().entrySet().containsAll(podLabels.entrySet()), is(true));
        assertThat(sts.getSpec().getTemplate().getMetadata().getAnnotations().entrySet().containsAll(podAnots.entrySet()), is(true));
        assertThat(sts.getSpec().getTemplate().getSpec().getSchedulerName(), is("my-scheduler"));

        // Check Service
        Service svc = zc.generateService();
        assertThat(svc.getMetadata().getLabels().entrySet().containsAll(svcLabels.entrySet()), is(true));
        assertThat(svc.getMetadata().getAnnotations().entrySet().containsAll(svcAnots.entrySet()), is(true));

        // Check Headless Service
        svc = zc.generateHeadlessService();
        assertThat(svc.getMetadata().getLabels().entrySet().containsAll(hSvcLabels.entrySet()), is(true));
        assertThat(svc.getMetadata().getAnnotations().entrySet().containsAll(hSvcAnots.entrySet()), is(true));

        // Check PodDisruptionBudget
        PodDisruptionBudget pdb = zc.generatePodDisruptionBudget();
        assertThat(pdb.getMetadata().getLabels().entrySet().containsAll(pdbLabels.entrySet()), is(true));
        assertThat(pdb.getMetadata().getAnnotations().entrySet().containsAll(pdbAnots.entrySet()), is(true));
    }

    @Test
    public void testGracePeriod() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewPod()
                                .withTerminationGracePeriodSeconds(123)
                            .endPod()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getTerminationGracePeriodSeconds(), is(Long.valueOf(123)));
        Lifecycle lifecycle = sts.getSpec().getTemplate().getSpec().getContainers().get(1).getLifecycle();
        assertThat(lifecycle, is(notNullValue()));
        assertThat(lifecycle.getPreStop().getExec().getCommand().contains("/opt/stunnel/zookeeper_stunnel_pre_stop.sh"), is(true));
    }

    @Test
    public void testDefaultGracePeriod() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getTerminationGracePeriodSeconds(), is(Long.valueOf(30)));
        Lifecycle lifecycle = sts.getSpec().getTemplate().getSpec().getContainers().get(1).getLifecycle();
        assertThat(lifecycle, is(notNullValue()));
        assertThat(lifecycle.getPreStop().getExec().getCommand().contains("/opt/stunnel/zookeeper_stunnel_pre_stop.sh"), is(true));
    }

    @Test
    public void testImagePullSecrets() {
        LocalObjectReference secret1 = new LocalObjectReference("some-pull-secret");
        LocalObjectReference secret2 = new LocalObjectReference("some-other-pull-secret");

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewPod()
                                .withImagePullSecrets(secret1, secret2)
                            .endPod()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().size(), is(2));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().contains(secret1), is(true));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().contains(secret2), is(true));
    }

    @Test
    public void testImagePullSecretsFromCO() {
        LocalObjectReference secret1 = new LocalObjectReference("some-pull-secret");
        LocalObjectReference secret2 = new LocalObjectReference("some-other-pull-secret");

        List<LocalObjectReference> secrets = new ArrayList<>(2);
        secrets.add(secret1);
        secrets.add(secret2);

        Kafka kafkaAssembly = ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap());
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, secrets);
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().size(), is(2));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().contains(secret1), is(true));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().contains(secret2), is(true));
    }

    @Test
    public void testImagePullSecretsFromBoth() {
        LocalObjectReference secret1 = new LocalObjectReference("some-pull-secret");
        LocalObjectReference secret2 = new LocalObjectReference("some-other-pull-secret");

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewPod()
                                .withImagePullSecrets(secret2)
                            .endPod()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, singletonList(secret1));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().size(), is(1));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().contains(secret1), is(false));
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().contains(secret2), is(true));
    }

    @Test
    public void testDefaultImagePullSecrets() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getImagePullSecrets().size(), is(0));
    }

    /**
     * Verify the lookup order is:<ul>
     * <li>Kafka.spec.zookeeper.tlsSidecar.image</li>
     * <li>Kafka.spec.kafka.image</li>
     * <li>image for default version of Kafka</li></ul>
     */
    @Test
    public void testStunnelImage() {
        Kafka resource = ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap());

        Kafka kafka = new KafkaBuilder(resource)
                .editSpec()
                    .editZookeeper()
                        .editOrNewTlsSidecar()
                            .withImage("foo1")
                        .endTlsSidecar()
                    .endZookeeper()
                    .editKafka()
                        .withImage("foo2")
                    .endKafka()
                .endSpec()
            .build();
        assertThat(ZookeeperCluster.fromCrd(kafka, VERSIONS).getContainers(ImagePullPolicy.ALWAYS).get(1).getImage(), is("foo1"));

        kafka = new KafkaBuilder(resource)
                .editSpec()
                    .editZookeeper()
                        .withImage("bar")
                        .editOrNewTlsSidecar()
                            .withImage(null)
                        .endTlsSidecar()
                    .endZookeeper()
                    .editKafka()
                        .withImage("foo2")
                    .endKafka()
                .endSpec()
            .build();
        assertThat(ZookeeperCluster.fromCrd(kafka, VERSIONS).getContainers(ImagePullPolicy.ALWAYS).get(1).getImage(), is("foo2"));

        kafka = new KafkaBuilder(resource)
                .editSpec()
                    .editZookeeper()
                        .withImage("bar")
                        .editOrNewTlsSidecar()
                            .withImage(null)
                        .endTlsSidecar()
                    .endZookeeper()
                    .editKafka()
                        .withVersion(KafkaVersionTestUtils.PREVIOUS_KAFKA_VERSION)
                        .withImage(null)
                    .endKafka()
                .endSpec()
            .build();
        assertThat(ZookeeperCluster.fromCrd(kafka, VERSIONS).getContainers(ImagePullPolicy.ALWAYS).get(1).getImage(), is(KafkaVersionTestUtils.DEFAULT_KAFKA_IMAGE));

        kafka = new KafkaBuilder(resource)
                .editSpec()
                    .editZookeeper()
                        .withImage("bar")
                        .editOrNewTlsSidecar()
                            .withImage(null)
                        .endTlsSidecar()
                    .endZookeeper()
                    .editKafka()
                        .withVersion(KafkaVersionTestUtils.LATEST_KAFKA_VERSION)
                        .withImage(null)
                    .endKafka()
                .endSpec()
            .build();
        assertThat(ZookeeperCluster.fromCrd(kafka, VERSIONS).getContainers(ImagePullPolicy.ALWAYS).get(1).getImage(), is(KafkaVersionTestUtils.DEFAULT_KAFKA_IMAGE));
    }

    @Test
    public void testSecurityContext() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewPod()
                                .withSecurityContext(new PodSecurityContextBuilder().withFsGroup(123L).withRunAsGroup(456L).withRunAsUser(789L).build())
                            .endPod()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getSecurityContext(), is(notNullValue()));
        assertThat(sts.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup(), is(Long.valueOf(123)));
        assertThat(sts.getSpec().getTemplate().getSpec().getSecurityContext().getRunAsGroup(), is(Long.valueOf(456)));
        assertThat(sts.getSpec().getTemplate().getSpec().getSecurityContext().getRunAsUser(), is(Long.valueOf(789)));
    }

    @Test
    public void testDefaultSecurityContext() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getSecurityContext(), is(nullValue()));
    }

    @Test
    public void testPodDisruptionBudget() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewPodDisruptionBudget()
                                .withMaxUnavailable(2)
                            .endPodDisruptionBudget()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        PodDisruptionBudget pdb = zc.generatePodDisruptionBudget();
        assertThat(pdb.getSpec().getMaxUnavailable(), is(new IntOrString(2)));
    }

    @Test
    public void testDefaultPodDisruptionBudget() {
        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        PodDisruptionBudget pdb = zc.generatePodDisruptionBudget();
        assertThat(pdb.getSpec().getMaxUnavailable(), is(new IntOrString(1)));
    }

    @Test
    public void testImagePullPolicy() {
        Kafka kafkaAssembly = ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap());
        kafkaAssembly.getSpec().getKafka().setRack(new RackBuilder().withTopologyKey("topology-key").build());
        ZookeeperCluster kc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(true, ImagePullPolicy.ALWAYS, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getContainers().get(0).getImagePullPolicy(), is(ImagePullPolicy.ALWAYS.toString()));
        assertThat(sts.getSpec().getTemplate().getSpec().getContainers().get(1).getImagePullPolicy(), is(ImagePullPolicy.ALWAYS.toString()));

        sts = zc.generateStatefulSet(true, ImagePullPolicy.IFNOTPRESENT, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getContainers().get(0).getImagePullPolicy(), is(ImagePullPolicy.IFNOTPRESENT.toString()));
        assertThat(sts.getSpec().getTemplate().getSpec().getContainers().get(1).getImagePullPolicy(), is(ImagePullPolicy.IFNOTPRESENT.toString()));
    }

    @Test
    public void testNetworkPolicyOldKubernetesVersions() {
        Kafka kafkaAssembly = ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap());
        kafkaAssembly.getSpec().getKafka().setRack(new RackBuilder().withTopologyKey("topology-key").build());
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        NetworkPolicy np = zc.generateNetworkPolicy(false);

        LabelSelector podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(zc.getCluster())));
        assertThat(np.getSpec().getPodSelector(), is(podSelector));

        List<NetworkPolicyIngressRule> rules = np.getSpec().getIngress();
        assertThat(rules.size(), is(3));

        // Ports 2888 and 3888
        NetworkPolicyIngressRule zooRule = rules.get(0);
        assertThat(zooRule.getPorts().size(), is(2));
        assertThat(zooRule.getPorts().get(0).getPort(), is(new IntOrString(2888)));
        assertThat(zooRule.getPorts().get(1).getPort(), is(new IntOrString(3888)));

        assertThat(zooRule.getFrom().size(), is(1));
        podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(zc.getCluster())));
        assertThat(zooRule.getFrom().get(0), is(new NetworkPolicyPeerBuilder().withPodSelector(podSelector).build()));

        // Port 2181
        NetworkPolicyIngressRule clientsRule = rules.get(1);
        assertThat(clientsRule.getPorts().size(), is(1));
        assertThat(clientsRule.getPorts().get(0).getPort(), is(new IntOrString(2181)));
        assertThat(clientsRule.getFrom().size(), is(0));

        // Port 9404
        NetworkPolicyIngressRule metricsRule = rules.get(2);
        assertThat(metricsRule.getPorts().size(), is(1));
        assertThat(metricsRule.getPorts().get(0).getPort(), is(new IntOrString(9404)));
        assertThat(metricsRule.getFrom().size(), is(0));
    }

    @Test
    public void testNetworkPolicyNewKubernetesVersions() {
        Kafka kafkaAssembly = ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap());
        kafkaAssembly.getSpec().getKafka().setRack(new RackBuilder().withTopologyKey("topology-key").build());
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        // Check Network Policies
        NetworkPolicy np = zc.generateNetworkPolicy(true);

        LabelSelector podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(zc.getCluster())));
        assertThat(np.getSpec().getPodSelector(), is(podSelector));

        List<NetworkPolicyIngressRule> rules = np.getSpec().getIngress();
        assertThat(rules.size(), is(3));

        // Ports 2888 and 3888
        NetworkPolicyIngressRule zooRule = rules.get(0);
        assertThat(zooRule.getPorts().size(), is(2));
        assertThat(zooRule.getPorts().get(0).getPort(), is(new IntOrString(2888)));
        assertThat(zooRule.getPorts().get(1).getPort(), is(new IntOrString(3888)));

        assertThat(zooRule.getFrom().size(), is(1));
        podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(zc.getCluster())));
        assertThat(zooRule.getFrom().get(0), is(new NetworkPolicyPeerBuilder().withPodSelector(podSelector).build()));

        // Port 2181
        NetworkPolicyIngressRule clientsRule = rules.get(1);
        assertThat(clientsRule.getPorts().size(), is(1));
        assertThat(clientsRule.getPorts().get(0).getPort(), is(new IntOrString(2181)));

        assertThat(clientsRule.getFrom().size(), is(4));

        podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, KafkaCluster.kafkaClusterName(zc.getCluster())));
        assertThat(clientsRule.getFrom().get(0), is(new NetworkPolicyPeerBuilder().withPodSelector(podSelector).build()));

        podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(zc.getCluster())));
        assertThat(clientsRule.getFrom().get(1), is(new NetworkPolicyPeerBuilder().withPodSelector(podSelector).build()));

        podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_NAME_LABEL, EntityOperator.entityOperatorName(zc.getCluster())));
        assertThat(clientsRule.getFrom().get(2), is(new NetworkPolicyPeerBuilder().withPodSelector(podSelector).build()));

        podSelector = new LabelSelector();
        podSelector.setMatchLabels(Collections.singletonMap(Labels.STRIMZI_KIND_LABEL, "cluster-operator"));
        assertThat(clientsRule.getFrom().get(3), is(new NetworkPolicyPeerBuilder().withPodSelector(podSelector).withNamespaceSelector(new LabelSelector()).build()));

        // Port 9404
        NetworkPolicyIngressRule metricsRule = rules.get(2);
        assertThat(metricsRule.getPorts().size(), is(1));
        assertThat(metricsRule.getPorts().get(0).getPort(), is(new IntOrString(9404)));
        assertThat(metricsRule.getFrom().size(), is(0));
    }

    @Test
    public void testNetworkPolicyWithoutNamespaceAndPodSelectors() {

    }

    @Test
    public void testGeneratePersistentVolumeClaimsParsistentWithClaimDeletion() {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                .editZookeeper()
                .withNewPersistentClaimStorage().withStorageClass("gp2-ssd").withDeleteClaim(true).withSize("100Gi").endPersistentClaimStorage()
                .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        // Check Storage annotation on STS
        assertThat(zc.generateStatefulSet(true, ImagePullPolicy.NEVER, null).getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_STORAGE),
                is(ModelUtils.encodeStorageToJson(ka.getSpec().getZookeeper().getStorage())));

        // Check PVCs
        List<PersistentVolumeClaim> pvcs = zc.generatePersistentVolumeClaims();

        assertThat(pvcs.size(), is(3));

        for (PersistentVolumeClaim pvc : pvcs) {
            assertThat(pvc.getSpec().getResources().getRequests().get("storage"), is(new Quantity("100Gi")));
            assertThat(pvc.getSpec().getStorageClassName(), is("gp2-ssd"));
            assertThat(pvc.getMetadata().getName().startsWith(zc.VOLUME_NAME), is(true));
            assertThat(pvc.getMetadata().getOwnerReferences().size(), is(1));
            assertThat(pvc.getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_DELETE_CLAIM), is("true"));
        }
    }

    @Test
    public void testGeneratePersistentVolumeClaimsPersistentWithoutClaimDeletion() {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                .editZookeeper()
                .withNewPersistentClaimStorage().withStorageClass("gp2-ssd").withDeleteClaim(false).withSize("100Gi").endPersistentClaimStorage()
                .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        // Check Storage annotation on STS
        assertThat(zc.generateStatefulSet(true, ImagePullPolicy.NEVER, null).getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_STORAGE),
                is(ModelUtils.encodeStorageToJson(ka.getSpec().getZookeeper().getStorage())));

        // Check PVCs
        List<PersistentVolumeClaim> pvcs = zc.generatePersistentVolumeClaims();

        assertThat(pvcs.size(), is(3));

        for (PersistentVolumeClaim pvc : pvcs) {
            assertThat(pvc.getSpec().getResources().getRequests().get("storage"), is(new Quantity("100Gi")));
            assertThat(pvc.getSpec().getStorageClassName(), is("gp2-ssd"));
            assertThat(pvc.getMetadata().getName().startsWith(zc.VOLUME_NAME), is(true));
            assertThat(pvc.getMetadata().getOwnerReferences().size(), is(0));
            assertThat(pvc.getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_DELETE_CLAIM), is("false"));
        }
    }

    @Test
    public void testGeneratePersistentVolumeClaimsPersistentWithOverride() {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                .editZookeeper()
                .withNewPersistentClaimStorage()
                    .withStorageClass("gp2-ssd")
                    .withDeleteClaim(false)
                    .withSize("100Gi")
                    .withOverrides(new PersistentClaimStorageOverrideBuilder()
                            .withBroker(1)
                            .withStorageClass("gp2-ssd-az1")
                            .build())
                .endPersistentClaimStorage()
                .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        // Check Storage annotation on STS
        assertThat(zc.generateStatefulSet(true, ImagePullPolicy.NEVER, null).getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_STORAGE),
                is(ModelUtils.encodeStorageToJson(ka.getSpec().getZookeeper().getStorage())));

        // Check PVCs
        List<PersistentVolumeClaim> pvcs = zc.generatePersistentVolumeClaims();

        assertThat(pvcs.size(), is(3));

        for (int i = 0; i < 3; i++) {
            PersistentVolumeClaim pvc = pvcs.get(i);

            assertThat(pvc.getSpec().getResources().getRequests().get("storage"), is(new Quantity("100Gi")));

            if (i != 1) {
                assertThat(pvc.getSpec().getStorageClassName(), is("gp2-ssd"));
            } else {
                assertThat(pvc.getSpec().getStorageClassName(), is("gp2-ssd-az1"));
            }

            assertThat(pvc.getMetadata().getName().startsWith(zc.VOLUME_NAME), is(true));
            assertThat(pvc.getMetadata().getOwnerReferences().size(), is(0));
            assertThat(pvc.getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_DELETE_CLAIM), is("false"));
        }
    }

    @Test
    public void testGeneratePersistentVolumeClaimsWithTemplate() {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewPersistentVolumeClaim()
                                .withNewMetadata()
                                    .withLabels(singletonMap("testLabel", "testValue"))
                                    .withAnnotations(singletonMap("testAnno", "testValue"))
                                .endMetadata()
                            .endPersistentVolumeClaim()
                        .endTemplate()
                        .withStorage(new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd")
                                        .withDeleteClaim(false)
                                        .withId(0)
                                        .withSize("100Gi")
                                        .withOverrides(new PersistentClaimStorageOverrideBuilder().withBroker(1).withStorageClass("gp2-ssd-az1").build())
                                        .build())
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        // Check PVCs
        List<PersistentVolumeClaim> pvcs = zc.generatePersistentVolumeClaims();

        assertThat(pvcs.size(), is(3));

        for (int i = 0; i < 3; i++) {
            PersistentVolumeClaim pvc = pvcs.get(i);
            assertThat(pvc.getMetadata().getLabels().get("testLabel"), is("testValue"));
            assertThat(pvc.getMetadata().getAnnotations().get("testAnno"), is("testValue"));
        }
    }

    @Test
    public void testGeneratePersistentVolumeClaimsEphemeral()    {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                .editZookeeper()
                .withNewEphemeralStorage().endEphemeralStorage()
                .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        // Check Storage annotation on STS
        assertThat(zc.generateStatefulSet(true, ImagePullPolicy.NEVER, null).getMetadata().getAnnotations().get(AbstractModel.ANNO_STRIMZI_IO_STORAGE),
                is(ModelUtils.encodeStorageToJson(ka.getSpec().getZookeeper().getStorage())));

        // Check PVCs
        List<PersistentVolumeClaim> pvcs = zc.generatePersistentVolumeClaims();

        assertThat(pvcs.size(), is(0));
    }

    @Test
    public void testGenerateSTSWithPersistentVolumeEphemeral()    {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editZookeeper()
                        .withNewEphemeralStorage().endEphemeralStorage()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(false, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getVolumes().get(0).getEmptyDir().getSizeLimit(), is(nullValue()));
    }

    @Test
    public void testGenerateSTSWithPersistentVolumeEphemeralWithSizeLimit()    {
        String sizeLimit = "1Gi";
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editZookeeper()
                        .withNewEphemeralStorage().withNewSizeLimit(sizeLimit).endEphemeralStorage()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

        StatefulSet sts = zc.generateStatefulSet(false, null, null);
        assertThat(sts.getSpec().getTemplate().getSpec().getVolumes().get(0).getEmptyDir().getSizeLimit().getAmount(), is(sizeLimit));
    }

    @Test
    public void testStorageReverting() {
        SingleVolumeStorage ephemeral = new EphemeralStorageBuilder().build();
        SingleVolumeStorage persistent = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build();

        // Test Storage changes and how the are reverted

        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                .editZookeeper()
                .withStorage(ephemeral)
                .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS, persistent);
        assertThat(zc.getStorage(), is(persistent));

        ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                .editZookeeper()
                .withStorage(persistent)
                .endZookeeper()
                .endSpec()
                .build();
        zc = ZookeeperCluster.fromCrd(ka, VERSIONS, ephemeral);
        assertThat(zc.getStorage(), is(ephemeral));
    }

    @Test
    public void testStorageValidationAfterInitialDeployment() {
        assertThrows(InvalidResourceException.class, () -> {
            Storage oldStorage = new PersistentClaimStorageBuilder()
                    .withSize("100Gi")
                    .build();

            Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image,
                    healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                    .editSpec()
                    .editZookeeper()
                        .withStorage(new PersistentClaimStorageBuilder().build())
                        .endZookeeper()
                    .endSpec()
                    .build();
            ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS, oldStorage);
        });
    }

    @Test
    public void testZookeeperContainerEnvVars() {

        ContainerEnvVar envVar1 = new ContainerEnvVar();
        String testEnvOneKey = "TEST_ENV_1";
        String testEnvOneValue = "test.env.one";
        envVar1.setName(testEnvOneKey);
        envVar1.setValue(testEnvOneValue);

        ContainerEnvVar envVar2 = new ContainerEnvVar();
        String testEnvTwoKey = "TEST_ENV_2";
        String testEnvTwoValue = "test.env.two";
        envVar2.setName(testEnvTwoKey);
        envVar2.setValue(testEnvTwoValue);

        List<ContainerEnvVar> testEnvs = new ArrayList<>();
        testEnvs.add(envVar1);
        testEnvs.add(envVar2);
        ContainerTemplate zookeeperContainer = new ContainerTemplate();
        zookeeperContainer.setEnv(testEnvs);

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                .editZookeeper()
                .withNewTemplate()
                .withZookeeperContainer(zookeeperContainer)
                .endTemplate()
                .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        List<EnvVar> zkEnvVars = zc.getEnvVars();

        assertThat("Failed to correctly set container environment variable: " + testEnvOneKey,
                zkEnvVars.stream().filter(env -> testEnvOneKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvOneValue), is(true));
        assertThat("Failed to correctly set container environment variable: " + testEnvTwoKey,
                zkEnvVars.stream().filter(env -> testEnvTwoKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvTwoValue), is(true));

    }

    @Test
    public void testZookeeperContainerEnvVarsConflict() {
        ContainerEnvVar envVar1 = new ContainerEnvVar();
        String testEnvOneKey = ZookeeperCluster.ENV_VAR_ZOOKEEPER_NODE_COUNT;
        String testEnvOneValue = "test.env.one";
        envVar1.setName(testEnvOneKey);
        envVar1.setValue(testEnvOneValue);

        ContainerEnvVar envVar2 = new ContainerEnvVar();
        String testEnvTwoKey = ZookeeperCluster.ENV_VAR_ZOOKEEPER_METRICS_ENABLED;
        String testEnvTwoValue = "test.env.two";
        envVar2.setName(testEnvTwoKey);
        envVar2.setValue(testEnvTwoValue);

        List<ContainerEnvVar> testEnvs = new ArrayList<>();
        testEnvs.add(envVar1);
        testEnvs.add(envVar2);
        ContainerTemplate zookeeperContainer = new ContainerTemplate();
        zookeeperContainer.setEnv(testEnvs);

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                .editZookeeper()
                .withNewTemplate()
                .withZookeeperContainer(zookeeperContainer)
                .endTemplate()
                .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        List<EnvVar> zkEnvVars = zc.getEnvVars();

        assertThat("Failed to prevent over writing existing container environment variable: " + testEnvOneKey,
                zkEnvVars.stream().filter(env -> testEnvOneKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvOneValue), is(false));
        assertThat("Failed to prevent over writing existing container environment variable: " + testEnvTwoKey,
                zkEnvVars.stream().filter(env -> testEnvTwoKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvTwoValue), is(false));

    }

    @Test
    public void testTlsSideCarContainerEnvVars() {

        ContainerEnvVar envVar1 = new ContainerEnvVar();
        String testEnvOneKey = "TEST_ENV_1";
        String testEnvOneValue = "test.env.one";
        envVar1.setName(testEnvOneKey);
        envVar1.setValue(testEnvOneValue);

        ContainerEnvVar envVar2 = new ContainerEnvVar();
        String testEnvTwoKey = "TEST_ENV_2";
        String testEnvTwoValue = "test.env.two";
        envVar2.setName(testEnvTwoKey);
        envVar2.setValue(testEnvTwoValue);

        List<ContainerEnvVar> testEnvs = new ArrayList<>();
        testEnvs.add(envVar1);
        testEnvs.add(envVar2);
        ContainerTemplate tlsContainer = new ContainerTemplate();
        tlsContainer.setEnv(testEnvs);

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                .editZookeeper()
                .withNewTemplate()
                .withTlsSidecarContainer(tlsContainer)
                .endTemplate()
                .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        List<EnvVar> zkEnvVars = zc.getTlsSidevarEnvVars();

        assertThat("Failed to correctly set container environment variable: " + testEnvOneKey,
                zkEnvVars.stream().filter(env -> testEnvOneKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvOneValue), is(true));
        assertThat("Failed to correctly set container environment variable: " + testEnvTwoKey,
                zkEnvVars.stream().filter(env -> testEnvTwoKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvTwoValue), is(true));

    }

    @Test
    public void testTlsSidecarContainerEnvVarsConflict() {

        ContainerEnvVar envVar1 = new ContainerEnvVar();
        String testEnvOneKey = ZookeeperCluster.ENV_VAR_ZOOKEEPER_NODE_COUNT;
        String testEnvOneValue = "test.env.one";
        envVar1.setName(testEnvOneKey);
        envVar1.setValue(testEnvOneValue);


        List<ContainerEnvVar> testEnvs = new ArrayList<>();
        testEnvs.add(envVar1);
        ContainerTemplate tlsContainer = new ContainerTemplate();
        tlsContainer.setEnv(testEnvs);

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                .editZookeeper()
                .withNewTemplate()
                .withTlsSidecarContainer(tlsContainer)
                .endTemplate()
                .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        List<EnvVar> zkEnvVars = zc.getTlsSidevarEnvVars();

        assertThat("Failed to prevent over writing existing container environment variable: " + testEnvOneKey,
                zkEnvVars.stream().filter(env -> testEnvOneKey.equals(env.getName()))
                        .map(EnvVar::getValue).findFirst().orElse("").equals(testEnvOneValue), is(false));
    }
}
