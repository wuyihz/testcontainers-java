package org.testcontainers.containers.paas.common;

import io.kubernetes.client.openapi.models.V1ContainerPort;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ImageConfig  implements Serializable {
    private String name;
    private String imageName;
    private List<V1ContainerPort> ports;


}
