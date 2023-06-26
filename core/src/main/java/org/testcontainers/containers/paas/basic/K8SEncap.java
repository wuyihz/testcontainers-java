package org.testcontainers.containers.paas.basic;

import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.StringUtil;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.paas.common.ImageConfig;
import org.testcontainers.containers.paas.common.PodConfig;
import org.testcontainers.containers.paas.error.TCK8SException;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class K8SEncap {
    private static final Logger log = LoggerFactory.getLogger(K8SEncap.class);

    private static ApiClient apiClient;

    private String configPath = "kubectl.kubeconfig";

    public K8SEncap(){
        try{
          if(apiClient == null) {
              URL url = K8SEncap.class.getClassLoader().getResource(configPath);
              apiClient = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(url.getFile()))).build();
          }
        } catch (FileNotFoundException e) {
            log.warn("init k8s client error", e);
        } catch (IOException e) {
            log.warn("init k8s client error", e);
        }
    }

    private void checkConfig() throws TCK8SException {
        if(null == apiClient) {
            throw new TCK8SException("apiClient init error");
        }
    }

    public V1Pod create(PodConfig podConfig, List<ImageConfig> imageConfigs) throws TCK8SException {
        checkConfig();
        V1Pod pod = new V1Pod().metadata(new V1ObjectMeta()
            .name(podConfig.getName())
            .namespace(podConfig.getNameSpace())
        ).spec(new V1PodSpec().containers(imageConfigs.stream().map(ic -> new V1Container().name(ic.getName()).image(ic.getImageName()).ports(ic.getPorts())).collect(Collectors.toList())));

        GenericKubernetesApi<V1Pod, V1PodList> podClient = new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, "", "v1", "pods", apiClient);
        KubernetesApiResponse<V1Pod> createRes = podClient.create(pod);
        log.info("create pod response = {}", createRes);
        V1Pod latestPod = createRes.getObject();
        log.info("pod create name = {}", podConfig.getName());

        return latestPod;
    }

    public void delete(PodConfig podConfig) throws TCK8SException {
        checkConfig();
        GenericKubernetesApi<V1Pod, V1PodList> podClient = new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, "", "v1", "pods", apiClient);
        V1Pod deleted = podClient.delete(podConfig.getNameSpace(), podConfig.getName()).getObject();
        if(deleted != null) {
            log.info("Received after-deletion status of the request object, will be deleting in background!");
        } else {
            log.info(podConfig.getName() + " deleted!");
        }

    }

    public V1Pod queryPods(PodConfig podConfig) throws TCK8SException {
        checkConfig();
        GenericKubernetesApi<V1Pod, V1PodList> podClient = new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, "", "v1", "pods", apiClient);
        return podClient.get(podConfig.getNameSpace(), podConfig.getName()).getObject();
    }

    public int exec(PodConfig podConfig, String... args) throws TCK8SException, IOException, ApiException, InterruptedException {
        checkConfig();
        Exec exec = new Exec();
        Configuration.setDefaultApiClient(apiClient);
        boolean tty = System.console() != null;
        final Process process = exec.exec(podConfig.getNameSpace(), podConfig.getName(), args.length == 0 ? new String[]{"sh"} : args, true, tty);
        process.waitFor();
        process.destroy();
        return process.exitValue();
    }

    public V1Namespace createNameSpace(String nameSpace) throws TCK8SException, ApiException {
        checkConfig();
        Map<String, String> mp = new HashMap<>();
        mp.put("name", "testcontainers");
        CoreV1Api apiInstance = new CoreV1Api(apiClient);
        V1Namespace v1Namespace = new V1Namespace().metadata(new V1ObjectMeta().name("testcontainers").labels(mp));
        V1Namespace result = apiInstance.createNamespace(v1Namespace, "true", null,null);
        return result;
    }

    public List<V1Pod> listPods(String namespace, String image) throws ApiException {
        CoreV1Api apiInstance = new CoreV1Api(apiClient);
        V1PodList v1PodList = apiInstance.listPodForAllNamespaces(null, null, null, null,null, null,null, null,null);
        return v1PodList.getItems().stream().filter(item -> StringUtils.equals(namespace, item.getMetadata().getNamespace()) &&
            item.getSpec().getContainers().stream().map(i -> i.getImage()).collect(Collectors.toSet()).contains(image))
            .collect(Collectors.toList());
    }
}
