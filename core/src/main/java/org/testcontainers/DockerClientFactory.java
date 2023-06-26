package org.testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerClientDelegate;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.Volume;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.DockerMachineClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.images.TimeLimitedLoggedPullImageResultCallback;
import org.testcontainers.utility.ComparableVersion;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.ResourceReaper;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Singleton class that provides initialized Docker clients.
 * <p>
 * The correct client configuration to use will be determined on first use, and cached thereafter.
 */
@Slf4j
public class DockerClientFactory {


    public static final ThreadGroup TESTCONTAINERS_THREAD_GROUP = new ThreadGroup("testcontainers");

    public static final String TESTCONTAINERS_LABEL = DockerClientFactory.class.getPackage().getName();

    public static final String TESTCONTAINERS_SESSION_ID_LABEL = TESTCONTAINERS_LABEL + ".sessionId";

    public static final String TESTCONTAINERS_LANG_LABEL = TESTCONTAINERS_LABEL + ".lang";

    public static final String TESTCONTAINERS_VERSION_LABEL = TESTCONTAINERS_LABEL + ".version";

    public static final String SESSION_ID = UUID.randomUUID().toString();

    public static final Map<String, String> DEFAULT_LABELS = markerLabels();

    static Map<String, String> markerLabels() {
        String implementationVersion = DockerClientFactory.class.getPackage().getImplementationVersion();
        String testcontainersVersion = implementationVersion == null ? "unspecified" : implementationVersion;

        Map<String, String> labels = new HashMap<>();
        labels.put(TESTCONTAINERS_LABEL, "true");
        labels.put(TESTCONTAINERS_LANG_LABEL, "java");
        labels.put(TESTCONTAINERS_VERSION_LABEL, testcontainersVersion);
        return Collections.unmodifiableMap(labels);
    }

    private static final DockerImageName TINY_IMAGE = DockerImageName.parse("alpine:3.16");

    private static DockerClientFactory instance;

    // Cached client configuration
    @VisibleForTesting
    DockerClientProviderStrategy strategy;

    @VisibleForTesting
    DockerClient client;

    @VisibleForTesting
    RuntimeException cachedClientFailure;

    private String activeApiVersion;

    @Getter(lazy = true)
    private final boolean fileMountingSupported = checkMountableFile();

    @VisibleForTesting
    DockerClientFactory() {}

    public static DockerClient lazyClient() {
        return new DockerClientDelegate() {
            @Override
            protected DockerClient getDockerClient() {
                return instance().client();
            }

            @Override
            public String toString() {
                return "LazyDockerClient";
            }
        };
    }

    /**
     * Obtain an instance of the DockerClientFactory.
     *
     * @return the singleton instance of DockerClientFactory
     */
    public static synchronized DockerClientFactory instance() {
        if (instance == null) {
            instance = new DockerClientFactory();
        }

        return instance;
    }

    /**
     * Checks whether Docker is accessible and {@link #client()} is able to produce a client.
     *
     * @return true if Docker is available, false if not.
     */
    public synchronized boolean isDockerAvailable() {
        try {
            client();
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    @Synchronized
    private DockerClientProviderStrategy getOrInitializeStrategy() {
        if (strategy != null) {
            return strategy;
        }

        List<DockerClientProviderStrategy> configurationStrategies = new ArrayList<>();
        ServiceLoader.load(DockerClientProviderStrategy.class).forEach(configurationStrategies::add);

        strategy = DockerClientProviderStrategy.getFirstValidStrategy(configurationStrategies);
        return strategy;
    }

    @UnstableAPI
    public TransportConfig getTransportConfig() {
        return getOrInitializeStrategy().getTransportConfig();
    }

    @UnstableAPI
    public String getRemoteDockerUnixSocketPath() {
        String dockerSocketOverride = System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE");
        if (!StringUtils.isBlank(dockerSocketOverride)) {
            return dockerSocketOverride;
        }

        URI dockerHost = getTransportConfig().getDockerHost();
        String path = "unix".equals(dockerHost.getScheme()) ? dockerHost.getRawPath() : "/var/run/docker.sock";
        return SystemUtils.IS_OS_WINDOWS ? "/" + path : path;
    }

    /**
     * @return a new initialized Docker client
     */
    @Synchronized
    public DockerClient client() {
        // fail-fast if checks have failed previously
        if (cachedClientFailure != null) {
            log.debug("There is a cached checks failure - throwing", cachedClientFailure);
            throw cachedClientFailure;
        }

        if (client != null) {
            return client;
        }

        final DockerClientProviderStrategy strategy = getOrInitializeStrategy();

        client =
            new DockerClientDelegate() {
                @Getter
                final DockerClient dockerClient = strategy.getDockerClient();

                @Override
                public void close() {
                    throw new IllegalStateException("You should never close the global DockerClient!");
                }
            };
        log.info("Docker host IP address is {}", strategy.getDockerHostIpAddress());

        Info dockerInfo = strategy.getInfo();
        log.debug("Docker info: {}", dockerInfo.getRawValues());
        Version version = client.versionCmd().exec();
        log.debug("Docker version: {}", version.getRawValues());
        activeApiVersion = version.getApiVersion();
        log.info(
            "Connected to docker: \n" +
            "  Server Version: " +
            dockerInfo.getServerVersion() +
            "\n" +
            "  API Version: " +
            activeApiVersion +
            "\n" +
            "  Operating System: " +
            dockerInfo.getOperatingSystem() +
            "\n" +
            "  Total Memory: " +
            dockerInfo.getMemTotal() /
            (1024 * 1024) +
            " MB"
        );

        try {
            //noinspection deprecation
            ResourceReaper.instance().init();
        } catch (RuntimeException e) {
            cachedClientFailure = e;
            throw e;
        }

        boolean checksEnabled = !TestcontainersConfiguration.getInstance().isDisableChecks();
        if (checksEnabled) {
            log.debug("Checks are enabled");

            try {
                log.info("Checking the system...");
                checkDockerVersion(version.getVersion());
            } catch (RuntimeException e) {
                cachedClientFailure = e;
                throw e;
            }
        } else {
            log.debug("Checks are disabled");
        }

        return client;
    }

    private void checkDockerVersion(String dockerVersion) {
        boolean versionIsSufficient = new ComparableVersion(dockerVersion).compareTo(new ComparableVersion("1.6.0")) >=
        0;
        check("Docker server version should be at least 1.6.0", versionIsSufficient);
    }

    private void check(String message, boolean isSuccessful) {
        if (isSuccessful) {
            log.info("\u2714\ufe0e {}", message);
        } else {
            log.error("\u274c {}", message);
            throw new IllegalStateException("Check failed: " + message);
        }
    }

    private boolean checkMountableFile() {
        DockerClient dockerClient = client();

        MountableFile mountableFile = MountableFile.forClasspathResource(
            ResourceReaper.class.getName().replace(".", "/") + ".class"
        );

        Volume volume = new Volume("/dummy");
        try {
            return runInsideDocker(
                createContainerCmd -> {
                    createContainerCmd.withBinds(new Bind(mountableFile.getResolvedPath(), volume, AccessMode.ro));
                },
                (__, containerId) -> {
                    try (
                        InputStream stream = dockerClient
                            .copyArchiveFromContainerCmd(containerId, volume.getPath())
                            .exec()
                    ) {
                        stream.read();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            );
        } catch (Exception e) {
            log.debug("Failure while checking for mountable file support", e);
            return false;
        }
    }

    /**
     * Check whether the image is available locally and pull it otherwise
     *
     * @deprecated use {@link RemoteDockerImage}
     */
    @SneakyThrows
    @Deprecated
    public void checkAndPullImage(DockerClient client, String image) {
        try {
            client.inspectImageCmd(image).exec();
        } catch (NotFoundException notFoundException) {
            PullImageCmd pullImageCmd = client.pullImageCmd(image);
            try {
                pullImageCmd.exec(new TimeLimitedLoggedPullImageResultCallback(log)).awaitCompletion();
            } catch (DockerClientException e) {
                // Try to fallback to x86
                pullImageCmd
                    .withPlatform("linux/amd64")
                    .exec(new TimeLimitedLoggedPullImageResultCallback(log))
                    .awaitCompletion();
            }
        }
    }

    /**
     * @return the IP address of the host running Docker
     */
    public String dockerHostIpAddress() {
        return getOrInitializeStrategy().getDockerHostIpAddress();
    }

    public <T> T runInsideDocker(
        Consumer<CreateContainerCmd> createContainerCmdConsumer,
        BiFunction<DockerClient, String, T> block
    ) {
        return runInsideDocker(TINY_IMAGE, createContainerCmdConsumer, block);
    }

    <T> T runInsideDocker(
        DockerImageName imageName,
        Consumer<CreateContainerCmd> createContainerCmdConsumer,
        BiFunction<DockerClient, String, T> block
    ) {
        RemoteDockerImage dockerImage = new RemoteDockerImage(imageName);
        HashMap<String, String> labels = new HashMap<>(DEFAULT_LABELS);
        labels.putAll(ResourceReaper.instance().getLabels());
        CreateContainerCmd createContainerCmd = client.createContainerCmd(dockerImage.get()).withLabels(labels);
        createContainerCmdConsumer.accept(createContainerCmd);
        String id = createContainerCmd.exec().getId();

        try {
            client.startContainerCmd(id).exec();
            return block.apply(client, id);
        } finally {
            try {
                client.removeContainerCmd(id).withRemoveVolumes(true).withForce(true).exec();
            } catch (NotFoundException | InternalServerErrorException e) {
                log.debug("Swallowed exception while removing container", e);
            }
        }
    }

    /**
     * @return the docker API version of the daemon that we have connected to
     */
    public String getActiveApiVersion() {
        client();
        return activeApiVersion;
    }

    /**
     * @return the docker execution driver of the daemon that we have connected to
     */
    public String getActiveExecutionDriver() {
        return getInfo().getExecutionDriver();
    }

    /**
     * @param providerStrategyClass a class that extends {@link DockerMachineClientProviderStrategy}
     * @return whether or not the currently active strategy is of the provided type
     */
    public boolean isUsing(Class<? extends DockerClientProviderStrategy> providerStrategyClass) {
        return strategy != null && providerStrategyClass.isAssignableFrom(this.strategy.getClass());
    }

    @UnstableAPI
    public Info getInfo() {
        return getOrInitializeStrategy().getInfo();
    }
}
