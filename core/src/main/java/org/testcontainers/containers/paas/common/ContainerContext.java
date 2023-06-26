package org.testcontainers.containers.paas.common;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ContainerContext {

    private static Map<String, PodInfo> ContainerIdPodMap = new HashMap<>();
    private static Map<PodInfo, String> PodContainerIdMap = new HashMap<>();

    public static String addPod(PodInfo podInfo) {
        if(PodContainerIdMap.containsKey(podInfo)) {
            return PodContainerIdMap.get(podInfo);
        } else {
            String uuid = UUID.randomUUID().toString();
            PodContainerIdMap.put(podInfo, uuid);
            ContainerIdPodMap.put(uuid, podInfo);
            return uuid;
        }
    }

    public static String getContainerId(PodInfo podInfo) {
        return PodContainerIdMap.get(podInfo);
    }

    public static PodInfo getPodInfo(String containerId) {
        return ContainerIdPodMap.get(containerId);
    }

}
