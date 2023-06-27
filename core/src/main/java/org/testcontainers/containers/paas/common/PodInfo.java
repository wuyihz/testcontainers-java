package org.testcontainers.containers.paas.common;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
@Data
@EqualsAndHashCode(of = {"name", "nameSpace"})
public class PodInfo implements Serializable {

    private String name;

    private String nameSpace;

    public PodInfo(String name, String nameSpace) {
        this.name = name;
        this.nameSpace = nameSpace;
    }



}
