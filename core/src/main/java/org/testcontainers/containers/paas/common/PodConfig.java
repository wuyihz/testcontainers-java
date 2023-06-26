package org.testcontainers.containers.paas.common;

import lombok.Data;

import java.io.Serializable;
@Data
public class PodConfig  implements Serializable {

    private String name;

    private String nameSpace;



    public PodConfig(PodInfo podInfo){
        if(podInfo == null) {
            return;
        }
        this.name = podInfo.getName();
        this.nameSpace = podInfo.getNameSpace();
    }

}
