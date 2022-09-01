package org.testcontainers.containers;

import com.github.dockerjava.api.model.Container;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;
import org.testcontainers.utility.LogUtils;
import org.testcontainers.utility.ResourceReaper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class ComposeConfiguration {

    void pullImages() {
        // Pull images using our docker client rather than compose itself,
        // (a) as a workaround for https://github.com/docker/compose/issues/5854, which prevents authenticated image pulls being possible when credential helpers are in use
        // (b) so that credential helper-based auth still works when compose is running from within a container
        dockerComposeFiles
            .getDependencyImages()
            .forEach(imageName -> {
                try {
                    log.info(
                        "Preemptively checking local images for '{}', referenced via a compose file or transitive Dockerfile. If not available, it will be pulled.",
                        imageName
                    );
                    new RemoteDockerImage(DockerImageName.parse(imageName))
                        .withImageNameSubstitutor(ImageNameSubstitutor.noop())
                        .get();
                } catch (Exception e) {
                    log.warn(
                        "Unable to pre-fetch an image ({}) depended upon by Docker Compose build - startup will continue but may fail. Exception message was: {}",
                        imageName,
                        e.getMessage()
                    );
                }
            });
    }

    public SELF withServices(@NonNull String... services) {
        this.services = Arrays.asList(services);
        return self();
    }

    void createServices() {
        // services that have been explicitly requested to be started. If empty, all services should be started.
        final String serviceNameArgs = Stream
            .concat(
                services.stream(), // services that have been specified with `withServices`
                scalingPreferences.keySet().stream() // services that are implicitly needed via `withScaledService`
            )
            .distinct()
            .collect(Collectors.joining(" "));

        // Apply scaling for the services specified using `withScaledService`
        final String scalingOptions = scalingPreferences
            .entrySet()
            .stream()
            .map(entry -> "--scale " + entry.getKey() + "=" + entry.getValue())
            .distinct()
            .collect(Collectors.joining(" "));

        String command = optionsAsString() + "up -d";

        if (build) {
            command += " --build";
        }

        if (!Strings.isNullOrEmpty(scalingOptions)) {
            command += " " + scalingOptions;
        }

        if (!Strings.isNullOrEmpty(serviceNameArgs)) {
            command += " " + serviceNameArgs;
        }

        // Run the docker-compose container, which starts up the services
        runWithCompose(command);
    }

    private String optionsAsString() {
        String optionsString = options.stream().collect(Collectors.joining(" "));
        if (optionsString.length() != 0) {
            // ensures that there is a space between the options and 'up' if options are passed.
            return optionsString + " ";
        } else {
            // otherwise two spaces would appear between 'docker-compose' and 'up'
            return StringUtils.EMPTY;
        }
    }

    void waitUntilServiceStarted() {
        listChildContainers().forEach(this::createServiceInstance);

        Set<String> servicesToWaitFor = waitStrategyMap.keySet();
        Set<String> instantiatedServices = serviceInstanceMap.keySet();
        Sets.SetView<String> missingServiceInstances = Sets.difference(servicesToWaitFor, instantiatedServices);

        if (!missingServiceInstances.isEmpty()) {
            throw new IllegalStateException(
                "Services named " +
                missingServiceInstances +
                " " +
                "do not exist, but wait conditions have been defined " +
                "for them. This might mean that you misspelled " +
                "the service name when defining the wait condition."
            );
        }

        serviceInstanceMap.forEach(this::waitUntilServiceStarted);
    }

    private void createServiceInstance(com.github.dockerjava.api.model.Container container) {
        String serviceName = getServiceNameFromContainer(container);
        final ComposeServiceWaitStrategyTarget containerInstance = new ComposeServiceWaitStrategyTarget(
            dockerClient,
            container,
            ambassadorContainer,
            ambassadorPortMappings.getOrDefault(serviceName, new HashMap<>())
        );

        String containerId = containerInstance.getContainerId();
        if (tailChildContainers) {
            followLogs(containerId, new Slf4jLogConsumer(log).withPrefix(container.getNames()[0]));
        }
        //follow logs using registered consumers for this service
        logConsumers
            .getOrDefault(serviceName, Collections.emptyList())
            .forEach(consumer -> followLogs(containerId, consumer));
        serviceInstanceMap.putIfAbsent(serviceName, containerInstance);
    }

    private void waitUntilServiceStarted(String serviceName, ComposeServiceWaitStrategyTarget serviceInstance) {
        final WaitAllStrategy waitAllStrategy = waitStrategyMap.get(serviceName);
        if (waitAllStrategy != null) {
            waitAllStrategy.waitUntilReady(serviceInstance);
        }
    }

    private String getServiceNameFromContainer(com.github.dockerjava.api.model.Container container) {
        final String containerName = container.getLabels().get("com.docker.compose.service");
        final String containerNumber = container.getLabels().get("com.docker.compose.container-number");
        return String.format("%s_%s", containerName, containerNumber);
    }

    private void runWithCompose(String cmd) {
        Preconditions.checkNotNull(composeFiles);
        Preconditions.checkArgument(!composeFiles.isEmpty(), "No docker compose file have been provided");

        final DockerCompose dockerCompose;
        if (localCompose) {
            dockerCompose = new LocalDockerCompose(COMPOSE_EXECUTABLE, composeFiles, project);
        } else {
            dockerCompose = new ContainerisedDockerCompose(DEFAULT_IMAGE_NAME, composeFiles, project);
        }

        dockerCompose.withCommand(cmd).withEnv(env).invoke();
    }

    void registerContainersForShutdown() {
        ResourceReaper
            .instance()
            .registerLabelsFilterForCleanup(Collections.singletonMap("com.docker.compose.project", project));
    }

    @VisibleForTesting
    List<Container> listChildContainers() {
        return dockerClient
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .stream()
            .filter(container -> Arrays.stream(container.getNames()).anyMatch(name -> name.startsWith("/" + project)))
            .collect(Collectors.toList());
    }

    void startAmbassadorContainers() {
        if (!ambassadorPortMappings.isEmpty()) {
            ambassadorContainer.start();
        }
    }

    public SELF withExposedService(String serviceName, int servicePort) {
        return withExposedService(serviceName, servicePort, Wait.defaultWaitStrategy());
    }

    public DockerComposeContainer withExposedService(String serviceName, int instance, int servicePort) {
        return withExposedService(serviceName + "_" + instance, servicePort);
    }

    public DockerComposeContainer withExposedService(
        String serviceName,
        int instance,
        int servicePort,
        WaitStrategy waitStrategy
    ) {
        return withExposedService(serviceName + "_" + instance, servicePort, waitStrategy);
    }

    public SELF withExposedService(String serviceName, int servicePort, @NonNull WaitStrategy waitStrategy) {
        String serviceInstanceName = getServiceInstanceName(serviceName);

        /*
         * For every service/port pair that needs to be exposed, we register a target on an 'ambassador container'.
         *
         * The ambassador container's role is to link (within the Docker network) to one of the
         * compose services, and proxy TCP network I/O out to a port that the ambassador container
         * exposes.
         *
         * This avoids the need for the docker compose file to explicitly expose ports on all the
         * services.
         *
         * {@link GenericContainer} should ensure that the ambassador container is on the same network
         * as the rest of the compose environment.
         */

        // Ambassador container will be started together after docker compose has started
        int ambassadorPort = nextAmbassadorPort.getAndIncrement();
        ambassadorPortMappings
            .computeIfAbsent(serviceInstanceName, __ -> new ConcurrentHashMap<>())
            .put(servicePort, ambassadorPort);
        ambassadorContainer.withTarget(ambassadorPort, serviceInstanceName, servicePort);
        ambassadorContainer.addLink(new FutureContainer(this.project + "_" + serviceInstanceName), serviceInstanceName);
        addWaitStrategy(serviceInstanceName, waitStrategy);
        return self();
    }

    String getServiceInstanceName(String serviceName) {
        String serviceInstanceName = serviceName;
        if (!serviceInstanceName.matches(".*_[0-9]+")) {
            serviceInstanceName += "_1"; // implicit first instance of this service
        }
        return serviceInstanceName;
    }

    /*
     * can have multiple wait strategies for a single container, e.g. if waiting on several ports
     * if no wait strategy is defined, the WaitAllStrategy will return immediately.
     * The WaitAllStrategy uses the startup timeout for everything as a global maximum, but we expect timeouts to be handled by the inner strategies.
     */
    private void addWaitStrategy(String serviceInstanceName, @NonNull WaitStrategy waitStrategy) {
        final WaitAllStrategy waitAllStrategy = waitStrategyMap.computeIfAbsent(
            serviceInstanceName,
            __ -> {
                return new WaitAllStrategy(WaitAllStrategy.Mode.WITH_MAXIMUM_OUTER_TIMEOUT)
                    .withStartupTimeout(startupTimeout);
            }
        );
        waitAllStrategy.withStrategy(waitStrategy);
    }

    /**
     * Specify the {@link WaitStrategy} to use to determine if the container is ready.
     *
     * @param serviceName  the name of the service to wait for
     * @param waitStrategy the WaitStrategy to use
     * @return this
     * @see org.testcontainers.containers.wait.strategy.Wait#defaultWaitStrategy()
     */
    public SELF waitingFor(String serviceName, @NonNull WaitStrategy waitStrategy) {
        String serviceInstanceName = getServiceInstanceName(serviceName);
        addWaitStrategy(serviceInstanceName, waitStrategy);
        return self();
    }

    /**
     * Get the host (e.g. IP address or hostname) that an exposed service can be found at, from the host machine
     * (i.e. should be the machine that's running this Java process).
     * <p>
     * The service must have been declared using DockerComposeContainer#withExposedService.
     *
     * @param serviceName the name of the service as set in the docker-compose.yml file.
     * @param servicePort the port exposed by the service container.
     * @return a host IP address or hostname that can be used for accessing the service container.
     */
    public String getServiceHost(String serviceName, Integer servicePort) {
        return ambassadorContainer.getHost();
    }

    /**
     * Get the port that an exposed service can be found at, from the host machine
     * (i.e. should be the machine that's running this Java process).
     * <p>
     * The service must have been declared using DockerComposeContainer#withExposedService.
     *
     * @param serviceName the name of the service as set in the docker-compose.yml file.
     * @param servicePort the port exposed by the service container.
     * @return a port that can be used for accessing the service container.
     */
    public Integer getServicePort(String serviceName, Integer servicePort) {
        Map<Integer, Integer> portMap = ambassadorPortMappings.get(getServiceInstanceName(serviceName));

        if (portMap == null) {
            throw new IllegalArgumentException(
                "Could not get a port for '" +
                serviceName +
                "'. " +
                "Testcontainers does not have an exposed port configured for '" +
                serviceName +
                "'. " +
                "To fix, please ensure that the service '" +
                serviceName +
                "' has ports exposed using .withExposedService(...)"
            );
        } else {
            return ambassadorContainer.getMappedPort(portMap.get(servicePort));
        }
    }

    public Optional<ContainerState> getContainerByServiceName(String serviceName) {
        String serviceInstantName = getServiceInstanceName(serviceName);
        return Optional.ofNullable(serviceInstanceMap.get(serviceInstantName));
    }

    private void followLogs(String containerId, Consumer<OutputFrame> consumer) {
        LogUtils.followOutput(dockerClient, containerId, consumer);
    }

    private String randomProjectId() {
        return identifier + Base58.randomString(6).toLowerCase();
    }

    public enum RemoveImages {
        /**
         * Remove all images used by any service.
         */
        ALL("all"),

        /**
         * Remove only images that don't have a custom tag set by the `image` field.
         */
        LOCAL("local");

        private final String dockerRemoveImagesType;

        RemoveImages(final String dockerRemoveImagesType) {
            this.dockerRemoveImagesType = dockerRemoveImagesType;
        }

        public String dockerRemoveImagesType() {
            return dockerRemoveImagesType;
        }
    }
}
