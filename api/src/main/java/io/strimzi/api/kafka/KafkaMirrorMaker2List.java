/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka;

import io.fabric8.kubernetes.client.CustomResourceList;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2;

/**
 * A {@code CustomResourceList<KafkaMirrorMake2r>} required for using Fabric8 CRD support.
 */
public class KafkaMirrorMaker2List extends CustomResourceList<KafkaMirrorMaker2> {
    private static final long serialVersionUID = 1L;
}
